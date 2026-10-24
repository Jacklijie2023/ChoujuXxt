package `fun`.kirari.hanako.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import `fun`.kirari.hanako.AppContainer
import `fun`.kirari.hanako.HanakoApplication
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.screencontext.RealtimeEventKind
import `fun`.kirari.hanako.screencontext.RealtimeScrollSignal
import `fun`.kirari.hanako.screencontext.ScreenContextManager
import `fun`.kirari.hanako.screencontext.WindowCandidate
import `fun`.kirari.hanako.screencontext.isActiveWindowAcceptable
import `fun`.kirari.hanako.screencontext.canBindAsTarget
import `fun`.kirari.hanako.screencontext.isPoolWriteAllowed
import `fun`.kirari.hanako.screencontext.resolveEventTargetPackage
import `fun`.kirari.hanako.screencontext.selectTargetWindowIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 无障碍服务，用于从屏幕 UI 节点树中直接提取文本。
 * 支持多关键字过滤、节点类型过滤、实时监听模式。
 */
class HanakoAccessibilityService : AccessibilityService() {

    // 实时监听状态
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private var monitoringKeywords: List<String>? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingExtraction: Runnable? = null

    /**
     * 防抖窗口内最近的滚动位置证据（含其来源包名）。
     *
     * 单个 300ms 防抖窗口里可能混有滚动与内容变化事件；若滚动信息被随后到达的内容变化事件
     * 覆盖，就会把「真实滚动」误判为「无滚动」，因此这里**独立保留**最近一次滚动证据，
     * 直到该窗口的提取任务真正执行完毕。
     */
    private var pendingScrollSignal: RealtimeScrollSignal? = null
    private var pendingScrollPackage: String? = null

