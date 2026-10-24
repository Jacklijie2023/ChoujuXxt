package `fun`.kirari.hanako

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import `fun`.kirari.hanako.accessibility.HanakoAccessibilityService
import `fun`.kirari.hanako.capture.CaptureUiGate
import `fun`.kirari.hanako.capture.ScreenCaptureManager
import `fun`.kirari.hanako.capture.StaleFrameException
import `fun`.kirari.hanako.chat.ChatConfig
import `fun`.kirari.hanako.chat.ChatModelCatalog
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.chat.ScreenContextResult
import `fun`.kirari.hanako.chat.UnifiedChatStreamSource
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.ChatSessionStore
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.data.SettingsStore
import `fun`.kirari.hanako.data.resolveModelName
import `fun`.kirari.hanako.data.resolveModelProvider
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.network.NetworkClientProvider
import `fun`.kirari.hanako.network.ProviderModelsApi
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.overlay.OverlayRuntimeState
import `fun`.kirari.hanako.overlay.TtsHelper
import `fun`.kirari.hanako.screencontext.CaptureFrameStore
import `fun`.kirari.hanako.screencontext.ScreenContextManager
import `fun`.kirari.hanako.screencontext.ScreenContextSource
import `fun`.kirari.hanako.screencontext.ScreenReadReport
import `fun`.kirari.hanako.screencontext.ScreenReadStatus
import `fun`.kirari.hanako.screencontext.TargetAppMode
import `fun`.kirari.hanako.screencontext.TargetBindingState
import `fun`.kirari.hanako.screencontext.canBindAsTarget
import `fun`.kirari.hanako.screencontext.canEstablishBinding
import `fun`.kirari.hanako.screencontext.assembleAccumulatedWithLive
import `fun`.kirari.hanako.screencontext.assessLiveScreenContent
import `fun`.kirari.hanako.screencontext.LIVE_VIEWPORT_MARK
import `fun`.kirari.hanako.screencontext.measureViewportNovelty
import `fun`.kirari.hanako.screencontext.ScreenChangeDetector
import `fun`.kirari.hanako.screencontext.classifyCaptureMissingStage
import `fun`.kirari.hanako.screencontext.classifyOcrFailureStage
import `fun`.kirari.hanako.screencontext.countScreenTextLines
import `fun`.kirari.hanako.screencontext.failureReasonText
import `fun`.kirari.hanako.screencontext.normalizeScreenText
import `fun`.kirari.hanako.screencontext.ocrFingerprintOf
import `fun`.kirari.hanako.screencontext.ocrLines
import `fun`.kirari.hanako.screencontext.ScreenContextPool
import `fun`.kirari.hanako.screencontext.organizeOcrText
import `fun`.kirari.hanako.screencontext.PoolUpdateResult
import `fun`.kirari.hanako.screencontext.PoolInjectionDecision
import `fun`.kirari.hanako.screencontext.decidePoolInjection
import `fun`.kirari.hanako.screencontext.decideOcrContextAction
import `fun`.kirari.hanako.screencontext.decideRequestScreenContext
import `fun`.kirari.hanako.screencontext.questionNumberOf
import `fun`.kirari.hanako.screencontext.probeSkipReason
import `fun`.kirari.hanako.screencontext.RequestScreenContextAction
import `fun`.kirari.hanako.screencontext.RealtimeEventKind
import `fun`.kirari.hanako.screencontext.RealtimeScrollSignal
import `fun`.kirari.hanako.screencontext.RealtimeViewportCollector
import `fun`.kirari.hanako.screencontext.requiresSessionBoundary
import `fun`.kirari.hanako.screencontext.ScreenSample
import `fun`.kirari.hanako.screencontext.ScreenProbeDecision
import `fun`.kirari.hanako.screencontext.shouldResolveScreenContext
import `fun`.kirari.hanako.util.HapticHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AppContainer(private val appContext: Context) {
    val networkClientProvider = NetworkClientProvider()
    val settingsStore = SettingsStore(appContext)
    val unifiedLLMClient = UnifiedLLMClient(clientProvider = networkClientProvider)
    val localOcrManager = LocalOcrManager(appContext)

    /**
     * 「本次送入本地 OCR 的截图」请求级内存快照（仅本机诊断用，不落盘、不上传）。
     * 供完整文本查看界面按 traceId 展示该次请求真正参与 OCR 的那张图。
     */
    val captureFrameStore = CaptureFrameStore()
    val settingsRepository = SettingsRepository(settingsStore)

    /** 应用级协程作用域：承载聊天会话流式任务与设置快照订阅。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前设置的只读快照，供无法直接订阅 Compose 状态的组件（如聊天会话）读取。 */
    private val appSettings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    /**
     * 当前用户选定的目标应用包名（题目上下文的身份白名单）只读快照。
     *
     * 供无障碍服务（事件路径 / 窗口前台确认）同步读取：只有该应用才允许建立或更新题目上下文。
     * 未选定（null）时一律不放行，保留原有的「非本应用 + 应用内容窗口类型」等安全门控。
     */
    val selectedTargetAppPackage: StateFlow<String?> = appSettings
        .map { it.targetAppPackage?.trim()?.ifBlank { null } }
        .stateIn(scope = appScope, started = SharingStarted.Eagerly, initialValue = null)

    /** 目标应用绑定模式（R7）：默认 AUTO。 */
    val targetAppMode: StateFlow<TargetAppMode> = appSettings
        .map { it.targetAppMode }
        .stateIn(scope = appScope, started = SharingStarted.Eagerly, initialValue = TargetAppMode.AUTO)

    /** 目标应用「会话绑定」状态机（R7）。 */
    val targetBinding = TargetBindingState()

    private val _boundTargetPackage = MutableStateFlow<String?>(null)

    /** 已建立绑定的目标应用包名（未绑定为 null）；供无障碍服务门控同步读取。 */
    val boundTargetPackage: StateFlow<String?> = _boundTargetPackage.asStateFlow()

    /** 系统桌面（HOME）包名集合：进程内解析一次并缓存。桌面不得作为绑定目标。 */
    val homePackages: Set<String> by lazy { resolveHomePackages() }

    private fun resolveHomePackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        appContext.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * 依「实时前台应用」尝试建立/重建绑定。
     * 实时前台由无障碍服务每次重新枚举 getWindows() 得到（不缓存）。
     */
    fun bindTargetFromLiveForeground(): String? {
        val live = HanakoAccessibilityService.liveForegroundPackage() ?: return boundTargetPackage.value
        if (!canEstablishBinding(live, targetAppMode.value, selectedTargetAppPackage.value,
                appContext.packageName, homePackages)) {
            return boundTargetPackage.value
        }
        return applyBinding(live)
    }

    /** 设置页「重新绑定」按钮：显式重绑定到指定目标。目标无资格时忽略。 */
    fun rebindTarget(packageName: String): String? {
        if (!canBindAsTarget(packageName, appContext.packageName, homePackages)) return boundTargetPackage.value
        return applyBinding(packageName)
    }

    private fun applyBinding(packageName: String): String? {
        val previous = targetBinding.boundPackage()
        targetBinding.bind(packageName)
        _boundTargetPackage.value = packageName
        if (requiresSessionBoundary(previous, packageName)) {
            // 新会话边界：旧题的累计上下文一律丢弃，绝不串入新应用。
            realtimeViewportCollector.onSessionBoundary()
            chatSessionHolder.onTargetRebound()
            AppDebugLogStore.i(ScreenCtxTag, "session-boundary rebind ${previous ?: "-"} -> $packageName")
        }
        return packageName
    }

    /** 实时屏幕上下文管理器（内存快照，随实时模式启停）。 */
    val screenContextManager = ScreenContextManager()

    /**
     * 阶段 2：实时「持续采集」器与本地文本池。
     *
     * 只在实时模式下随无障碍事件（以及确有必要时的单次本地 OCR）后台累积**同一道题**的屏幕文本，
     * 用于验证「文本池能否连续积累」与「能否检测题目切换」。
     *
     * 关键边界：[currentScreenContextForPrompt] 仍走原有单次解析流程，**不读取**该文本池，
     * 因此任何一次发送请求使用的屏幕文本都不受本采集器影响；本采集器也**不**发起大模型请求。
     */
    val realtimeViewportCollector = RealtimeViewportCollector()

    /**
     * 「画面变化探测回退」的后台任务（阶段 2 修复 B）。
     *
     * 仅在实时模式开启期间运行，且每次真正截图前还要通过
     * [RealtimeViewportCollector.shouldProbeScreen] 的「最近无目标滚动事件」抑制判定 ——
     * 因此它不是常开定时截图，而是「目标应用不发送滚动事件」时的兜底。
     */
    private var realtimeProbeJob: Job? = null

    /** 模型列表拉取（复用既有 OpenAI/Anthropic/Google 适配与认证头规则）。 */
    val providerModelsApi = ProviderModelsApi(networkClientProvider)

    /**
     * 聊天模型全量列表内存缓存（聊天页与迷你窗共用）。
     *
     * 按 providerId 键控，含 loading / error 态；拉取失败由 UI 回退展示该 API 已配置的 chatModel。
     */
    val chatModelCatalog = ChatModelCatalog(
        api = providerModelsApi,
        scope = appScope,
        trustAllHttpsCertificatesProvider = { appSettings.value.trustAllHttpsCertificates }
    )

    /**
     * 全局单例聊天会话持有者。
     *
     * 通过 `AppContainer` 注入，聊天页面与（P3）迷你聊天共享同一实例，
     * 会话状态不局限于任何单个 Composable 的局部 `remember`。
     */
    val chatSessionHolder = ChatSessionHolder(
        streamSource = UnifiedChatStreamSource(unifiedLLMClient),
        configProvider = {
            val settings = appSettings.value
            ChatConfig(
                provider = settings.resolveModelProvider(ModelPurpose.TEXT),
                model = settings.resolveModelName(ModelPurpose.TEXT),
                systemPrompt = CHAT_SYSTEM_PROMPT,
                trustAllHttpsCertificates = settings.trustAllHttpsCertificates,
                providers = settings.providers
            )
        },
        scope = appScope,
        persistence = ChatSessionStore(appContext),
        screenContextInjector = { traceId -> currentScreenContextForPrompt(traceId) },
        screenContextReporter = { screenContextManager.recordRead(it) },
        traceLogger = { AppDebugLogStore.i(ChatTraceTag, it) }
    )

    init {
        // 初始化 TTS
        TtsHelper.init(appContext)
        // 恢复用户保存的朗读语音（设置页「朗读语音」选择；引擎就绪后自动应用）。
        appScope.launch {
            appSettings.firstOrNull()?.let { TtsHelper.setVoice(it.ttsVoiceName) }
        }
        // 初始化触觉反馈
        HapticHelper.init(appContext)

        // 阶段 2：声明本地 OCR 能力。Lite flavor 没有本地 ML Kit 识别，必须显式标记为能力降级，
        // 持续采集不得调用不存在的实现，只使用无障碍节点树文本。
        appScope.launch {
            val ocrStatus = localOcrManager.installationStatus()
            realtimeViewportCollector.setOcrCapability(
                available = ocrStatus.installed,
                degradedNote = if (ocrStatus.installed) null
                else "当前版本不支持本地文字识别（Lite），持续采集仅使用无障碍节点树文本"
            )
        }

        // 实时屏幕感知：开关驱动无障碍持续监听与本地上下文启停，关闭后停止主动感知。
        appScope.launch {
            appSettings
                .map { it.realTimeMode }
                .distinctUntilChanged()
                .collect { enabled ->
                    AppDebugLogStore.i(ScreenCtxTag, "realTimeMode startup/change -> $enabled")
                    if (enabled) {
                        screenContextManager.start()
                        HanakoAccessibilityService.startMonitoring()
                        // 阶段 2：开启持续采集（从空文本池开始）。
                        realtimeViewportCollector.setEnabled(true)
                        // 阶段 2 修复 B：启动受控「画面变化探测回退」（仅在该模式下运行）。
                        startRealtimeScreenProbe()
                    } else {
                        // 先停探测任务，避免关闭瞬间仍有在途截图 / OCR。
                        stopRealtimeScreenProbe()
                        HanakoAccessibilityService.stopMonitoring()
                        screenContextManager.stop()
                        // 阶段 2：停止采集并释放文本池，避免关闭后仍残留会话上下文。
                        realtimeViewportCollector.setEnabled(false)
                    }
                }
        }
    }

    /**
     * 提交模型前解析可注入的屏幕上下文。
     *
     * [traceId] 由聊天链路传入（本次发送的助手消息 id），屏幕读取阶段的所有日志共用
     * 该 ID，便于把「用户发送 → 屏幕读取 → 注入 → 模型请求」关联成同一次请求。
     *
     * - 实时模式关闭 → [ScreenContextResult.NotRequested]（普通聊天）。
     * - 实时模式已选但实时互动服务未启动（`realTimeMode` 只是模式偏好）→ [ScreenContextResult.NotRequested]。
     * - 无障碍服务未启用 → [ScreenContextResult.Missing]（明确原因，不静默降级）。
     * - 无障碍读到了当前屏幕文本 → [ScreenContextResult.Available]。
     * - 无障碍读不到时走本地 OCR 兜底（仅本地识别，不发起云端 OCR/视觉请求），
     *   绝不回退到可能来自上一屏的旧缓存。
     */
    private suspend fun currentScreenContextForPrompt(traceId: String): ScreenContextResult {
        // S2 触发点（用户主动发送提问前）：按实时前台应用建立/重建会话绑定。
        // 无障碍窗口枚举必须在主线程执行，故显式切换到 Main。
        withContext(Dispatchers.Main) { bindTargetFromLiveForeground() }

        val settings = appSettings.value

        // 实时互动「运行期」门控：realTimeMode 只是用户在首页选择的模式偏好（见 homeModeTransform），
        // 并不代表实时互动服务（悬浮球 / 迷你窗宿主）已经启动。
        // 关闭模式、或「选了模式但服务未启动」时，一律完全绕过屏幕读取，
        // 绝不触碰无障碍 / 截图 / OCR；用户原始消息直接走普通模型请求，
        // 不因缺屏幕上下文而 Missing / Failed，更不得把「没启动」误报成「截图授权失败」。
        val runtimeRunning = OverlayRuntimeState.running.value
        if (!shouldResolveScreenContext(settings.realTimeMode, runtimeRunning)) {
            val reason =
                if (!settings.realTimeMode) "realTimeMode=false" else "real-time runtime not started"
            AppDebugLogStore.i(
                ScreenCtxTag,
                "trace=$traceId screen-ctx bypass: $reason -> NotRequested " +
                    "(realTimeMode=${settings.realTimeMode} runtimeRunning=$runtimeRunning)"
            )
            return ScreenContextResult.NotRequested
        }

        val now = System.currentTimeMillis()

        // 阶段 0：无障碍服务是否启用 / 是否连接（真实区分「未授权」与「已授权但未连接」）。
        val a11yEnabled = HanakoAccessibilityService.isServiceEnabled(appContext)
        val a11yConnected = HanakoAccessibilityService.isRunning()

        if (!a11yEnabled) {
            val report = ScreenReadReport(
                traceId = traceId,
                status = ScreenReadStatus.SEND_FAILED,
                a11yEnabled = false,
                a11yConnected = false,
                failureStage = "a11y_disabled",
                readAtMillis = now
            )
            screenContextManager.recordRead(report)
            AppDebugLogStore.i(ScreenCtxTag, "trace=$traceId stage=a11y_disabled")
            return ScreenContextResult.Missing(failureReasonText("a11y_disabled"))
        }

        // 诊断累积字段：成功与失败路径共用，绝不把「第一次窗口元数据 + 第二次文本」拼成一条记录。
        var screenshotAttempted: Boolean? = null
        var screenshotSucceeded: Boolean? = null
        var captureError: String? = null
        var bitmapWidth: Int? = null
        var bitmapHeight: Int? = null
        var ocrExecuted: Boolean? = null
        var ocrNotAvailable = false
        var ocrError: String? = null
        var rawOcrCharCount: Int? = null
        var ocrLineCount: Int? = null
        var ocrFingerprint: String? = null
        var optionMarkerCount: Int? = null
        var optionAssociation: String? = null
        // 引擎原始拼接文本（仅诊断对比用；不进日志、不进模型）。
        var rawOcrText: String? = null
        // 截图是否因「未取到窗口隐藏后的新帧」失败：与其它截图执行失败区分开。
        var staleFrameRejected = false

        // 阶段 1：无障碍读取（文本与窗口元数据来自同一次窗口选择与遍历）。
        val read = HanakoAccessibilityService.readCurrentWindow()
        val a11yText = read.text
        val windowValid = read.packageName != null && read.windowType != null

        fun readReport(status: ScreenReadStatus): ScreenReadReport = ScreenReadReport(
            traceId = traceId,
            status = status,
            source = ScreenContextSource.ACCESSIBILITY,
            a11yEnabled = a11yEnabled,
            a11yConnected = a11yConnected,
            targetPackage = read.packageName,
            windowType = read.windowType,
            windowLayer = read.layer,
            windowActive = read.isActive,
            windowFocused = read.isFocused,
            windowValid = windowValid,
            screenshotAttempted = screenshotAttempted,
            screenshotSucceeded = screenshotSucceeded,
            captureError = captureError,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            ocrExecuted = ocrExecuted,
            ocrNotAvailable = ocrNotAvailable,
            ocrError = ocrError,
            ocrCharCount = rawOcrCharCount,
            ocrLineCount = ocrLineCount,
            ocrFingerprint = ocrFingerprint,
            optionMarkerCount = optionMarkerCount,
            optionAssociation = optionAssociation,
            rawOcrText = rawOcrText,
            readAtMillis = now
        )

        fun fail(stage: String, source: ScreenContextSource = ScreenContextSource.LOCAL_OCR): ScreenContextResult {
            val report = readReport(ScreenReadStatus.SEND_FAILED).copy(
                source = source,
                failureStage = stage
            )
            screenContextManager.recordRead(report)
            AppDebugLogStore.i(
                ScreenCtxTag,
                "trace=$traceId stage=$stage conn=$a11yConnected win=$windowValid " +
                    "shotAttempt=$screenshotAttempted shotOk=$screenshotSucceeded " +
                    "captureErr=$captureError staleFrame=$staleFrameRejected " +
                    "ocrExec=$ocrExecuted ocrNotAvail=$ocrNotAvailable " +
                    "ocrErr=$ocrError ocrChars=$rawOcrCharCount ocrLines=$ocrLineCount " +
                    "optionMarkers=$optionMarkerCount ocrFp=$ocrFingerprint"
            )
            return ScreenContextResult.Missing(failureReasonText(stage))
        }

        if (a11yText != null) {
            screenContextManager.updateFromAccessibility(a11yText, read.packageName)
            // 只使用「本次无障碍读到的新鲜文本」，不得回退到 currentText() 的 TTL 缓存：
            // 上一次读取的题目即使仍在 30 秒有效期内，也未必对应当前屏幕；
            // 拿旧缓存当当前题目注入模型正是「上一题内容串到下一题」的来源之一。
            val text = normalizeScreenText(a11yText, ScreenContextManager.DEFAULT_MAX_TEXT_LENGTH)
                .takeIf { it.isNotBlank() }
            if (text != null) {
                val report = readReport(ScreenReadStatus.CONTEXT_AVAILABLE).copy(
                    charCount = text.length,
                    // 记录真正注入前的文本，供本机诊断预览（不写 Logcat）。
                    injectedText = text
                )
                screenContextManager.recordRead(report)
                AppDebugLogStore.i(
                    ScreenCtxTag,
                    "trace=$traceId source=a11y pkg=${read.packageName} chars=${text.length}"
                )
                // 同题滚动时，当前屏可能只是题干/选项的一部分；若累计池已可证明覆盖本屏，
                // 用池快照（含同题已累积片段）注入，否则回退到本次无障碍即时文本。
                // R8.4：先算出「累计池是否可被本帧证明覆盖」，再交给**本次请求的内容质量门控**决策；
                // 池可证明覆盖 ≠ 「历史池显示 COMPLETE 就无条件注入」（后者由 [decidePoolInjection] 单独拒绝）。
                val pooled = pooledContextIfProvable(
                    liveViewport = text,
                    currentWindowPackage = read.packageName,
                    traceId = traceId,
                    liveSource = ScreenContextSource.ACCESSIBILITY,
                    liveReport = report
                )
                val decision = decideRequestScreenContext(
                    liveText = text,
                    currentWindowPackage = read.packageName,
                    boundTargetPackage = boundTargetPackage.value,
                    poolSnapshotProvable = pooled != null
                )
                AppDebugLogStore.i(
                    ScreenCtxTag,
                    "screen_context_gate trace=$traceId stage=a11y action=${decision.action} " +
                        "quality=${decision.quality} reason=${decision.reasonCode} " +
                        "bound=${boundTargetPackage.value ?: "-"} curWin=${read.packageName ?: "-"} " +
                        "liveChars=${text.length} poolProvable=${pooled != null} visualInput=false"
                )
                when (decision.action) {
                    RequestScreenContextAction.USE_POOL -> return pooled!!
                    // 身份未确认（当前前台不是已绑定目标应用）：明确报告不可用，不得拿旧内容或
                    // 其它应用的内容充当当前题目。
                    RequestScreenContextAction.BLOCKED ->
                        return fail(decision.blockedStage ?: "content_insufficient", ScreenContextSource.ACCESSIBILITY)
                    RequestScreenContextAction.USE_LIVE -> {
                        val finalReport = report.copy(
                            contentQuality = decision.quality.name,
                            visualInput = false
                        )
                        screenContextManager.recordRead(finalReport)
                        // R8.7 P3：即使只注入本次视口，也必须留下可自证的请求级证据
                        // （最终文本长度 + 稳定指纹），供与 `stream-send` 侧逐条比对。
                        AppDebugLogStore.i(
                            ScreenCtxTag,
                            "trace=$traceId request_context_evidence source=a11y " +
                                "viewportId=live-a11y-${System.currentTimeMillis()} " +
                                "capturedAtMillis=$now poolInjected=false hasSegmentBreak=false liveIncluded=true " +
                                "injectedSegmentIds=- contextChars=${text.length} " +
                                "contextFp=${ocrFingerprintOf(text)}"
                        )
                        return ScreenContextResult.Available(
                            text,
                            traceId,
                            ScreenContextSource.ACCESSIBILITY,
                            finalReport
                        )
                    }
                    RequestScreenContextAction.FRESH_CAPTURE -> {
                        // 真机证据：粉笔资料分析页无障碍只返回 36 字外壳（标题 / 题号 / 重复「材料」），
                        // 一行题干与选项都读不到。此时**不再**拿这 36 字当当前题目注入模型，而是立刻为
                        // **本次请求**取一帧新鲜画面做本地 OCR（截图 / 识别侧仍走既有限频、去重与安全门控）。
                        AppDebugLogStore.i(
                            ScreenCtxTag,
                            "trace=$traceId a11y-insufficient quality=${decision.quality} " +
                                "reason=${decision.reasonCode} → 本次请求强制新鲜截图 + 本地 OCR"
                        )
                    }
                }
            }
            AppDebugLogStore.i(
                ScreenCtxTag,
                "trace=$traceId a11y-blank pkg=${read.packageName} → 不回退旧缓存，转本地 OCR"
            )
        } else {
            AppDebugLogStore.i(
                ScreenCtxTag,
                "trace=$traceId a11y-miss conn=$a11yConnected win=$windowValid " +
                    "pkg=${read.packageName} type=${read.windowType}"
            )
        }

        // 阶段 2：截图会话确认 + 本地 OCR 兜底（fresh 模式，不回退旧缓存）。
        if (!ScreenCaptureManager.hasActiveSession(settings.screenCaptureMethod)) {
            // 区分「从未建立会话（尚未授权）」与「曾建立但运行期丢失」：前者是权限问题，
            // 后者是运行期故障，提示必须与真实原因一致，不得一律写成「请先完成授权」。
            return fail(
                classifyCaptureMissingStage(
                    ScreenCaptureManager.hasEverEstablishedSession(settings.screenCaptureMethod)
                )
            )
        }
        if (!screenContextManager.active.value) {
            return fail("manager_inactive")
        }

        val ocrText = screenContextManager.refreshViaOcr(allowCached = false) {
            screenshotAttempted = true
            val bitmap = try {
                // 用户请求触发的强制采集：优先「真正隐藏 Hanako 浮窗 + 收起输入法 → 取隐藏后新帧 → 恢复」。
                // 位图遮罩只会把被浮窗覆盖的区域涂黑，无法还原其背后的目标题目页面；键盘更是独立窗口、
                // 不被遮罩覆盖，不收起就会被拍进截图并被 OCR 认成题目（真机证据 `GHI JKL MNO`）。
                AppDebugLogStore.i(
                    ScreenCtxTag,
                    "trace=$traceId capture-occluding requested=true " +
                        "mask=${CaptureUiGate.isMaskProviderAvailable()} " +
                        "hide=${CaptureUiGate.isHideProviderAvailable()} method=${settings.screenCaptureMethod}"
                )
                ScreenCaptureManager.captureLatestBitmap(
                    appContext,
                    settings.screenCaptureMethod,
                    occludingCaptureRequired = true
                )
            } catch (t: Throwable) {
                // 记录真实异常（含完整堆栈，非敏感），避免只留下被归类后的失败阶段。
                val root = t.cause ?: t
                captureError = root.javaClass.simpleName
                // 画面新鲜度失败与其它截图执行失败必须区分：前者是取帧时机问题，不是会话/线程故障。
                staleFrameRejected = root is StaleFrameException
                AppDebugLogStore.e(
                    ScreenCtxTag,
                    "trace=$traceId capture-threw ${root.javaClass.simpleName} staleFrame=$staleFrameRejected " +
                        "msg=${root.message}",
                    root
                )
                null
            }
            screenshotSucceeded = bitmap != null
            if (bitmap == null) return@refreshViaOcr null
            bitmapWidth = bitmap.width
            bitmapHeight = bitmap.height
            // 诊断快照：登记本次**真正送入本地 OCR**的截图（等比缩放 + JPEG，仅内存、不落盘、不上传）。
            // 在调用 OCR 之前登记，保证 OCR 抛异常时也能看到该次请求的输入图。
            captureFrameStore.record(traceId, bitmap)
            ocrExecuted = true
            try {
                // 本地 OCR 返回「原始拼接文本 + 带坐标的行」，先按坐标恢复阅读顺序、
                // 关联选项标签与正文，再把整理结果送去注入；原始文本仅留作诊断对比。
                val ocrResult = localOcrManager.recognize(bitmap)
                val organization = organizeOcrText(ocrResult)
                rawOcrText = ocrResult.rawText.takeIf { it.isNotBlank() }
                // 仅记录不泄漏题干的指标：字符数、行数、选项标记数、结果指纹。
                rawOcrCharCount = rawOcrText?.length
                ocrLineCount = countScreenTextLines(organization.text)
                optionMarkerCount = organization.optionMarkerCount
                optionAssociation = organization.association.name
                ocrFingerprint = organization.text.takeIf { it.isNotBlank() }?.let(::ocrFingerprintOf)
                AppDebugLogStore.i(
                    ScreenCtxTag,
                    "trace=$traceId ocr-organize rawChars=${rawOcrCharCount ?: 0} " +
                        "lines=${organization.rowCount} orderedChars=${organization.text.length} " +
                        "options=${organization.optionMarkerCount} association=${organization.association}"
                )
                organization.text.takeIf { it.isNotBlank() }
            } catch (t: Throwable) {
                if (t is UnsupportedOperationException) ocrNotAvailable = true
                ocrError = (t.cause ?: t).javaClass.simpleName
                null
            }
        }

        if (ocrText != null) {
            val report = readReport(ScreenReadStatus.CONTEXT_AVAILABLE).copy(
                source = ScreenContextSource.LOCAL_OCR,
                charCount = ocrText.length,
                // 记录真正注入前的文本，供本机诊断预览（不写 Logcat）。
                injectedText = ocrText
            )
            screenContextManager.recordRead(report)
            AppDebugLogStore.i(
                ScreenCtxTag,
                "trace=$traceId source=ocr shotOk=$screenshotSucceeded ocrExec=$ocrExecuted " +
                    "ocrChars=$rawOcrCharCount ocrLines=$ocrLineCount optionMarkers=$optionMarkerCount " +
                    "ocrFp=$ocrFingerprint bitmap=${bitmapWidth}x$bitmapHeight chars=${ocrText.length}"
            )
            // 与无障碍分支同理：本次 OCR 只反映当前屏的一部分时，优先注入已被证明同题的累计池快照。
            // R8.4：本次新鲜 OCR 结果先过**请求级内容质量门控** —— 只有页面标题 / 导航 / 重复「材料」
            // 外壳时，不得当作「已识别到题目」；身份未确认时也不得当作已确认的当前屏幕上下文。
            val pooled = pooledContextIfProvable(
                liveViewport = ocrText,
                currentWindowPackage = read.packageName,
                traceId = traceId,
                liveSource = ScreenContextSource.LOCAL_OCR,
                liveReport = report
            )
            val decision = decideOcrContextAction(
                ocrText = ocrText,
                currentWindowPackage = read.packageName,
                boundTargetPackage = boundTargetPackage.value,
                poolSnapshotProvable = pooled != null
            )
            AppDebugLogStore.i(
                ScreenCtxTag,
                "screen_context_gate trace=$traceId stage=ocr action=${decision.action} " +
                    "quality=${decision.quality} reason=${decision.reasonCode} " +
                    "ocrChars=${ocrText.length} ocrLines=$ocrLineCount optionMarkers=$optionMarkerCount " +
                    "bound=${boundTargetPackage.value ?: "-"} curWin=${read.packageName ?: "-"} visualInput=false"
            )
            when (decision.action) {
                RequestScreenContextAction.USE_POOL -> return pooled!!
                // 新鲜帧识别后仍然只有壳内容：如实报告识别失败，绝不把壳文字当成题目。
                RequestScreenContextAction.BLOCKED ->
                    return fail(decision.blockedStage ?: "content_insufficient")
                else -> {
                    val finalReport = report.copy(
                        contentQuality = decision.quality.name,
                        visualInput = false
                    )
                    screenContextManager.recordRead(finalReport)
                    // R8.7 P3：本次视口单独注入时的请求级证据（与 `pool_inject INJECT` 同构）。
                    AppDebugLogStore.i(
                        ScreenCtxTag,
                        "trace=$traceId request_context_evidence source=ocr " +
                            "viewportId=live-ocr-${System.currentTimeMillis()} " +
                            "capturedAtMillis=$now poolInjected=false hasSegmentBreak=false liveIncluded=true " +
                            "injectedSegmentIds=- contextChars=${ocrText.length} " +
                            "contextFp=${ocrFingerprintOf(ocrText)}"
                    )
                    return ScreenContextResult.Available(
                        ocrText,
                        traceId,
                        ScreenContextSource.LOCAL_OCR,
                        finalReport
                    )
                }
            }
        }
        return fail(
            classifyOcrFailureStage(
                managerActive = screenContextManager.active.value,
                screenshotAttempted = screenshotAttempted == true,
                screenshotSucceeded = screenshotSucceeded == true,
                ocrNotAvailable = ocrNotAvailable,
                ocrErrored = ocrError != null,
                staleFrame = staleFrameRejected
            )
        )
    }

    /**
     * 若累计同题快照可被证明属于当前题目，则返回**已组装**的可用结果；否则返回 null
     * （调用方回退到本次单次读取的结果）。
     *
     * 这是修复「同题滚动累积的文本无法进入最终请求」的关键接点：历史设计让单次屏幕上下文与
     * 累计池隔离（防旧题污染），代价是同题跨屏内容永远进不了请求。此处只做**注入**判定，
     * 不改动写池门控，要求「已绑定目标 + 池当前题同应用 + 当前前台仍是目标 + 当前视口可读」同时成立；
     * R8.6 起**不再**要求「池必须覆盖当前屏」—— 跨滚动长题下池与当前屏不连续是正常情形（见
     * [decidePoolInjection] 的说明）。
     *
     * R8.6 §4.5：返回的是**按可信度组装**的文本 —— 已确认同题的累计片段在前，本次新鲜视口（非阻断时）
     * 以 [LIVE_VIEWPORT_MARK] 明确分段在后（R8.6 曾沿用 `SEGMENT_BREAK_MARK`，无法区分「同题另一段」
     * 与「本次当前屏」，R8.7 已拆开）；同一片段不会重复注入；本次视口不可用时显式声明。
     *
     * 所有不满足的原因都以**脱敏**文本写入诊断日志（只有包名、计数、指纹，无题干正文），
     * 使真机能定位缺失断点究竟在采集 / 识别 / 写池 / 请求注入哪一环。
     */
    private fun pooledContextIfProvable(
        liveViewport: String,
        currentWindowPackage: String?,
        traceId: String,
        liveSource: ScreenContextSource,
        liveReport: ScreenReadReport
    ): ScreenContextResult? {
        val bound = boundTargetPackage.value
        val snapshot = realtimeViewportCollector.currentPool().currentQuestion()
        val collectorStatus = realtimeViewportCollector.status()
        // 当前**实时视口**解析出的题号（与池题号对照；仅用于「换题即拒绝注入旧题池」的隔离判定）。
        val viewportQuestionNumber = questionNumberOf(liveViewport)
        val decision = decidePoolInjection(
            boundPackage = bound,
            currentWindowPackage = currentWindowPackage,
            poolWindowKey = snapshot?.windowKey,
            poolText = snapshot?.text.orEmpty(),
            viewportText = liveViewport,
            poolLastResult = collectorStatus.lastResult,
            poolQuestionNumber = snapshot?.questionNumber,
            viewportQuestionNumber = viewportQuestionNumber
        )
        val now = System.currentTimeMillis()
        if (decision != PoolInjectionDecision.INJECT_POOL || snapshot == null) {
            AppDebugLogStore.i(
                ScreenCtxTag,
                "trace=$traceId pool_inject skipped=$decision bound=${bound ?: "-"} " +
                    "curWin=${currentWindowPackage ?: "-"} poolKey=${snapshot?.windowKey ?: "-"} " +
                    "poolAgeMs=${snapshot?.let { now - it.updatedAtMillis } ?: -1} " +
                    "poolChars=${snapshot?.text?.length ?: 0} poolFp=${fingerprintOrDash(snapshot?.text)} " +
                    "poolQuestion=${snapshot?.questionKey ?: "-"} completeness=${snapshot?.completeness?.name ?: "-"} " +
                    "poolLastResult=${collectorStatus.lastResult?.name ?: "-"} " +
                    "poolQNo=${snapshot?.questionNumber ?: -1} viewportQNo=${viewportQuestionNumber ?: -1} " +
                    "sessionEpoch=${collectorStatus.sequence}/${collectorStatus.acceptedSequence} " +
                    "liveSource=${liveSource.name} liveChars=${liveViewport.length} → 回退单次读取"
            )
            return null
        }
        val poolText = snapshot.text
        // R8.6 §4.5：按可信度组装「已确认同题的累计片段 + 本次新鲜视口」。
        // 仅在本次视口**非阻断**（不是只有页面外壳 / 键盘噪声 / 非目标应用）时才并入，
        // 避免把明显无用的外壳文字塞进请求；无论如何都不重复注入同一片段（见 [assembleAccumulatedWithLive]）。
        val liveAssessment = assessLiveScreenContent(liveViewport, currentWindowPackage, bound)
        // R8.7 P3：`viewportId` 与 `traceId` 是**两个不同的对象** ——
        // traceId 标识「这一次用户请求」，viewportId 标识「这一帧被采信的当前屏」。
        // 原先把 traceId 直接写进 viewportId 字段，导致「视口」维度无法跨日志关联。
        val viewportId = "live-${liveSource.name.lowercase()}-$now"
        val assembly = assembleAccumulatedWithLive(
            accumulatedText = poolText,
            liveText = liveViewport.takeIf { !liveAssessment.blocking },
            liveViewportId = viewportId,
            liveUnavailableReason = liveAssessment.reasonCode.takeIf { liveAssessment.blocking }
        )
        val finalText = assembly.text
        val report = liveReport.copy(
            source = ScreenContextSource.POOL_SNAPSHOT,
            charCount = finalText.length,
            injectedText = finalText
        )
        screenContextManager.recordRead(report)
        // R8.7 P2/P3：本次视口相对累计内容的**新增信息量**（供核查「是否重复累计 / 重复注入」）。
        val liveNovelty = if (liveViewport.isBlank()) null else measureViewportNovelty(
            ocrLines(poolText),
            ocrLines(liveViewport)
        )
        AppDebugLogStore.i(
            ScreenCtxTag,
            "trace=$traceId pool_inject INJECT bound=$bound curWin=$currentWindowPackage " +
                "identityConfirmed=true " +
                "poolQuestion=${snapshot.questionKey} poolChars=${poolText.length} " +
                "fragments=${snapshot.fragments.size} completeness=${snapshot.completeness.name} " +
                "poolAgeMs=${now - snapshot.updatedAtMillis} poolFp=${fingerprintOrDash(poolText)} " +
                "sessionEpoch=${collectorStatus.sequence}/${collectorStatus.acceptedSequence} " +
                "liveSource=${liveSource.name} " +
                // §7 结构化字段：片段从采集 → 合并 → 最终请求的完整链路可追踪（不含题目正文）。
                "questionSessionId=${snapshot.questionKey} viewportId=$viewportId " +
                "captureTimestamp=${snapshot.updatedAtMillis} requestCapturedAtMillis=$now " +
                "targetPackage=${bound ?: "-"} " +
                "scrollEvidence=${collectorStatus.lastScrollDecision} " +
                "mergeDecision=${collectorStatus.lastResult?.name ?: "-"} " +
                "overlapEvidence=commonRun=${ScreenChangeDetector.sharedContiguousChars(poolText, liveViewport)}" +
                " sharedLines=${overlapLineCount(poolText, liveViewport)} " +
                "poolSegmentCount=${snapshot.segmentCount} " +
                "requestIncludedSegmentIds=${snapshot.includedSegmentIds.joinToString(",")} " +
                "excludedSegmentIds=${snapshot.excludedSegmentIds.joinToString(",")}" +
                "(${snapshot.excludedSegmentIds.size} 个关联未知片段未注入) " +
                "liveChars=${liveViewport.length} liveIncluded=${assembly.liveIncluded} " +
                "liveVerified=${assembly.liveVerified} liveBlocking=${liveAssessment.blocking} " +
                "liveUnavailableReason=${liveAssessment.reasonCode.takeIf { liveAssessment.blocking } ?: "-"} " +
                "liveNovelLines=${liveNovelty?.newLines?.size ?: -1} liveNewChars=${liveNovelty?.newChars ?: -1} " +
                "liveEchoRatio=${liveNovelty?.let { percentOf(it.duplicateRatio) } ?: "-"} " +
                "hasLiveMarker=${assembly.hasLiveMarker} " +
                "hasSegmentBreak=${finalText.contains(ScreenContextPool.SEGMENT_BREAK_MARK)} " +
                "contextChars=${finalText.length} contextFp=${ocrFingerprintOf(finalText)} " +
                "assembleNote=${assembly.note}"
        )
        return ScreenContextResult.Available(
            text = finalText,
            traceId = traceId,
            source = ScreenContextSource.POOL_SNAPSHOT,
            readReport = report,
            // R8.7 P3.5：片段 ID 必须与**最终注入文本**对应 —— 这里直接取快照里「实际贡献了文字」的那些。
            includedSegmentIds = snapshot.includedSegmentIds,
            hasSegmentBreak = finalText.contains(ScreenContextPool.SEGMENT_BREAK_MARK) ||
                finalText.contains(LIVE_VIEWPORT_MARK),
            liveIncluded = assembly.liveIncluded,
            liveViewportId = viewportId,
            liveVerified = assembly.liveVerified
        )
    }

    /** 仅用于诊断日志的短指纹；不泄漏题目正文。 */
    private fun fingerprintOrDash(text: String?): String =
        text?.takeIf { it.isNotBlank() }?.let(::ocrFingerprintOf) ?: "-"

    /** 仅用于诊断日志：两个文本之间**完全相同**的行数（不泄漏正文）。 */
    private fun overlapLineCount(a: String, b: String): Int {
        if (a.isBlank() || b.isBlank()) return 0
        val aLines = ocrLines(a).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (aLines.isEmpty()) return 0
        return ocrLines(b).map { it.trim() }.count { it.isNotEmpty() && it in aLines }
    }

    /** 诊断日志用：按百分比显示重复率。 */
    private fun percentOf(ratio: Double): String = "${(ratio * 100).toInt()}%"

    /**
     * 阶段 2：把一次**无障碍观察**交给持续采集器（文本允许为空）。
     *
     * 边界（务必保持）：
     * - **不参与** [currentScreenContextForPrompt]，因此不改变任何一次发送请求所使用的屏幕文本；
     * - 主路径只用节点树文本，**不触发截图**；
     * - 仅当文本池判定「不确定」、或「文本没读到 / 文本未变化但确认目标应用发生了滚动」、
     *   且本地 OCR 可用、距上次 OCR 已超过频率间隔、当前无在途任务时，才按**单飞**方式补一次
     *   「单次截图 + 本地 OCR」；截图复用既有 [ScreenCaptureManager.captureLatestBitmap]，
     *   其内部已由 CaptureLifecycleLock 做互斥与悬浮窗隐藏/恢复，**不新建**绕过该门控的链路；
     * - 本方法**不发起任何大模型请求**。
     *
     * [windowKey] 使用目标第三方应用窗口的包名（由无障碍侧完成目标应用过滤后给出），
     * 不使用事件自身包名，避免把本应用悬浮窗/输入法/系统界面当成目标刷题文本。
     * [windowKey] 为 null 表示本次读取失败（没有有效的第三方目标窗口）。
     *
     * [text] 为 null / 空白表示「本次没有读到可用文本」—— 采集器会记为未读到文字（**不**更新池内容），
     * 但**仍然完整保留** [eventKind] 与 [scroll] 证据，使「用户确实滚动了、只是节点树读不到文本」
     * 仍能按 2 秒间隔 + 单飞补一次受控 OCR。这是修复「后台实时采集未运行」的关键路径：
     * 真机刷题 App 常用 Canvas / WebView 承载题目，节点树常读到空文本。
     *
     * [scroll] 为本次事件**同帧复制**的滚动位置元数据（非滚动事件为 null）；
     * [eventFromTargetWindow] 表示该滚动是否确实来自目标窗口（无障碍侧已完成
     * 「来源包名 == 目标窗口包名且非本应用」的过滤）。
     * 非滚动事件、滚动位置未变的重复分发、以及来自非目标窗口的滚动，保持原有行为（不触发 OCR）。
     */
    internal fun observeRealtimeAccessibilityObservation(
        text: String?,
        windowKey: String?,
        scroll: RealtimeScrollSignal? = null,
        eventKind: RealtimeEventKind = RealtimeEventKind.UNKNOWN,
        eventFromTargetWindow: Boolean = false
    ) {
        val outcome = realtimeViewportCollector.onAccessibilityObservation(
            text = text,
            windowKey = windowKey,
            scroll = scroll,
            eventKind = eventKind,
            eventFromTargetWindow = eventFromTargetWindow
        )
        // [collector_observation_submitted]：本次观察已提交给采集器。
        // 只记录非敏感指标（来源 / 事件类型 / 身份是否确认 / 文本状态与字符数 / 滚动判定 /
        // 结果 / 是否被接受 / 池内容是否真正改变 / 序号 / 未采纳原因），不写任何题干正文。
        // `obs` 用采集器的累计提交次数作为本次提交的关联标识。
        // 注意：`applied=true` 只代表「提交被接受」，**不代表**文本池内容已改变（要用 `changed`）。
        val submitted = realtimeViewportCollector.status()
        val observationId = submitted.updateCount
        AppDebugLogStore.i(
            ScreenCtxTag,
            "collector_observation_submitted obs=$observationId source=${outcome.source?.name ?: "-"} " +
                "kind=${eventKind.name} " +
                "identity=${windowKey != null} textStatus=${submitted.lastTextReadStatus?.name ?: "-"} " +
                "textChars=${text?.length ?: 0} scroll=${submitted.lastScrollDecision.name} " +
                "result=${outcome.result.name} applied=${outcome.applied} changed=${outcome.poolContentChanged} " +
                "seq=${submitted.sequence}/${submitted.acceptedSequence} note=${outcome.note ?: "-"}"
        )
        // [pool_question_established]：本次提交**成功建立**了新题目上下文时，单独记一条**脱敏**记录，
        // 使真机可确认「内容池最初由哪一次观察、以什么来源 / 身份 / 规模建立」。
        // 只在结果确为 NEW_QUESTION 时触发，不改变任何建立条件或状态迁移；字段全为计数 / 布尔。
        if (outcome.result == PoolUpdateResult.NEW_QUESTION) {
            AppDebugLogStore.i(
                ScreenCtxTag,
                "pool_question_established source=${outcome.source?.name ?: "-"} " +
                    "seq=${submitted.sequence}/${submitted.acceptedSequence} " +
                    "windowKey=${windowKey != null} identity=${windowKey ?: "-"} " +
                    "hasCoordinates=false textChars=${text?.length ?: 0} " +
                    "nonWhitespaceChars=${text?.count { !it.isWhitespace() } ?: 0} " +
                    "lines=${text?.let { ocrLines(it).size } ?: 0} result=${outcome.result.name}"
            )
        }
        // [ocr_gate_decision]：不触发 OCR 时必须显式记录「未执行及原因」，
        // 不得仅凭源码中存在该函数就宣称后台 OCR 已运行。
        if (!realtimeViewportCollector.shouldTriggerOcr(outcome.result)) {
            AppDebugLogStore.i(
                ScreenCtxTag,
                "ocr_gate_decision obs=$observationId skipped=" +
                    (realtimeViewportCollector.status().lastOcrGateNote
                        ?: "本次观察无需补 OCR（非滚动事件 / 重复滚动 / 文本池已由文本更新）")
            )
            return
        }
        val sequence = realtimeViewportCollector.beginOcrAttempt()
        if (sequence == null) {
            AppDebugLogStore.i(
                ScreenCtxTag,
                "ocr_gate_decision obs=$observationId skipped=" +
                    (realtimeViewportCollector.status().lastOcrGateNote ?: "未取得 OCR 执行序号")
            )
            return
        }
        AppDebugLogStore.i(ScreenCtxTag, "ocr_gate_decision obs=$observationId admitted seq=$sequence")
        appScope.launch {
            val settings = appSettings.value
            // 记录当前执行到哪一步：异常时据此判定「卡在哪一阶段」，不用猜测。
            var stage = "capture"
            try {
                if (!screenContextManager.active.value ||
                    !ScreenCaptureManager.hasActiveSession(settings.screenCaptureMethod)
                ) {
                    // 截图会话不可用时不强行截图：记录**明确的阻断原因**，保持既有文本池不变。
                    realtimeViewportCollector.failOcrAttempt(
                        sequence = sequence,
                        reason = "截图会话不可用（active=${screenContextManager.active.value}，" +
                            "hasSession=${ScreenCaptureManager.hasActiveSession(settings.screenCaptureMethod)}，未改动文本池）"
                    )
                    AppDebugLogStore.i(
                        ScreenCtxTag,
                        "capture_started seq=$sequence skipped=capture_session_unavailable " +
                            "active=${screenContextManager.active.value} " +
                            "hasSession=${ScreenCaptureManager.hasActiveSession(settings.screenCaptureMethod)} " +
                            "未改动文本池"
                    )
                    return@launch
                }
                AppDebugLogStore.i(ScreenCtxTag, "capture_started seq=$sequence")
                val captureStartedAt = System.currentTimeMillis()
                val bitmap = ScreenCaptureManager.captureLatestBitmap(appContext, settings.screenCaptureMethod)
                AppDebugLogStore.i(
                    ScreenCtxTag,
                    "capture_finished seq=$sequence ms=${System.currentTimeMillis() - captureStartedAt} " +
                        "w=${bitmap.width} h=${bitmap.height}"
                )
                stage = "ocr"
                AppDebugLogStore.i(ScreenCtxTag, "ocr_started seq=$sequence")
                val ocrStartedAt = System.currentTimeMillis()
                val ocrResult = localOcrManager.recognize(bitmap)
                AppDebugLogStore.i(
                    ScreenCtxTag,
                    "ocr_finished seq=$sequence ms=${System.currentTimeMillis() - ocrStartedAt} " +
                        "chars=${ocrResult.rawText.length}"
                )
                stage = "pool"
                AppDebugLogStore.i(ScreenCtxTag, "pool_observe_started seq=$sequence identity=${windowKey != null}")
                val organization = organizeOcrText(ocrResult)
                val poolOutcome = realtimeViewportCollector.completeOcrAttempt(
                    sequence = sequence,
                    organization = organization,
                    rawOcrText = ocrResult.rawText,
                    windowKey = windowKey
                )
                // 只记录非敏感指标（来源 / 结果 / 是否被接受 / 池内容是否真正改变 / 序号 /
                // 行数 / 选项标记 / 池片段数与字符数 / 未采纳原因），不写完整识别原文。
                // 三态必须分开看：`applied`＝提交是否被接受；`changed`＝池内容是否真正改变；
                // `note` 说明未采纳/未生效的具体原因（如「过期丢弃」「乱序」）。
                val afterPool = realtimeViewportCollector.status()
                AppDebugLogStore.i(
                    ScreenCtxTag,
                    "pool_observe_finished seq=$sequence result=${poolOutcome.result.name} " +
                        "applied=${poolOutcome.applied} changed=${poolOutcome.poolContentChanged} " +
                        "source=${poolOutcome.source?.name ?: "-"} lines=${organization.rowCount} " +
                        "options=${organization.optionMarkerCount} association=${organization.association} " +
                        "fragments=${afterPool.fragmentCount} poolChars=${afterPool.poolText.length} " +
                        "acceptedSeq=${afterPool.acceptedSequence} note=${poolOutcome.note ?: "-"}"
                )
                // [pool_question_established]：与无障碍路径同一条脱敏记录（OCR 建立新题的入口）。
                // 只在结果确为 NEW_QUESTION 时触发，不改变任何建立条件或状态迁移。
                if (poolOutcome.result == PoolUpdateResult.NEW_QUESTION) {
                    AppDebugLogStore.i(
                        ScreenCtxTag,
                        "pool_question_established source=${poolOutcome.source?.name ?: "-"} seq=$sequence " +
                            "windowKey=${windowKey != null} identity=${windowKey ?: "-"} " +
                            "hasCoordinates=true textChars=${organization.text.length} " +
                            "nonWhitespaceChars=${organization.text.count { !it.isWhitespace() }} " +
                            "lines=${organization.rowCount} result=${poolOutcome.result.name}"
                    )
                }
            } catch (t: Throwable) {
                AppDebugLogStore.i(
                    ScreenCtxTag,
                    "realtime_failed seq=$sequence stage=$stage ${(t.cause ?: t).javaClass.simpleName}"
                )
                realtimeViewportCollector.failOcrAttempt(sequence)
            }
        }
    }

    // ==================== 阶段 2 修复 B：受控「画面变化探测回退」 ====================

    /**
     * 启动受控画面探测任务（仅实时模式开启期间存在）。
     *
     * 任务本身只做**限频轮询判定**，是否真的截图完全由 [runRealtimeScreenProbeOnce] 内的
     * 运行期条件与 [RealtimeViewportCollector.shouldProbeScreen] 决定。
     */
    private fun startRealtimeScreenProbe() {
        if (realtimeProbeJob?.isActive == true) return
        realtimeProbeJob = appScope.launch {
            while (isActive) {
                delay(realtimeViewportCollector.status().probeIntervalMillis)
                runRealtimeScreenProbeOnce()
            }
        }
    }

    /** 停止画面探测任务（实时模式关闭 / 目标应用离开前台 / 服务停止时调用）。 */
    private fun stopRealtimeScreenProbe() {
        realtimeProbeJob?.cancel()
        realtimeProbeJob = null
    }

    /**
     * 一次受控画面探测：复用既有截图链路取样，只有画面**发生实质变化**时才进入 OCR。
     *
     * 运行期门控（任一不满足即直接返回，不截图）：
     * 1. 实时模式开启（采集器已启用）；
     * 2. 屏幕上下文服务处于活动状态；
     * 3. 当前截图方式有可用会话（[ScreenCaptureManager.hasActiveSession]）；
     * 4. 无障碍服务正在运行，且**目标刷题应用确认位于前台**；
     * 5. 采集器的 [RealtimeViewportCollector.shouldProbeScreen]（限频 + 最近无目标滚动事件）。
     *
     * 截图**复用** [ScreenCaptureManager.captureLatestBitmap]，其内部已由 CaptureLifecycleLock 做
     * 互斥与悬浮窗隐藏/恢复，不新建任何绕过门控的截图通道；同一张图既用于画面采样比较，
     * 也直接交给本地 OCR，避免一次探测产生两次截图。
     */
    private suspend fun runRealtimeScreenProbeOnce() {
        // 每道门控都记录**非敏感**的跳过原因：真机验收时能直接看出「探测任务在跑但被哪一条挡住」，
        // 从而区分「后台任务没运行」与「运行了但条件不满足」。日志不含任何题干正文。
        //
        // 门控判定集中在纯函数 [probeSkipReason] 中（可单测）：任一条件不满足都返回可读原因。
        // 「目标应用前台身份确认失败」与「目标应用位于前台但无障碍文本为空」是两种不同状态：
        // 前者保留安全门控并明确记录「无法确认」，后者不影响探测。
        val settings = appSettings.value
        val realtimeEnabled = realtimeViewportCollector.isEnabled()
        val managerActive = screenContextManager.active.value
        val captureSessionAvailable = ScreenCaptureManager.hasActiveSession(settings.screenCaptureMethod)
        val a11yConnected = HanakoAccessibilityService.isRunning()
        // 只有在前四条都满足时才值得去确认目标应用前台身份（避免无谓的窗口枚举）。
        // R8.4：这里不再只取「是否前台」的布尔值，而是解析出**目标前台包名**，使本次受控 OCR 结果
        // 能携带「属于哪个已绑定应用」的身份证据 —— 真机日志此前恒为 identity=false
        // （`windowKey = null`），导致这次新鲜 OCR 既不能作为本次请求的临时上下文，
        // 也无法用它重建累计池（只能被 UNSTABLE 丢弃）。
        val targetForegroundPackage = if (realtimeEnabled && managerActive && captureSessionAvailable && a11yConnected) {
            // 无障碍窗口枚举必须在主线程执行。
            withContext(Dispatchers.Main) {
                HanakoAccessibilityService.liveForegroundPackage()
            }
        } else {
            null
        }
        // 与既有 isTargetWindowForeground() 完全相同的口径：身份已确认且包名等于已绑定目标。
        val targetForegroundConfirmed =
            targetForegroundPackage != null && targetForegroundPackage == boundTargetPackage.value
        val probeAllowed = realtimeViewportCollector.shouldProbeScreen()
        val skipReason = probeSkipReason(
            realtimeEnabled = realtimeEnabled,
            managerActive = managerActive,
            captureSessionAvailable = captureSessionAvailable,
            a11yConnected = a11yConnected,
            targetForegroundConfirmed = targetForegroundConfirmed,
            probeAllowedByCollector = probeAllowed
        )
        if (skipReason != null) {
            AppDebugLogStore.i(
                ScreenCtxTag,
                "probe_gate_decision skipped=$skipReason（method=${settings.screenCaptureMethod}，" +
                    "everEstablished=${ScreenCaptureManager.hasEverEstablishedSession(settings.screenCaptureMethod)}，" +
                    "probeAllowed=$probeAllowed）"
            )
            return
        }
        AppDebugLogStore.i(
            ScreenCtxTag,
            "probe_gate_decision admitted targetForegroundConfirmed=$targetForegroundConfirmed " +
                "foregroundPkg=${targetForegroundPackage ?: "-"} bound=${boundTargetPackage.value ?: "-"}"
        )
        var attempt: Long? = null
        // 记录当前执行到哪一步：异常时据此判定「卡在哪一阶段」，不用猜测。
        var stage = "capture"
        try {
            AppDebugLogStore.i(ScreenCtxTag, "capture_started probe")
            val captureStartedAt = System.currentTimeMillis()
            val bitmap = ScreenCaptureManager.captureLatestBitmap(appContext, settings.screenCaptureMethod)
            AppDebugLogStore.i(
                ScreenCtxTag,
                "capture_finished probe ms=${System.currentTimeMillis() - captureStartedAt} " +
                    "w=${bitmap.width} h=${bitmap.height}"
            )
            val changed = realtimeViewportCollector.onScreenProbe(screenSampleOf(bitmap))
            // R8.2：画面未发生变化（UNCHANGED，`changed=false`）**不得**直接断定无需 OCR ——
            // 画面可能确实没变，但此前提取的无障碍文本仍明显不完整（一行选项标记都读不到，
            // 真机粉笔资料分析页只读到 28 字外壳）。此时允许按既有限频 + 单飞**再补一次**受控 OCR，
            // 且复用**当前这张新鲜截图**（不是过期帧）。
            // 首次采样（FIRST_SAMPLE）只建立基线，保持原样不补采；安全门控不变：
            // 本分支只有在 probe_gate_decision admitted（目标应用前台身份已确认）之后才会到达。
            val probeDecision = realtimeViewportCollector.status().lastProbeDecision
            val incompleteSupplement = probeDecision == ScreenProbeDecision.UNCHANGED &&
                realtimeViewportCollector.shouldSupplementOcrForIncompletePool()
            AppDebugLogStore.i(
                ScreenCtxTag,
                "probe_sample_decision " +
                    "${probeDecision?.name ?: "-"} changed=$changed incompleteSupplement=$incompleteSupplement"
            )
            if (!changed && !incompleteSupplement) return
            val sequence = realtimeViewportCollector.beginOcrAttempt()
            if (sequence == null) {
                AppDebugLogStore.i(
                    ScreenCtxTag,
                    "ocr_gate_decision probe skipped=" +
                        (realtimeViewportCollector.status().lastOcrGateNote ?: "未取得 OCR 执行序号")
                )
                return
            }
            attempt = sequence
            AppDebugLogStore.i(ScreenCtxTag, "ocr_gate_decision probe admitted seq=$sequence")
            stage = "ocr"
            AppDebugLogStore.i(ScreenCtxTag, "ocr_started seq=$sequence")
            val ocrStartedAt = System.currentTimeMillis()
            val ocrResult = localOcrManager.recognize(bitmap)
            AppDebugLogStore.i(
                ScreenCtxTag,
                "ocr_finished seq=$sequence ms=${System.currentTimeMillis() - ocrStartedAt} " +
                    "chars=${ocrResult.rawText.length}"
            )
            stage = "pool"
            AppDebugLogStore.i(
                ScreenCtxTag,
                "pool_observe_started seq=$sequence identity=${targetForegroundPackage != null} " +
                    "windowKey=${targetForegroundPackage ?: "-"}"
            )
            val organization = organizeOcrText(ocrResult)
            val poolOutcome = realtimeViewportCollector.completeOcrAttempt(
                sequence = sequence,
                organization = organization,
                rawOcrText = ocrResult.rawText,
                // R8.4：带上**目标前台包名**作为本次 OCR 帧的身份证据；身份未确认时保持 null，
                // 采集器据此不把未经确认的内容写入共享累计池。
                windowKey = targetForegroundPackage
            )
            val afterPool = realtimeViewportCollector.status()
            AppDebugLogStore.i(
                ScreenCtxTag,
                "pool_observe_finished seq=$sequence result=${poolOutcome.result.name} " +
                    "applied=${poolOutcome.applied} lines=${organization.rowCount} " +
                    "options=${organization.optionMarkerCount} association=${organization.association} " +
                    "fragments=${afterPool.fragmentCount} poolChars=${afterPool.poolText.length}"
            )
        } catch (t: Throwable) {
            AppDebugLogStore.i(
                ScreenCtxTag,
                "realtime_failed probe stage=$stage ${(t.cause ?: t).javaClass.simpleName}"
            )
            attempt?.let { realtimeViewportCollector.failOcrAttempt(it, reason = "受控画面探测执行失败（未改动文本池）") }
        }
    }

    /**
     * 把一次截图降采样为亮度网格样本（纯数值），交由纯 JVM 采集器做轻量变化判定。
     *
     * 取的是**亮度**而非彩色，并把画面压到 [SCREEN_SAMPLE_GRID] × [SCREEN_SAMPLE_GRID] 个格子：
     * 这样对「滚动导致的整屏内容位移」敏感，对时钟数字、过渡动画、悬浮窗状态这类极小面积变化不敏感。
     * 不回收调用方传入的 bitmap（OCR 还要继续使用它）。
     */
    private fun screenSampleOf(bitmap: Bitmap): ScreenSample {
        val size = SCREEN_SAMPLE_GRID
        val scaled = if (bitmap.width == size && bitmap.height == size) bitmap
        else Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()
        return ScreenSample(
            cells = pixels.map { pixel ->
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                (red * 299 + green * 587 + blue * 114) / 1000
            }
        )
    }

    private companion object {
        /** 聊天默认系统提示词（无产品化要求时的中性缺省值）。 */
        const val CHAT_SYSTEM_PROMPT = "你是 Hanako 内置助手，请用简洁、准确的中文回答用户的问题。"

        /** 屏幕上下文调试日志标签。 */
        const val ScreenCtxTag = "ScreenCtx"

        /** 聊天发送链路调试日志标签（含 traceId，非敏感）。 */
        const val ChatTraceTag = "ChatTrace"

        /** 画面变化探测的降采样网格边长（32 × 32 个亮度格子）。 */
        const val SCREEN_SAMPLE_GRID = 32
    }
}