    /**
     * 防抖窗口内最近一次事件的**原始类型**。
     *
     * 与滚动位置证据一起被同帧记录，用于让采集器区分「确为目标应用的滚动事件
     * （`TYPE_VIEW_SCROLLED`）」与「内容 / 文本 / 窗口变化」，并写入只读诊断；只记录枚举，
     * 不记录任何窗口正文。
     */
    private var pendingEventKind: RealtimeEventKind? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!_isMonitoring.value) return

        // 仅处理窗口状态变化、内容变化、文本变化、滚动等与屏幕内容相关的事件。
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> Unit
            else -> return
        }

        // 事件对象会被系统回收，必须在回调内**同帧复制**滚动位置元数据与事件类型。
        // 只读取 Android 已提供的位置字段（scrollY / maxScrollY / fromIndex / toIndex / itemCount），
        // 不额外推断或虚构滚动状态；缺少这些字段时按「滚动已发生、位置未知」交给采集器处理。
        pendingEventKind = realtimeEventKindOf(event.eventType)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            pendingScrollSignal = RealtimeScrollSignal(
                fromIndex = event.fromIndex,
                toIndex = event.toIndex,
                itemCount = event.itemCount,
                scrollY = event.scrollY,
                maxScrollY = event.maxScrollY
            )
            pendingScrollPackage = event.packageName?.toString()
        }

        // 同帧复制「事件来源包名」与「事件窗口 id」：这两项由系统直接提供，
        // **不依赖节点树是否可读**，是「目标应用身份确认」（三态分离的第 ① 态）的关键证据。
        scheduleDebouncedExtraction(
            packageName = event.packageName?.toString(),
            windowId = event.windowId.takeIf { it >= 0 }
        )
    }

    /**
     * 延迟防抖：事件连续到达时取消前一次尚未执行的提取任务，待页面短暂稳定后
     * 再在主线程读取 rootInActiveWindow，避免读到切换过程中的旧内容或反复遍历节点树。
     *
     * [packageName] / [windowId] 由事件本体携带，被本次任务闭包捕获，随本次提取一起使用。
     */
    private fun scheduleDebouncedExtraction(packageName: String?, windowId: Int?) {
        pendingExtraction?.let(handler::removeCallbacks)
        val runnable = Runnable {
            pendingExtraction = null
            // 取出并清空本窗口内累积的滚动证据（含其来源包名与事件类型）。
            val scroll = pendingScrollSignal
            val scrollPackage = pendingScrollPackage
            val eventKind = pendingEventKind
            pendingScrollSignal = null
            pendingScrollPackage = null
            pendingEventKind = null
            if (!_isMonitoring.value || instance == null) return@Runnable
            // 一次窗口选择同时拿到「目标第三方应用窗口文本」与其包名；并把事件来源包名 /
            // 窗口 id 一并传入，使「目标应用身份」在节点树读不到文字时仍可被确认。
            val read = readTargetWindowText(monitoringKeywords, packageName, windowId)
            val text = read.text
            if (!text.isNullOrBlank()) {
                _liveText.value = text
                // 既有链路保持不变：仍按事件包名写入 ScreenContextManager。
                screenContextManager()?.updateFromAccessibility(text, packageName)
            }
            // 三态分离的落地点：
            // ① 目标应用身份 —— 优先取「本次实际读取到的目标窗口包名」；若节点树读不到文字而
            //    拿不到包名，但事件来源包名非空且非本应用，则以事件来源包名作为已确认身份
            //    （系统已明确告知该事件属于该应用）；两者都拿不到 → 无法确认（保留安全门控）。
            // ② 文本可读性 —— 由 `text` 是否为空表达（不再与身份绑定）。
            // ③ 滚动归属 —— 只有「滚动事件来源包名 == 已确认的目标包名」且非本应用才算目标滚动。
            val resolvedTargetPackage = read.packageName
                ?: resolveEventTargetPackage(packageName, OWN_PACKAGE)
                    // 身份边界（R7）：只有**已建立会话绑定**的目标应用才算目标（而非手动选择值）。
                    // 未建立绑定（boundPackage == null）、或候选与绑定目标不一致时一律不确认身份，
                    // 因此桌面（com.miui.home）、通知栏、其它应用的事件都不会写入题目上下文。
                    ?.takeIf { isPoolWriteAllowed(it, boundTargetPackage(), OWN_PACKAGE, containerHomePackages()) }
            val scrollFromTargetWindow = scroll != null &&
                scrollPackage != null &&
                scrollPackage != OWN_PACKAGE &&
                scrollPackage == resolvedTargetPackage
            // 情况 A 修复（关键）：**不再把本调用放在 `if (text.isNullOrBlank())` 之内**。
            // 目标刷题 App 常用 Canvas / WebView / 自绘控件承载题目，节点树常常读到空文本；
            // 若此时直接跳过，就会连同「已确认的目标应用滚动事件」一起丢弃 —— 采集器永远收不到
            // 任何观察，文本池永不更新，界面则一直停留在「未获取到屏幕内容」。
            // 现在改为：文本是否读到、事件类型、来源窗口与滚动元数据**分开传递**；
            // 文本为空时由采集器记为「未读到文本」（不伪装成功、不覆盖既有有效池内容），
            // 但滚动证据被完整保留，使采集器仍可按单飞 + 频率限制决定是否补一次受控 OCR。
            appContainer()?.observeRealtimeAccessibilityObservation(
                text = text,
                windowKey = resolvedTargetPackage,
                scroll = scroll,
                eventKind = eventKind ?: RealtimeEventKind.UNKNOWN,
                eventFromTargetWindow = scrollFromTargetWindow
            )
            // 只记录非敏感状态（事件类型 / 是否读到文本 / 身份证据 / 是否确认目标滚动），不写题干正文。
            // 两个阶段分开记录，便于真机用同一组关键字在 Logcat 中定位一次后台采集任务的链路：
            // [event_received] → [window_identity_resolved] → collector_observation_submitted → …
            AppDebugLogStore.i(
                TAG,
                "event_received kind=${eventKind?.name ?: "UNKNOWN"} " +
                    "srcPkg=${packageName ?: "-"} windowId=${windowId ?: -1}"
            )
            AppDebugLogStore.i(
                TAG,
                "window_identity_resolved evidence=${read.identityEvidence ?: "none"} " +
                    "boundTarget=${boundTargetPackage() ?: "-"} " +
                    "mode=${(instance?.application as? HanakoApplication)?.container?.targetAppMode?.value?.name ?: "-"} " +
                    "targetWindow=${resolvedTargetPackage != null} " +
                    "read=${if (text.isNullOrBlank()) "empty" else "chars:${text?.length ?: 0}"} " +
                    "scrollFromTarget=$scrollFromTargetWindow"
            )
        }
        pendingExtraction = runnable
        handler.postDelayed(runnable, EXTRACT_DEBOUNCE_DELAY_MS)
    }

    private fun cancelPendingExtraction() {
        pendingExtraction?.let(handler::removeCallbacks)
        pendingExtraction = null
        pendingScrollSignal = null
        pendingScrollPackage = null
        pendingEventKind = null
    }

    private fun screenContextManager(): ScreenContextManager? =
        (application as? HanakoApplication)?.container?.screenContextManager

    private fun appContainer(): AppContainer? = (application as? HanakoApplication)?.container

    override fun onInterrupt() {
        cancelPendingExtraction()
        instance = null
    }

    override fun onDestroy() {
        cancelPendingExtraction()
        super.onDestroy()
        instance = null
        _isMonitoring.value = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 允许检索交互窗口（悬浮迷你聊天窗覆盖在第三方刷题 App 上方时，
        // 通过 getWindows() 回退读取底层第三方窗口，而非只读本应用悬浮窗）。
        //
        // 根因修复：原实现 `serviceInfo.flags = serviceInfo.flags or FLAG_...` 只改了
        // getter 返回对象的字段，**没有**把新的 serviceInfo 写回系统（AccessibilityManagerService），
        // 因此系统侧始终没有授予 `flagRetrieveInteractiveWindows`：
        //   - getWindows() 返回空列表 → selectedWindow() 两条路径同时失效 → 目标包名恒为 null；
        //   - 「读不到文字」被误判为「目标应用窗口不存在」，真实滚动事件被丢弃、OCR 永不触发。
        // 现在改为通过 setter 写回（同一 flag 亦冗余声明在 accessibility_config.xml，
        // 保证服务冷启动时就带上该 flag，不依赖重连时机）。
        val info = serviceInfo
        if (info != null) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        }
        // 服务被系统断开后重连时，恢复实时模式开启前的监听状态。
        _isMonitoring.value = monitoringEnabled
        // 走 AppDebugLogStore：同时进入 Logcat（tag=HanakoA11y）与应用内内存缓存，便于导出。
        AppDebugLogStore.i(TAG, "Accessibility service connected, monitoring=$monitoringEnabled")
        // 固定自检行：无障碍服务在自己进程内连接成功时输出；用户可据此确认服务真的连上了。
        AppDebugLogStore.i(
            HanakoApplication.DIAG_TAG,
            "${HanakoApplication.SELF_TEST_MARKER} a11y_connected monitoring=$monitoringEnabled"
        )
    }

    companion object {
        private const val TAG = "HanakoA11y"
        private const val OWN_PACKAGE = "fun.kirari.hanako"
        private const val EXTRACT_DEBOUNCE_DELAY_MS = 300L

        /** 身份未确认的具体原因是「当前窗口不是用户选定的目标应用」（非敏感，仅诊断用）。 */
        private const val NOT_SELECTED_TARGET_EVIDENCE = "not-selected-target"

        private var instance: HanakoAccessibilityService? = null

        /** 实时模式监听请求（跨服务重连保持）。 */
        @Volatile
        private var monitoringEnabled = false

        /** 服务是否正在运行 */
        fun isRunning(): Boolean = instance != null

        /**
         * 目标第三方应用窗口当前是否位于前台（**只读**判断，不返回任何窗口正文）。
         *
         * 用于把「画面变化探测回退」（阶段 2 修复 B）限制在目标刷题 App 位于前台时工作：
         * 用户切到桌面 / 其它应用后不得继续截图探测，否则会把系统界面文字误当成题目写进文本池。
         *
         * 与「读到的文字是否为空」**完全解耦**：判定只看「目标应用身份是否确认且非本应用」，
         * 不要求节点树能读出文字（[TargetWindow.identityConfirmed]）。
         * 若无法确认目标应用位于前台，则返回 false —— **保留安全门控**，由调用方记录「无法确认」原因。
         *
         * 必须在主线程调用（内部会访问 [AccessibilityService.getWindows] 与
         * [AccessibilityService.getRootInActiveWindow]）。
         */
        fun isTargetWindowForeground(): Boolean {
            val service = instance ?: return false
            // 探测路径没有事件上下文，因此不传事件包名 / 窗口 id：仅靠窗口枚举与活动窗口根判定。
            val window = selectedWindow(service)
            val pkg = window.packageName
            return window.identityConfirmed &&
                pkg != null &&
                // 身份边界（R7）：前台确认只对「已建立会话绑定且当前位于前台」的目标成立；
                // 用户切到桌面 / 其它应用后即返回 false，画面探测回退不会继续截图。
                canBindAsTarget(pkg, OWN_PACKAGE, containerHomePackages()) &&
                pkg == boundTargetPackage()
        }

        /** 已建立绑定的目标应用包名；未绑定 / 无容器返回 null（R7 门控基准）。 */
        private fun boundTargetPackage(): String? =
            (instance?.application as? HanakoApplication)?.container?.boundTargetPackage?.value

        /** 系统桌面（HOME）包名集合；无容器时为空集（仍保留「非桌面」安全门控）。 */
        private fun containerHomePackages(): Set<String> =
            (instance?.application as? HanakoApplication)?.container?.homePackages.orEmpty()

        /**
         * 实时重新枚举当前前台窗口，返回「有资格作为绑定目标」的第三方应用包名；无法确认返回 null。
         * 必须在主线程调用。每次调用都重新执行 getWindows() 枚举，不得缓存窗口信息。
         */
        fun liveForegroundPackage(): String? {
            val service = instance ?: return null
            val window = selectedWindow(service)
            val pkg = window.packageName ?: return null
            if (!window.identityConfirmed) return null
            val container = (service.application as? HanakoApplication)?.container ?: return null
            if (!canBindAsTarget(pkg, OWN_PACKAGE, container.homePackages)) return null
            return pkg
        }

        /** 把 Android 无障碍事件类型映射为采集器使用的枚举（纯映射，不含任何窗口正文）。 */
        private fun realtimeEventKindOf(eventType: Int): RealtimeEventKind = when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> RealtimeEventKind.WINDOW_STATE_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> RealtimeEventKind.WINDOW_CONTENT_CHANGED
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> RealtimeEventKind.VIEW_TEXT_CHANGED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> RealtimeEventKind.VIEW_SCROLLED
            else -> RealtimeEventKind.UNKNOWN
        }

        /**
         * 判断系统是否已为 Hanako 启用本无障碍服务（与「服务是否已连接」不同，
         * 用于区分「用户未授权」与「服务被系统回收但已授权」两种状态）。
         */
        fun isServiceEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${HanakoAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        /**
         * 选取「当前应读取内容」的第三方应用目标窗口。
         *
         * 悬浮迷你聊天窗获得焦点时 [AccessibilityService.getRootInActiveWindow] 会指向本应用
         * 的悬浮窗，导致底层第三方刷题 App 的文字被误判为空。此时回退到
         * [AccessibilityService.getWindows]（需 flagRetrieveInteractiveWindows）检索有效的
         * 第三方应用内容窗口。
         *
         * **三态分离（本轮核心修复）**：本方法只负责「目标应用窗口是否存在 / 是否前台」，
         * 与「本次是否读到了文字」完全无关。三条路径依次尝试：
         * 1. 活动窗口快速路径：包名有效且非本应用；窗口列表可用时还需类型确认为应用内容窗口，
         *    窗口列表不可用时以「事件来源包名 == 活动窗口包名」作为替代证据；
         * 2. 候选列表过滤（[selectTargetWindowIndex]）：窗口列表可用时从中挑出第三方应用窗口；
         * 3. **事件来源确认路径**：窗口列表不可用 / 节点树读不到文字而拿不到包名时，若系统
         *    分发的**事件来源包名**是非空且非本应用的第三方应用，即可确认目标应用身份
         *    （此时没有可用节点树 → 文本为空，但身份已确认，**不等于**「没有目标窗口」）。
         *
         * 三条路径都取不到目标时才返回空 [TargetWindow]（[TargetWindow.identityConfirmed] 为 false）。
         */
        private fun selectedWindow(
            service: HanakoAccessibilityService,
            eventPackage: String? = null,
            eventWindowId: Int? = null
        ): TargetWindow {
            val own = service.packageName ?: OWN_PACKAGE

            // getWindows() 在未获 flagRetrieveInteractiveWindows 时可能返回 null/空列表：
            // **必须视为「窗口列表不可用」，而不是「没有第三方窗口」**（两者是完全不同的状态）。
            val windows: List<AccessibilityWindowInfo> = service.windows ?: emptyList()
            val windowListAvailable = windows.isNotEmpty()

            // 1. 活动窗口快速路径。
            val active = service.rootInActiveWindow
            val activePkg = active?.packageName?.toString()
            if (active != null && activePkg != null) {
                val matched = windows.firstOrNull { it.root?.packageName?.toString() == activePkg }
                if (isActiveWindowAcceptable(activePkg, matched?.type, own, AccessibilityWindowInfo.TYPE_APPLICATION, windowListAvailable, eventPackage)) {
                    return TargetWindow(
                        root = active,
                        packageName = activePkg,
                        type = matched?.type,
                        layer = matched?.layer,
                        isActive = matched?.isActive ?: true,
                        isFocused = matched?.isFocused ?: true,
                        identityConfirmed = true,
                        identityEvidence = if (matched != null) "window-list" else "active-root"
                    )
                }
            }

            // 2. 从全部窗口中筛选第三方应用内容窗口（由纯函数决定取舍，便于单元测试）。
            if (windowListAvailable) {
                val candidates = windows.map { w ->
                    WindowCandidate(
                        packageName = w.root?.packageName?.toString(),
                        type = w.type,
                        layer = w.layer,
                        isActive = w.isActive,
                        isFocused = w.isFocused
                    )
                }
                val index = selectTargetWindowIndex(candidates, own, AccessibilityWindowInfo.TYPE_APPLICATION)
                if (index != null) {
                    val window = windows[index]
                    return TargetWindow(
                        root = window.root,
                        packageName = window.root?.packageName?.toString() ?: candidates[index].packageName,
                        type = window.type,
                        layer = window.layer,
                        isActive = window.isActive,
                        isFocused = window.isFocused,
                        identityConfirmed = true,
                        identityEvidence = "window-list"
                    )
                }
            }

            // 3. 事件来源确认路径（关键修复）：窗口列表不可用 / 节点树读不到文字时，
            //    系统已在事件里明确给出「该事件属于哪个应用」。若该包名为有效的第三方应用，
            //    即可确认目标应用身份（本次没有可用节点树 → 文本为空，但**不是**没有目标窗口）。
            //    若事件窗口 id 恰好能在本次窗口枚举中定位，则补充其类型 / 层级元数据。
            val eventResolved = resolveEventTargetPackage(eventPackage, own)
            if (eventResolved != null) {
                val meta = eventWindowId?.let { id -> windows.firstOrNull { it.id == id } }
                return TargetWindow(
                    root = null,
                    packageName = eventResolved,
                    type = meta?.type,
                    layer = meta?.layer,
                    isActive = meta?.isActive ?: true,
                    isFocused = meta?.isFocused ?: false,
                    identityConfirmed = true,
                    identityEvidence = "event-package"
                )
            }

            return TargetWindow(null, null, null, null, false, false, false, null)
        }

        /**
         * 被选中的无障碍目标窗口（含根节点与元数据，供读取与诊断共用）。
         *
         * [identityConfirmed] 明确区分「目标应用身份是否已确认」与「本次是否读到文字」：
         * 前者与后者的解耦正是本轮「三态分离」的落点。[identityEvidence] 为身份证据
         * （非敏感，仅诊断）：`window-list` / `active-root` / `event-package`，未确认为 null。
         */
        private data class TargetWindow(
            val root: AccessibilityNodeInfo?,
            val packageName: String?,
            val type: Int?,
            val layer: Int?,
            val isActive: Boolean,
            val isFocused: Boolean,
            val identityConfirmed: Boolean,
            val identityEvidence: String?
        )

        /** 启动实时监听 */
        fun startMonitoring(keywords: List<String>? = null) {
            monitoringEnabled = true
            instance?.let {
                it.monitoringKeywords = keywords
                it._isMonitoring.value = true
                // 只记关键词数量，不记关键词原文（避免把用户自定义检索词写进日志）。
                AppDebugLogStore.i(TAG, "Monitoring started keywordCount=${keywords?.size ?: 0}")
            }
        }

        /** 停止实时监听并清理待执行的提取任务 */
        fun stopMonitoring() {
            monitoringEnabled = false
            instance?.let {
                it.cancelPendingExtraction()
                it._isMonitoring.value = false
                it.monitoringKeywords = null
                AppDebugLogStore.i(TAG, "Monitoring stopped")
            }
        }

        /**
         * 一次原子操作中读取当前无障碍窗口的文本与其窗口元数据（主线程安全）。
         *
         * 文本与 [packageName]/[type]/[layer]/[isActive]/[isFocused] 来自同一次窗口选择
         * 与遍历，避免两次调用之间窗口切换导致诊断与文本来源不一致。
         *
         * - 服务未连接 / 实时模式未开启 / 无有效第三方窗口 → [text] 为 null。
         * - 仅文本非空才代表读到了有效内容；窗口元数据仍随结果一并返回。
         */
        suspend fun readCurrentWindow(keywords: List<String>? = null): AccessibilityReadResult =
            withContext(Dispatchers.Main.immediate) {
                if (!monitoringEnabled) return@withContext AccessibilityReadResult.empty()
                readTargetWindowText(keywords)
            }

        /**
         * 同步读取「当前被选中的目标第三方应用窗口」的文本与其窗口元数据。
         *
         * 与 [readCurrentWindow] 复用**同一段**窗口选择与遍历实现，只是不额外施加
         * 「实时模式是否开启」的约束（调用方已自行确认），供主线程上的事件防抖提取复用，
         * 确保「文本 / 目标包名 / 窗口类型」三者来自同一次选择，不会因两次选择之间窗口切换而不一致。
         *
         * [eventPackage] / [eventWindowId] 为本次事件来源包名与窗口 id（可空）：
         * 在节点树读不到文字、窗口列表不可用时，用于确认目标应用身份（三态分离的第 ① 态）。
         *
         * 返回的 [AccessibilityReadResult.packageName] 为**已确认的目标应用包名**（可能来自
         * 事件来源），与 [AccessibilityReadResult.text] 是否为空相互独立：
         * - 包名非空、文本为空 → 「目标窗口有效但本次视口没有可读文本」（EMPTY）；
         * - 包名为空 → 「未能确认有效第三方目标窗口」（FAILED）。
         */
        fun readTargetWindowText(
            keywords: List<String>? = null,
            eventPackage: String? = null,
            eventWindowId: Int? = null
        ): AccessibilityReadResult {
            val service = instance ?: return AccessibilityReadResult.empty()
            val target = selectedWindow(service, eventPackage, eventWindowId)
            // 目标应用身份边界（R7）：只有**已建立会话绑定**的目标应用才允许读取其内容。
            // 未建立绑定、或当前窗口是桌面（如 com.miui.home）/ 通知栏 / 其它应用时，
            // 一律视为「目标应用身份未确认」：文本与包名一起作废，调用方据此记为「未能确认目标应用」，
            // 从而**不建立新题、也不改动已有的题目上下文**（保留全部既有安全门控）。
            if (target.packageName != null &&
                !isPoolWriteAllowed(target.packageName, boundTargetPackage(), OWN_PACKAGE, containerHomePackages())
            ) {
                return AccessibilityReadResult.empty().copy(
                    identityEvidence = NOT_SELECTED_TARGET_EVIDENCE
                )
            }
            val root = target.root
            val text = if (root != null) {
                val sb = StringBuilder()
                traverseNodeText(root, keywords, sb)
                sb.toString().takeIf { it.isNotBlank() }
            } else {
                null
            }
            return AccessibilityReadResult(
                text = text,
                packageName = target.packageName,
                windowType = target.type,
                layer = target.layer,
                isActive = target.isActive,
                isFocused = target.isFocused,
                readAtMillis = System.currentTimeMillis(),
                identityConfirmed = target.identityConfirmed,
                identityEvidence = target.identityEvidence
            )
        }

        /**
         * 从当前屏幕提取全部文本。
         * @param keywords 可选关键字列表，为 null 时返回全部文本，非空时返回包含任意关键字的文本
         */
        fun extractTextFromScreen(keywords: List<String>? = null): String {
            val service = instance ?: return ""
            val root = selectedWindow(service).root ?: return ""

            val result = StringBuilder()
            traverseNodeText(root, keywords, result)
            return result.toString()
        }

        /**
         * 从当前屏幕提取文本（单关键字版本，兼容旧调用）。
         */
        fun extractTextFromScreen(keyword: String?): String {
            return extractTextFromScreen(keyword?.let { listOf(it) })
        }

        /**
         * 从指定屏幕区域提取文本。
         * @param zoneOnScreen 屏幕坐标系中的矩形区域
         * @param nodeTypes 要提取的节点类型（如 "android.widget.TextView", "android.widget.EditText"），为空时提取所有
         */
        fun extractTextFromZone(
            zoneOnScreen: Rect,
            nodeTypes: List<String> = emptyList()
        ): String {
            val service = instance ?: return ""
            val root = selectedWindow(service).root ?: return ""

            val result = StringBuilder()
            traverseNodeRect(root, zoneOnScreen, nodeTypes, result)
            return result.toString()
        }

        /**
         * 提取屏幕上的所有可交互元素信息（用于智能分析）。
         */
        fun extractInteractiveElements(): List<InteractiveElement> {
            val service = instance ?: return emptyList()
            val root = selectedWindow(service).root ?: return emptyList()

            val elements = mutableListOf<InteractiveElement>()
            traverseNodeInteractive(root, elements)
            return elements
        }

        private fun traverseNodeText(
            nodeInfo: AccessibilityNodeInfo,
            keywords: List<String>?,
            result: StringBuilder
        ) {
            val text = nodeInfo.text
            if (!text.isNullOrEmpty()) {
                if (keywords.isNullOrEmpty() || keywords.any { text.contains(it, ignoreCase = true) }) {
                    if (result.isNotEmpty()) result.append('\n')
                    result.append(text)
                }
            }

            for (i in 0 until nodeInfo.childCount) {
                val child = nodeInfo.getChild(i) ?: continue
                traverseNodeText(child, keywords, result)
            }
        }

        private fun traverseNodeRect(
            nodeInfo: AccessibilityNodeInfo,
            zone: Rect,
            nodeTypes: List<String>,
            result: StringBuilder
        ) {
            val nodeRect = Rect()
            nodeInfo.getBoundsInScreen(nodeRect)

            if (zone.contains(nodeRect)) {
                val className = nodeInfo.className?.toString() ?: ""
                if (nodeTypes.isEmpty() || nodeTypes.any { className.contains(it) }) {
                    val text = nodeInfo.text
                    if (!text.isNullOrEmpty()) {
                        if (result.isNotEmpty()) result.append('\n')
                        result.append(text)
                    }
                }
            }

            for (i in 0 until nodeInfo.childCount) {
                val child = nodeInfo.getChild(i) ?: continue
                traverseNodeRect(child, zone, nodeTypes, result)
            }
        }

        private fun traverseNodeInteractive(
            nodeInfo: AccessibilityNodeInfo,
            elements: MutableList<InteractiveElement>
        ) {
            val text = nodeInfo.text?.toString()
            val desc = nodeInfo.contentDescription?.toString()
            val className = nodeInfo.className?.toString() ?: ""
            val isClickable = nodeInfo.isClickable
            val isEditable = nodeInfo.isEditable
            val bounds = Rect()
            nodeInfo.getBoundsInScreen(bounds)

            if (!text.isNullOrBlank() || !desc.isNullOrBlank() || isEditable) {
                elements.add(
                    InteractiveElement(
                        text = text ?: "",
                        description = desc ?: "",
                        className = className,
                        isClickable = isClickable,
                        isEditable = isEditable,
                        bounds = bounds
                    )
                )
            }

            for (i in 0 until nodeInfo.childCount) {
                val child = nodeInfo.getChild(i) ?: continue
                traverseNodeInteractive(child, elements)
            }
        }
    }
}

/**
 * 屏幕上的可交互元素
 */
data class InteractiveElement(
    val text: String,
    val description: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: Rect
)

/**
 * 一次无障碍读取产生的共享结果：文本与窗口元数据来自同一次窗口选择与遍历，
 * 避免两次调用之间窗口切换导致诊断记录与文本来源不一致。
 *
 * **三态分离**：本结果同时表达三个彼此独立的判断，调用方不得把它们混为一谈：
 * 1. [identityConfirmed] / [packageName]：目标第三方应用身份是否已确认（与文字可读性无关）；
 * 2. [text]：本次是否读到非空节点树文本（null / 空白 = 没读到）；
 * 3. [identityEvidence]：身份确认所用证据（非敏感，仅诊断）。
 *
 * [text] 为 null 且 [identityConfirmed] 为 true 表示「目标窗口有效但本次视口没有可读文本」，
 * **不等于**「没有目标窗口」；两者都由调用方进入本地 OCR 兜底或明确失败，不得据此认定读取成功。
 */
data class AccessibilityReadResult(
    val text: String?,
    val packageName: String?,
    val windowType: Int?,
    val layer: Int?,
    val isActive: Boolean,
    val isFocused: Boolean,
    val readAtMillis: Long,
    /** 是否已确认「当前目标第三方应用」身份（与「能否读到文字」相互独立）。 */
    val identityConfirmed: Boolean = false,
    /** 身份确认所用证据（非敏感，仅诊断）：`window-list` / `active-root` / `event-package`，未确认为 null。 */
    val identityEvidence: String? = null
) {
    companion object {
        fun empty(): AccessibilityReadResult = AccessibilityReadResult(
            text = null,
            packageName = null,
            windowType = null,
            layer = null,
            isActive = false,
            isFocused = false,
            readAtMillis = System.currentTimeMillis()
        )
    }
}
