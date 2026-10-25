package `fun`.kirari.hanako.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.kirari.hanako.AppContainer
import `fun`.kirari.hanako.automation.BubbleEvent
import `fun`.kirari.hanako.automation.BubbleState
import `fun`.kirari.hanako.automation.BubbleStateMachine
import `fun`.kirari.hanako.capture.LongScreenshotCapturer
import `fun`.kirari.hanako.capture.ScreenCaptureStartResult
import `fun`.kirari.hanako.capture.recycleSafely
import `fun`.kirari.hanako.chat.ChatModelCatalog
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ProcessingEvent
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.ModelSelection
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.data.ThemeColorStyle
import `fun`.kirari.hanako.data.ThemeMode
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.localocr.LocalOcrManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 长截图开始前，等待悬浮面板收起动画结束的时间（毫秒）。 */
private const val PANEL_HIDE_SETTLE_MILLIS = 300L

/** 手动长截图最多拼接的屏数（每屏约 10MB，屏数越多越容易 OOM）。 */
private const val MAX_LONG_PAGES = 8

internal class OverlayViewModel(
    private val appContext: Context,
    private val repository: SettingsRepository,
    private val pipeline: ProcessingPipeline,
    /** 与主 App「聊天」页共享的全局单例会话（P3 迷你聊天复用同一实例）。 */
    val chatSessionHolder: ChatSessionHolder,
    /** 聊天模型全量列表内存缓存（与主聊天页共用同一实例，保证迷你窗模型选择一致）。 */
    val chatModelCatalog: ChatModelCatalog
) : ViewModel() {
    private val tag = "HanakoOverlayVM"
    private val processingTimeoutMillis = 90_000L
    private var activeAutoProcessingJob: Job? = null

    /** 长截图任务：同一时刻只允许一个（滚动期间重复点击会叠加滚动）。 */
    private var longCaptureJob: Job? = null
    
    // 新的状态机
    val bubbleStateMachine = BubbleStateMachine()
    
    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    /** `settings.providers` 快照，供迷你聊天模型选择弹窗使用（与主聊天页同一数据源）。 */
    val providers: StateFlow<List<ModelProviderConfig>> = _uiState
        .map { it.settings.providers }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 迷你聊天窗透明度（设置页「迷你聊天窗」实时更新；迷你窗渲染据此应用）。 */
    val miniChatAlpha: StateFlow<Float> = _uiState
        .map { it.settings.miniChat.backgroundAlpha }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.92f)

    /** 界面亮暗模式（设置页「主题」；迷你窗渲染据此应用）。 */
    val themeMode: StateFlow<ThemeMode> = _uiState
        .map { it.settings.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** 界面配色风格（设置页「主题」；迷你窗渲染据此应用）。 */
    val colorStyle: StateFlow<ThemeColorStyle> = _uiState
        .map { it.settings.colorStyle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeColorStyle.DEFAULT)

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        
        // 监听状态机变化，同步到 UI 状态
        viewModelScope.launch {
            bubbleStateMachine.state.collect { bubbleState ->
                _uiState.update { it.copy(bubbleState = bubbleState) }
            }
        }
    }

    fun setLaunchMode(mode: OverlayLaunchMode) {
        AppDebugLogStore.i(tag, "setLaunchMode mode=$mode")
        _uiState.update { state ->
            state.copy(
                launchMode = mode,
                autoRunState = if (mode == OverlayLaunchMode.NORMAL) AutoRunState.IDLE else state.autoRunState,
                autoCopiedLabel = if (mode == OverlayLaunchMode.NORMAL) null else state.autoCopiedLabel,
                error = null
            )
        }
        // 普通模式重置状态机
        if (mode == OverlayLaunchMode.NORMAL) {
            bubbleStateMachine.forceState(BubbleState.Idle)
        }
    }

    fun openCropSheet() {
        AppDebugLogStore.i(tag, "openCropSheet launchMode=${_uiState.value.launchMode}")
        // 长截图会话中：再次点击悬浮球不是「重新截图」，而是把新一屏拼到已有长图下方。
        if (_uiState.value.longCaptureActive) {
            AppDebugLogStore.i(tag, "openCropSheet -> appendLongCapturePage")
            appendLongCapturePage()
            return
        }
        if (_uiState.value.launchMode == OverlayLaunchMode.AUTO) {
            AppDebugLogStore.i(tag, "openCropSheet delegated to processFullScreen for auto mode")
            processFullScreen()
            return
        }
        // 实时互动模式：单击悬浮球直接自动截图并分析，不再弹出裁剪页让用户手动确认。
        // 无障碍提取路线仍走只读文本（不截图），其余路线（OCR / 多模态）走普通分析链路，
        // 保留实时屏幕上下文注入的同时省掉手动裁剪这一步。
        val realtimeState = _uiState.value
        if (realtimeState.settings.realTimeMode &&
            realtimeState.settings.processingRoute != ProcessingRoute.ACCESSIBILITY_TEXT
        ) {
            AppDebugLogStore.i(
                tag,
                "openCropSheet delegated to processRealtimeAutoCapture (realtime mode route=${realtimeState.settings.processingRoute})"
            )
            processRealtimeAutoCapture()
            return
        }
        // 无障碍模式：跳过截屏，直接提取文本并处理
        if (_uiState.value.settings.processingRoute == ProcessingRoute.ACCESSIBILITY_TEXT) {
            processAccessibilityDirect()
            return
        }
        // 截图会话丢失（常见于进程被系统回收后重启）时先重新授权，
        // 否则只会得到「屏幕捕捉会话未初始化」这种无从下手的错误。
        if (!ensureCaptureSession()) return
        viewModelScope.launch {
            runCatching {
                // occludingCaptureRequired=true：采集期间真正隐藏 Hanako 浮窗（悬浮球等）再取新帧，
                // 避免默认的位图遮罩把悬浮球位置涂成一块黑方块留在截图/预览里。
                `fun`.kirari.hanako.capture.ScreenCaptureManager.captureLatestBitmap(
                    context = appContext,
                    method = _uiState.value.settings.screenCaptureMethod,
                    occludingCaptureRequired = true
                )
            }.onSuccess { bitmap ->
                AppDebugLogStore.i(tag, "openCropSheet capture success width=${bitmap.width} height=${bitmap.height}")
                _uiState.update {
                    it.copy(
                        screenshot = bitmap,
                        selectedBitmap = null,
                        liveOcrText = "",
                        liveAnswerText = "",
                        result = null,
                        error = null,
                        working = false,
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.CROP,
                        miniChatVisible = false,
                        longCaptureActive = false,
                        longCapturePages = 0,
                        longPagePending = false,
                        longScreenshotBitmap = null,
                        longPageSegments = emptyList(),
                        longCaptureCropRect = null,
                        autoRunState = AutoRunState.IDLE,
                        autoCopiedLabel = null
                    )
                }
                bubbleStateMachine.forceState(BubbleState.Idle)
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "openCropSheet failed", error)
                _uiState.update {
                    it.copy(
                        error = error.message ?: "截屏失败",
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.CROP,
                        miniChatVisible = false
                    )
                }
            }
        }
    }

    /**
     * 检查截图会话是否可用；不可用时自动拉起授权页并提示用户。
     *
     * MediaProjection 的授权不能跨进程复用：应用被系统回收后重启，就必须重新授权，
     * 否则所有截图都会报「屏幕捕捉会话未初始化」。
     */
    private fun ensureCaptureSession(): Boolean {
        val method = _uiState.value.settings.screenCaptureMethod
        if (`fun`.kirari.hanako.capture.ScreenCaptureManager.hasActiveSession(method)) return true
        val result = `fun`.kirari.hanako.capture.ScreenCaptureManager.requestStart(
            appContext,
            method,
            OverlayLaunchMode.NORMAL
        )
        val message = when (result) {
            ScreenCaptureStartResult.Started ->
                "截图授权已失效：请在系统弹窗中点「立即开始」，然后重新点击悬浮球"
            is ScreenCaptureStartResult.UserActionRequired -> result.message
            is ScreenCaptureStartResult.Failed -> result.message
        }
        AppDebugLogStore.e(tag, "ensureCaptureSession unavailable: $message")
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        _uiState.update { it.copy(error = message, sheetVisible = false, miniChatVisible = false) }
        return false
    }

    /**
     * 手动长截图的第 1 屏：把当前截图作为已拼接内容，收起面板让用户回到目标页面。
     */
    fun startLongCaptureSession(cropRect: Rect?) {
        val first = _uiState.value.screenshot
        if (first == null) {
            Toast.makeText(appContext, "请先截图", Toast.LENGTH_SHORT).show()
            return
        }
        AppDebugLogStore.i(tag, "startLongCaptureSession size=${first.width}x${first.height} crop=$cropRect")
        // 第 1 屏按用户框选区域裁剪后放进「原图」区域；当前截图区仍显示整屏。
        val firstPage = cropToRegion(first, cropRect)
        _uiState.update {
            it.copy(
                longCaptureActive = true,
                longCapturePages = 1,
                longPagePending = false,
                longScreenshotBitmap = firstPage,
                longPageSegments = listOf(LongPageSegment(0, firstPage.height)),
                longCaptureCropRect = cropRect,
                sheetVisible = false,
                selectedBitmap = null,
                error = null,
                working = false
            )
        }
        Toast.makeText(
            appContext,
            "已记录第 1 屏：可继续点悬浮球截取下一屏（滚动到新内容或同一页再截都可以）",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 「继续截图」：先把这一屏按本次框选拼接，再收起面板回到目标页面，等用户再点悬浮球截下一屏。
     */
    fun continueLongCapture(cropRect: Rect?) {
        if (commitPendingLongPage(cropRect) == null) return
        _uiState.update { it.copy(sheetVisible = false, error = null) }
        Toast.makeText(appContext, "第 ${_uiState.value.longCapturePages} 屏已拼接，可继续点悬浮球截取下一屏", Toast.LENGTH_LONG).show()
    }

    /**
     * 长截图的「确认处理」：先把这一屏按本次框选拼接，再拿累计长图去分析。
     */
    fun confirmLongCapture(cropRect: Rect?) {
        val bitmap = commitPendingLongPage(cropRect) ?: return
        process(bitmap)
    }

    /**
     * 手动长截图：截当前屏并拼到已有长图下方（自动裁掉重复的顶部）。
     *
     * 采集用 `occludingCaptureRequired = true`，避免把悬浮球/面板拼进长图；
     * 面板先收起并等待一段延时，确保采集时窗口已经真正隐藏。
     */
    private fun appendLongCapturePage() {
        if (longCaptureJob?.isActive == true) return
        longCaptureJob = viewModelScope.launch {
            val settings = _uiState.value.settings
            _uiState.update { it.copy(sheetVisible = false, error = null, working = true) }
            delay(PANEL_HIDE_SETTLE_MILLIS)
            if (!ensureCaptureSession()) {
                _uiState.update { it.copy(working = false) }
                return@launch
            }
            runCatching {
                val state = _uiState.value
                if (state.longCapturePages >= MAX_LONG_PAGES) {
                    error("已拼到 $MAX_LONG_PAGES 屏上限，请点「确认处理」结束")
                }
                val base = state.longScreenshotBitmap ?: error("长截图会话已结束")
                if (base.height >= LongScreenshotCapturer.MAX_STITCH_HEIGHT) {
                    error("长图已达高度上限，请点「确认处理」结束")
                }
                `fun`.kirari.hanako.capture.ScreenCaptureManager.captureLatestBitmap(
                    context = appContext,
                    method = settings.screenCaptureMethod,
                    occludingCaptureRequired = true
                )
            }.onSuccess { next ->
                // 只截图、先不拼接：把这一屏交给截图页，等用户框选区域后点「继续截图」/
                // 「确认处理」时再按**这一次**的框选裁剪并拼接，避免所有屏都沿用第 1 屏的区域。
                AppDebugLogStore.i(tag, "longPageCaptured size=${next.width}x${next.height} pending=true")
                _uiState.update {
                    it.copy(
                        screenshot = next,
                        longPagePending = true,
                        selectedBitmap = null,
                        working = false,
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.CROP,
                        error = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                AppDebugLogStore.e(tag, "appendLongCapturePage failed", error)
                val message = error.message ?: "长截图失败"
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                _uiState.update {
                    it.copy(
                        working = false,
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.CROP,
                        error = message
                    )
                }
            }
        }
    }

    /**
     * 把待拼接的当前屏按 [cropRect]（用户本次框选，null 表示沿用上一次区域/整屏）
     * 裁剪后拼到「原图」下方，返回累计长图；失败或无待拼接页时返回 null/现有长图。
     */
    private fun commitPendingLongPage(cropRect: Rect?): Bitmap? {
        val state = _uiState.value
        val next = state.screenshot
        if (!state.longPagePending || next == null) return state.longScreenshotBitmap
        val base = state.longScreenshotBitmap ?: return null
        val rect = cropRect ?: state.longCaptureCropRect
        val page = cropToRegion(next, rect)
        val result = try {
            val stitched = LongScreenshotCapturer.appendScreen(base, page)
            if (stitched.addedRows <= 0) {
                // 与上一屏完全相同时按整屏追加（不限制用户必须滚动）。
                stitched.bitmap.recycleSafely()
                LongScreenshotCapturer.AppendResult(
                    LongScreenshotCapturer.appendBelow(base, page, 0),
                    overlapRows = 0,
                    addedRows = page.height
                )
            } else {
                stitched
            }
        } catch (t: Throwable) {
            AppDebugLogStore.e(tag, "commitPendingLongPage failed", t)
            Toast.makeText(appContext, t.message ?: "长截图拼接失败", Toast.LENGTH_LONG).show()
            return null
        } finally {
            if (page !== next) page.recycleSafely()
        }
        val pages = state.longCapturePages + 1
        // 这一屏在累计长图中的区段：长图 = 旧长图 + 本屏去掉重叠的部分，
        // 因此本屏内容在长图中从 (旧高度 - 重叠行数) 开始，高度等于本屏裁剪后的高度。
        val segment = LongPageSegment(
            offsetY = (base.height - result.overlapRows).coerceAtLeast(0),
            height = page.height
        )
        _uiState.update {
            it.copy(
                longScreenshotBitmap = result.bitmap,
                longPageSegments = it.longPageSegments + segment,
                longCapturePages = pages,
                longCaptureCropRect = rect,
                longPagePending = false,
                error = null
            )
        }
        AppDebugLogStore.i(
            tag,
            "longPageCommitted pages=$pages overlap=${result.overlapRows} added=${result.addedRows} " +
                "rect=$rect size=${result.bitmap.width}x${result.bitmap.height}"
        )
        return result.bitmap
    }

    /** 按长截图会话记录的裁剪区域裁出这一屏；区域无效（如屏幕旋转后超界）时退回整屏。 */
    private fun cropToRegion(source: Bitmap, rect: Rect?): Bitmap {
        if (rect == null) return source
        if (rect.left < 0 || rect.top < 0 ||
            rect.right > source.width || rect.bottom > source.height
        ) {
            return source
        }
        if (rect.width() <= 1 || rect.height() <= 1) return source
        return runCatching {
            Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        }.getOrDefault(source)
    }

    /**
     * 实时互动模式的「自动截图 + 自动分析」入口（单击悬浮球触发）。
     *
     * 与裁剪页流程的唯一区别：截图后不进入裁剪页等用户手动确认，直接按当前处理路线
     * 走普通分析链路（结果页功能完整），同时保留实时互动的屏幕上下文注入。
     */
    private fun processRealtimeAutoCapture() {
        if (_uiState.value.working) {
            AppDebugLogStore.i(tag, "processRealtimeAutoCapture ignored, already working")
            return
        }
        if (!ensureCaptureSession()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    liveOcrText = "",
                    liveAnswerText = "",
                    result = null,
                    error = null,
                    working = true,
                    sheetVisible = true,
                    sheetMode = OverlaySheetMode.RESULT,
                    miniChatVisible = false,
                    autoRunState = AutoRunState.IDLE,
                    autoCopiedLabel = null,
                    longCaptureActive = false,
                    longCapturePages = 0,
                    longPagePending = false,
                    longScreenshotBitmap = null,
                    longPageSegments = emptyList(),
                    longCaptureCropRect = null
                )
            }
            bubbleStateMachine.dispatch(BubbleEvent.StartProcessing)

            val bitmap = runCatching {
                withContext(Dispatchers.IO) {
                    `fun`.kirari.hanako.capture.ScreenCaptureManager.captureLatestBitmap(
                        context = appContext,
                        method = _uiState.value.settings.screenCaptureMethod,
                        occludingCaptureRequired = true
                    )
                }
            }.getOrElse { error ->
                if (error is CancellationException) return@launch
                AppDebugLogStore.e(tag, "processRealtimeAutoCapture capture failed", error)
                _uiState.update {
                    it.copy(working = false, error = error.message ?: "截屏失败")
                }
                bubbleStateMachine.forceState(BubbleState.Idle)
                return@launch
            }
            AppDebugLogStore.i(
                tag,
                "processRealtimeAutoCapture capture success width=${bitmap.width} height=${bitmap.height}"
            )
            _uiState.update {
                it.copy(screenshot = bitmap, selectedBitmap = bitmap)
            }
            process(bitmap)
        }
    }

    fun processFullScreen() {
        AppDebugLogStore.i(tag, "processFullScreen start launchMode=${_uiState.value.launchMode}")
        // 无障碍模式：直接提取文本处理，不需要截屏
        if (_uiState.value.settings.processingRoute == ProcessingRoute.ACCESSIBILITY_TEXT) {
            activeAutoProcessingJob?.cancel()
            val job = viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        liveOcrText = "",
                        liveAnswerText = "",
                        result = null,
                        error = null,
                        working = true,
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.RESULT,
                        autoRunState = AutoRunState.RUNNING,
                        autoCopiedLabel = null
                    )
                }
                bubbleStateMachine.dispatch(BubbleEvent.StartProcessing)
                processAccessibilityAutoBitmap()
            }
            activeAutoProcessingJob = job
            job.invokeOnCompletion {
                if (activeAutoProcessingJob === job) activeAutoProcessingJob = null
            }
            return
        }
        activeAutoProcessingJob?.cancel()
        val job = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    liveOcrText = "",
                    liveAnswerText = "",
                    result = null,
                    error = null,
                    working = true,
                    sheetVisible = true,
                    sheetMode = OverlaySheetMode.RESULT,
                    autoRunState = AutoRunState.RUNNING,
                    autoCopiedLabel = null
                )
            }
            bubbleStateMachine.dispatch(BubbleEvent.StartProcessing)

            runCatching {
                withContext(Dispatchers.IO) {
                    `fun`.kirari.hanako.capture.ScreenCaptureManager.captureLatestBitmap(appContext, _uiState.value.settings.screenCaptureMethod)
                }
            }.onSuccess { bitmap ->
                AppDebugLogStore.i(tag, "processFullScreen capture success width=${bitmap.width} height=${bitmap.height}")
                _uiState.update {
                    it.copy(
                        screenshot = bitmap,
                        selectedBitmap = bitmap,
                        // 自动流程是全新的一屏，不沿用上一次长截图的逐屏区段。
                        longScreenshotBitmap = null,
                        longPageSegments = emptyList()
                    )
                }
                processAutoBitmap(bitmap)
            }.onFailure { error ->
                if (error is CancellationException) {
                    AppDebugLogStore.i(tag, "processFullScreen cancelled")
                    return@onFailure
                }
                AppDebugLogStore.e(tag, "processFullScreen failed", error)
                _uiState.update {
                    it.copy(
                        working = false,
                        autoRunState = AutoRunState.IDLE,
                        error = error.message ?: "截屏失败"
                    )
                }
                bubbleStateMachine.forceState(BubbleState.Idle)
            }
        }
        activeAutoProcessingJob = job
        job.invokeOnCompletion {
            if (activeAutoProcessingJob === job) {
                activeAutoProcessingJob = null
            }
        }
    }

    fun process(bitmap: Bitmap) {
        process(listOf(bitmap))
    }

    fun process(bitmaps: List<Bitmap>) {
        val state = _uiState.value
        val firstBitmap = bitmaps.firstOrNull() ?: return
        AppDebugLogStore.i(tag, "process start route=${state.settings.processingRoute} bitmapCount=${bitmaps.size}")

        val models = runCatching { pipeline.resolveModels(state) }.getOrElse { error ->
            _uiState.update { it.copy(error = error.message) }
            return
        }
        val (baseResult, historyId, screenshotPaths) = pipeline.createBaseResult(models, bitmaps, "请求已开始")

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedBitmap = firstBitmap,
                    liveOcrText = "",
                    liveAnswerText = "",
                    result = null,
                    error = null,
                    working = true,
                    sheetVisible = true,
                    sheetMode = OverlaySheetMode.RESULT,
                    miniChatVisible = false,
                    // 已开始处理：长截图会话到此结束（会话标志清零）。
                    // 但保留各屏区段 [longPageSegments]：结果页「原图」据此按屏展示，
                    // 查看时才能左右滑动切换第 1/2/3… 屏（[selectedBitmap] 就是拼接后的长图）。
                    longCaptureActive = false,
                    longCapturePages = 0,
                    longPagePending = false,
                    longCaptureCropRect = null
                )
            }
            upsertHistory(baseResult)
            runCatching {
                withTimeout(processingTimeoutMillis) {
                    when (models.route) {
                        ProcessingRoute.OCR_THEN_LLM -> {
                            pipeline.validateOcrThenLlmModels(models)
                            val (ocrText, answer) = pipeline.streamOcrThenChat(
                                models = models,
                                bitmaps = bitmaps,
                                onOcrDelta = { delta ->
                                    _uiState.update { current -> current.copy(liveOcrText = current.liveOcrText + delta) }
                                },
                                onAnswerDelta = { delta ->
                                    _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                                }
                            )
                            pipeline.buildChatResult(baseResult, models, ocrText, answer, historyId, screenshotPaths)
                        }

                        ProcessingRoute.MULTIMODAL_DIRECT -> {
                            pipeline.validateVisionModels(models)
                            val answer = pipeline.streamVisionDirect(
                                models = models,
                                bitmaps = bitmaps,
                                onAnswerDelta = { delta ->
                                    _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                                }
                            )
                            pipeline.buildChatResult(baseResult, models, "", answer, historyId, screenshotPaths)
                        }

                        ProcessingRoute.ACCESSIBILITY_TEXT -> {
                            pipeline.validateTextModels(models)
                            val (extractedText, answer) = pipeline.streamAccessibilityThenChat(
                                models = models,
                                onTextDelta = { delta ->
                                    _uiState.update { current -> current.copy(liveOcrText = current.liveOcrText + delta) }
                                },
                                onAnswerDelta = { delta ->
                                    _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                                }
                            )
                            pipeline.buildChatResult(baseResult, models, extractedText, answer, historyId, screenshotPaths)
                        }
                    }
                }
            }.onSuccess { result ->
                AppDebugLogStore.i(tag, "process success resultId=${result.id} answerLength=${result.answer.length}")
                upsertHistory(result)
                _uiState.update {
                    it.copy(
                        working = false,
                        result = result,
                        liveOcrText = result.extractedText,
                        liveAnswerText = result.answer,
                        autoRunState = AutoRunState.IDLE,
                        autoCopiedLabel = null
                    )
                }
                bubbleStateMachine.forceState(BubbleState.Idle)
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "process failed", error)
                handleError(error, baseResult)
            }
        }
    }

    fun closeSheet() {
        // 关闭面板即放弃长截图会话，避免下次点悬浮球时被当成「继续拼接」。
        _uiState.update {
            it.copy(
                sheetVisible = false,
                // 从分析页「更多」唤起的迷你窗随抽屉一起关闭，避免面板消失后残留浮层。
                miniChatVisible = false,
                error = null,
                longCaptureActive = false,
                longCapturePages = 0,
                longPagePending = false,
                longScreenshotBitmap = null,
                longPageSegments = emptyList(),
                longCaptureCropRect = null
            )
        }
    }

    fun consumeAutoCompletedState() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.d(tag, "consumeAutoCompletedState state=${_uiState.value.autoRunState} bubble=${currentState::class.simpleName}")
        
        if (_uiState.value.launchMode == OverlayLaunchMode.AUTO && _uiState.value.autoRunState == AutoRunState.COMPLETED) {
            if (currentState is BubbleState.Copied) {
                _uiState.update { it.copy(autoRunState = AutoRunState.IDLE) }
                bubbleStateMachine.forceState(BubbleState.Idle)
            } else {
                _uiState.update { it.copy(autoRunState = AutoRunState.IDLE) }
            }
        }
    }

    fun onBubbleTappedAfterLettersShown() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "onBubbleTappedAfterLettersShown launchMode=${_uiState.value.launchMode} bubble=${currentState::class.simpleName}")
        
        if (_uiState.value.launchMode == OverlayLaunchMode.AUTO && currentState is BubbleState.ShowingLetters) {
            AppDebugLogStore.i(tag, "onBubbleTappedAfterLettersShown clearing letters and entering pending reset")
            bubbleStateMachine.dispatch(BubbleEvent.SingleTap)
        }
    }

    // 多页截图相关方法
    
    /**
     * 进入多页截图模式
     */
    fun enterMultiPageCaptureMode() {
        AppDebugLogStore.i(tag, "enterMultiPageCaptureMode")
        bubbleStateMachine.dispatch(BubbleEvent.LongPress)
    }

    /**
     * 截图一次
     */
    fun capturePage() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "capturePage called state=${currentState::class.simpleName}")
        if (currentState !is BubbleState.MultiPageCapture) {
            AppDebugLogStore.i(tag, "capturePage called but not in MultiPageCapture state")
            return
        }
        
        AppDebugLogStore.i(tag, "capturePage count=${currentState.captureCount}")
        // 先切换到正在截图状态
        bubbleStateMachine.dispatch(BubbleEvent.CaptureStart)
        AppDebugLogStore.i(tag, "capturePage dispatched CaptureStart, new state=${bubbleStateMachine.currentState::class.simpleName}")
        
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    `fun`.kirari.hanako.capture.ScreenCaptureManager.captureLatestBitmap(appContext, _uiState.value.settings.screenCaptureMethod)
                }
            }.onSuccess { bitmap ->
                AppDebugLogStore.i(tag, "capturePage success width=${bitmap.width} height=${bitmap.height}")
                bubbleStateMachine.dispatch(BubbleEvent.CaptureTaken(bitmap))
                AppDebugLogStore.i(tag, "capturePage dispatched CaptureTaken, new state=${bubbleStateMachine.currentState::class.simpleName}")
                // 2 秒后恢复到等待点击状态
                launch {
                    kotlinx.coroutines.delay(2000)
                    bubbleStateMachine.dispatch(BubbleEvent.CaptureSuccessAnimationDone)
                    AppDebugLogStore.i(tag, "capturePage dispatched CaptureSuccessAnimationDone, new state=${bubbleStateMachine.currentState::class.simpleName}")
                }
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "capturePage failed", error)
                _uiState.update { it.copy(error = error.message ?: "截图失败") }
                // 截图失败，恢复到等待点击状态
                bubbleStateMachine.dispatch(BubbleEvent.CaptureFailed)
                AppDebugLogStore.i(tag, "capturePage failed, dispatched CaptureFailed")
            }
        }
    }

    /**
     * 发送截图给 AI
     */
    fun sendCaptures() {
        AppDebugLogStore.i(tag, "sendCaptures called state=${bubbleStateMachine.currentState::class.simpleName}")
        if (!bubbleStateMachine.canSendCaptures()) {
            AppDebugLogStore.i(tag, "sendCaptures called but cannot send")
            return
        }
        
        val bitmaps = bubbleStateMachine.getCapturedBitmaps()
        AppDebugLogStore.i(tag, "sendCaptures count=${bitmaps.size}")
        
        if (bitmaps.isEmpty()) {
            AppDebugLogStore.i(tag, "sendCaptures no bitmaps available")
            return
        }
        
        bubbleStateMachine.dispatch(BubbleEvent.SendCaptures)
        AppDebugLogStore.i(tag, "sendCaptures dispatched SendCaptures, new state=${bubbleStateMachine.currentState::class.simpleName}")
        activeAutoProcessingJob?.cancel()
        val job = viewModelScope.launch {
            processAutoBitmaps(bitmaps)
        }
        activeAutoProcessingJob = job
        job.invokeOnCompletion {
            if (activeAutoProcessingJob === job) {
                activeAutoProcessingJob = null
            }
        }
    }

    /**
     * 退出多页截图模式
     */
    fun exitMultiPageCaptureMode() {
        AppDebugLogStore.i(tag, "exitMultiPageCaptureMode")
        bubbleStateMachine.dispatch(BubbleEvent.DoubleTap)
    }

    /**
     * 处理单击事件（根据当前状态决定行为）
     */
    fun handleSingleTap() {
        val currentState = bubbleStateMachine.currentState
        val launchMode = _uiState.value.launchMode
        AppDebugLogStore.i(tag, "handleSingleTap state=${currentState::class.simpleName} launchMode=$launchMode")

        // 长截图会话中：单击的唯一含义是「把当前屏拼到长图下方」，
        // 不受气泡状态（如 Copied/ShowingLetters）影响，否则会出现点了没反应。
        if (_uiState.value.longCaptureActive) {
            AppDebugLogStore.i(tag, "handleSingleTap -> appendLongCapturePage (long capture active)")
            appendLongCapturePage()
            return
        }

        when (currentState) {
            is BubbleState.MultiPageCapture -> {
                // 多页截图模式下，单击截图
                capturePage()
            }
            is BubbleState.MultiPageCapturing -> {
                // 正在截图中，忽略单击
                AppDebugLogStore.i(tag, "handleSingleTap ignored, capturing in progress")
            }
            is BubbleState.MultiPageCaptureSuccess -> {
                // 截图成功显示中，忽略单击
                AppDebugLogStore.i(tag, "handleSingleTap ignored, showing capture success")
            }
            is BubbleState.Copied -> {
                // 已复制状态下，单击重置
                bubbleStateMachine.dispatch(BubbleEvent.SingleTap)
                // 自动模式下，重置后立即进入新一轮分析：避免完成态残留导致单击只重置不分析、表现为“点击悬浮球无自动截图/分析”
                if (launchMode == OverlayLaunchMode.AUTO) {
                    openCropSheet()
                }
            }
            else -> {
                // 其他状态（Idle、ShowingLetters、Processing 等），打开裁剪页面
                openCropSheet()
            }
        }
    }

    /**
     * 处理长按事件（根据当前状态决定行为）
     */
    fun handleLongPress() {
        val currentState = bubbleStateMachine.currentState
        val launchMode = _uiState.value.launchMode
        AppDebugLogStore.i(tag, "handleLongPress state=${currentState::class.simpleName} launchMode=$launchMode")
        
        when {
            // 普通模式下长按，打开主页面
            launchMode == OverlayLaunchMode.NORMAL -> {
                `fun`.kirari.hanako.overlay.openMainActivity(appContext)
            }
            // 自动模式下，Idle 或 ShowingLetters 状态长按，进入多页截图模式
            launchMode == OverlayLaunchMode.AUTO && (currentState is BubbleState.Idle || currentState is BubbleState.ShowingLetters) -> {
                enterMultiPageCaptureMode()
            }
            // 多页截图模式下，长按发送截图
            currentState is BubbleState.MultiPageCapture -> {
                AppDebugLogStore.i(tag, "handleLongPress MultiPageCapture bitmaps=${currentState.capturedBitmaps.size}")
                if (currentState.capturedBitmaps.isNotEmpty()) {
                    sendCaptures()
                }
            }
            currentState is BubbleState.MultiPageCapturing -> {
                AppDebugLogStore.i(tag, "handleLongPress ignored, capturing in progress")
            }
            currentState is BubbleState.MultiPageCaptureSuccess -> {
                AppDebugLogStore.i(tag, "handleLongPress MultiPageCaptureSuccess bitmaps=${currentState.capturedBitmaps.size}")
                if (currentState.capturedBitmaps.isNotEmpty()) {
                    sendCaptures()
                }
            }
        }
    }

    /**
     * 处理双击事件
     */
    fun handleDoubleTap() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "handleDoubleTap state=${currentState::class.simpleName}")
        
        when (currentState) {
            is BubbleState.MultiPageCapture,
            is BubbleState.MultiPageCapturing,
            is BubbleState.MultiPageCaptureSuccess -> {
                exitMultiPageCaptureMode()
            }
            is BubbleState.Processing -> {
                cancelActiveAutoProcessing()
            }
            else -> {
                // 非截屏流程状态（Idle / ShowingLetters / Copied 等）：双击切换迷你聊天小窗。
                // 保持 MultiPageCapture*/Processing 的既有双击语义不变。
                toggleMiniChat()
            }
        }
    }

    /** 切换迷你聊天小窗可见性（双击悬浮球时调用）。 */
    fun toggleMiniChat() {
        setMiniChatVisible(!_uiState.value.miniChatVisible)
    }

    /**
     * 设置迷你聊天小窗可见性。
     *
     * 与全屏底部抽屉 [OverlayUiState.sheetVisible] 互斥：打开迷你聊天即收起抽屉，
     * 避免两个浮层叠加导致输入焦点与触摸冲突。
     */
    fun setMiniChatVisible(visible: Boolean) {
        AppDebugLogStore.i(tag, "setMiniChatVisible visible=$visible")
        _uiState.update { state ->
            state.copy(
                miniChatVisible = visible,
                sheetVisible = if (visible) false else state.sheetVisible
            )
        }
    }

    /**
     * 分析页标题栏「更多」入口：**保留**当前分析页再唤出迷你聊天窗。
     *
     * 与双击悬浮球不同：双击是二选一切换（打开迷你窗即收起抽屉），
     * 这里让用户能一边看着当前处理页（原图/OCR/题目/答案），一边就这页内容追问。
     */
    fun showMiniChatOverSheet() {
        AppDebugLogStore.i(tag, "showMiniChatOverSheet")
        _uiState.update { it.copy(miniChatVisible = true) }
    }

    fun selectAssistant(assistantId: String) = repository.selectAssistant(viewModelScope, assistantId)

    fun selectPreviousAssistant() {
        val current = _uiState.value.settings
        val assistants = current.assistants
        if (assistants.isEmpty()) return
        val selectedIndex = assistants.indexOfFirst { it.id == current.selectedAssistantId }.takeIf { it >= 0 } ?: 0
        val previousIndex = if (selectedIndex == 0) assistants.lastIndex else selectedIndex - 1
        selectAssistant(assistants[previousIndex].id)
    }

    fun selectNextAssistant() {
        val current = _uiState.value.settings
        val assistants = current.assistants
        if (assistants.isEmpty()) return
        val selectedIndex = assistants.indexOfFirst { it.id == current.selectedAssistantId }.takeIf { it >= 0 } ?: 0
        val nextIndex = if (selectedIndex == assistants.lastIndex) 0 else selectedIndex + 1
        selectAssistant(assistants[nextIndex].id)
    }

    fun updateModelSelection(purpose: ModelPurpose, selection: ModelSelection) =
        repository.updateModelSelection(viewModelScope, purpose, selection)

    fun updateModelSelectionWithFavorite(purpose: ModelPurpose, selection: ModelSelection, favoriteModel: Boolean = false) =
        repository.updateModelSelectionWithFavorite(viewModelScope, purpose, selection, favoriteModel)

    fun toggleFavoriteModel(providerId: String, modelId: String) =
        repository.toggleFavoriteModel(viewModelScope, providerId, modelId)

    fun toggleProcessingRoute() {
        viewModelScope.launch {
            repository.update { current ->
                current.copy(
                    processingRoute = when (current.processingRoute) {
                        ProcessingRoute.OCR_THEN_LLM -> ProcessingRoute.MULTIMODAL_DIRECT
                        ProcessingRoute.MULTIMODAL_DIRECT -> ProcessingRoute.ACCESSIBILITY_TEXT
                        ProcessingRoute.ACCESSIBILITY_TEXT -> ProcessingRoute.OCR_THEN_LLM
                    }
                )
            }
        }
    }

    fun setProcessingRoute(route: ProcessingRoute) {
        viewModelScope.launch {
            repository.update { current -> current.copy(processingRoute = route) }
        }
    }

    fun openWebView(url: String = "") {
        AppDebugLogStore.i(tag, "openWebView url=$url")
        _uiState.update {
            it.copy(
                webViewUrl = url.ifBlank { "https://cn.bing.com" },
                sheetVisible = true,
                sheetMode = OverlaySheetMode.WEBVIEW,
                miniChatVisible = false
            )
        }
    }

    fun searchInWebView(query: String) {
        val url = if (query.startsWith("http://") || query.startsWith("https://")) {
            query
        } else {
            "https://cn.bing.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        }
        _uiState.update { it.copy(webViewUrl = url) }
    }

    /**
     * 处理用户输入（检查是否为快速命令）
     * @return true 如果是命令已处理，false 如果是普通文本需要发送给 LLM
     */
    fun processUserInput(input: String): Boolean {
        val result = QuickCommands.process(input, _uiState.value.settings) ?: return false
        AppDebugLogStore.i(tag, "QuickCommand: ${input.take(20)} -> ${result.message.take(30)}")

        // 执行命令动作
        when (val action = result.action) {
            is QuickCommands.CommandAction.ClearHistory -> {
                viewModelScope.launch { repository.update { it.copy(history = emptyList(), lastResult = null) } }
            }
            is QuickCommands.CommandAction.SwitchModel -> {
                // 查找匹配的模型
                val providers = _uiState.value.settings.providers
                for (provider in providers) {
                    val models = listOf(provider.chatModel, provider.visionModel, provider.ocrModel)
                    if (models.any { it.contains(action.modelQuery, ignoreCase = true) }) {
                        updateModelSelection(action.purpose, ModelSelection(providerId = provider.id, model = action.modelQuery))
                        break
                    }
                }
            }
            is QuickCommands.CommandAction.SetPrompt -> {
                viewModelScope.launch {
                    repository.update { current ->
                        val assistants = current.assistants.map {
                            if (it.id == current.selectedAssistantId) it.copy(textPrompt = action.prompt) else it
                        }
                        current.copy(assistants = assistants)
                    }
                }
            }
            is QuickCommands.CommandAction.OpenWebView -> {
                openWebView(action.url)
            }
            is QuickCommands.CommandAction.SearchWeb -> {
                openWebView("https://cn.bing.com/search?q=${java.net.URLEncoder.encode(action.query, "UTF-8")}")
            }
            else -> {}
        }

        // 显示命令结果
        _uiState.update { it.copy(error = result.message) }
        return true
    }

    /**
     * 无障碍模式直接处理：无需截屏，直接从屏幕提取文本并发送给 LLM。
     */
    private fun processAccessibilityDirect() {
        AppDebugLogStore.i(tag, "processAccessibilityDirect start")
        activeAutoProcessingJob?.cancel()
        val job = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    liveOcrText = "",
                    liveAnswerText = "",
                    result = null,
                    error = null,
                    working = true,
                    sheetVisible = true,
                    sheetMode = OverlaySheetMode.RESULT,
                    miniChatVisible = false,
                    autoRunState = AutoRunState.IDLE,
                    autoCopiedLabel = null
                )
            }

            val state = _uiState.value
            val models = runCatching { pipeline.resolveModels(state) }.getOrElse { error ->
                _uiState.update { it.copy(working = false, error = error.message) }
                return@launch
            }

            val baseResult = ProcessingResult(
                id = java.util.UUID.randomUUID().toString(),
                assistantName = models.assistant.name,
                route = ProcessingRoute.ACCESSIBILITY_TEXT,
                status = ProcessingStatus.RUNNING,
                modelSummary = buildModelSummaryLocal(models.textModel, models.textProvider?.name),
                detail = "无障碍提取已开始",
                events = listOf(ProcessingEvent(title = "请求开始", detail = "无障碍文本提取模式"))
            )
            upsertHistory(baseResult)

            runCatching {
                withTimeout(processingTimeoutMillis) {
                    pipeline.validateTextModels(models)
                    val (extractedText, answer) = pipeline.streamAccessibilityThenChat(
                        models = models,
                        onTextDelta = { delta ->
                            _uiState.update { current -> current.copy(liveOcrText = current.liveOcrText + delta) }
                        },
                        onAnswerDelta = { delta ->
                            _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                        }
                    )
                    pipeline.buildChatResult(baseResult, models, extractedText, answer, baseResult.id, emptyList())
                }
            }.onSuccess { result ->
                AppDebugLogStore.i(tag, "processAccessibilityDirect success answerLength=${result.answer.length}")
                upsertHistory(result)
                _uiState.update {
                    it.copy(
                        working = false,
                        result = result,
                        liveOcrText = result.extractedText,
                        liveAnswerText = result.answer
                    )
                }
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "processAccessibilityDirect failed", error)
                handleError(error, baseResult)
            }
        }
        activeAutoProcessingJob = job
        job.invokeOnCompletion {
            if (activeAutoProcessingJob === job) activeAutoProcessingJob = null
        }
    }

    private fun buildModelSummaryLocal(model: String, providerName: String?): String {
        val trimmedModel = model.trim()
        val trimmedProvider = providerName?.trim().orEmpty()
        if (trimmedModel.isBlank()) return ""
        return if (trimmedProvider.isBlank()) trimmedModel else "$trimmedModel（$trimmedProvider）"
    }

    /**
     * 自动模式下的无障碍处理：直接提取文本并执行自动化动作。
     */
    private suspend fun processAccessibilityAutoBitmap() {
        val state = _uiState.value
        AppDebugLogStore.i(tag, "processAccessibilityAutoBitmap start")

        val models = runCatching { pipeline.resolveModels(state) }.getOrElse { error ->
            _uiState.update { it.copy(working = false, autoRunState = AutoRunState.IDLE, error = error.message) }
            bubbleStateMachine.forceState(BubbleState.Idle)
            return
        }
        val baseResult = ProcessingResult(
            id = java.util.UUID.randomUUID().toString(),
            assistantName = models.assistant.name,
            route = ProcessingRoute.ACCESSIBILITY_TEXT,
            status = ProcessingStatus.RUNNING,
            modelSummary = buildModelSummaryLocal(models.textModel, models.textProvider?.name),
            detail = "无障碍自动流程已开始",
            events = listOf(ProcessingEvent(title = "请求开始", detail = "无障碍文本提取自动模式"))
        )
        upsertHistory(baseResult)

        runCatching<Pair<`fun`.kirari.hanako.data.AutomationActionRecord, ProcessingResult>> {
            withTimeout(processingTimeoutMillis) {
                pipeline.validateTextModels(models)
                val (extractedText, automationResult) = pipeline.streamAccessibilityThenAutomation(
                    models = models,
                    onTextDelta = { delta ->
                        _uiState.update { current -> current.copy(liveOcrText = current.liveOcrText + delta) }
                    },
                    onThoughtDelta = { delta ->
                        _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                    }
                )
                pipeline.buildAutomationResult(baseResult, models, extractedText, automationResult, baseResult.id, emptyList())
            }
        }.onSuccess { (action, result) ->
            AppDebugLogStore.i(tag, "processAccessibilityAutoBitmap success action=${action.type}")
            upsertHistory(result)
            handleAutomationAction(action, result, null)
        }.onFailure { error ->
            if (error is CancellationException) {
                AppDebugLogStore.i(tag, "processAccessibilityAutoBitmap cancelled")
                return
            }
            AppDebugLogStore.e(tag, "processAccessibilityAutoBitmap failed", error)
            handleError(error, baseResult, isAutoMode = true)
        }
    }

    private suspend fun processAutoBitmap(bitmap: Bitmap) {
        processAutoBitmaps(listOf(bitmap))
    }

    private suspend fun processAutoBitmaps(bitmaps: List<Bitmap>) {
        val state = _uiState.value
        val firstBitmap = bitmaps.firstOrNull() ?: return
        AppDebugLogStore.i(tag, "processAutoBitmaps start route=${state.settings.processingRoute} bitmapCount=${bitmaps.size}")

        val models = runCatching { pipeline.resolveModels(state) }.getOrElse { error ->
            _uiState.update { it.copy(working = false, autoRunState = AutoRunState.IDLE, error = error.message) }
            bubbleStateMachine.forceState(BubbleState.Idle)
            return
        }
        val (baseResult, historyId, screenshotPaths) = pipeline.createBaseResult(models, bitmaps, "自动流程已开始")
        upsertHistory(baseResult)

        runCatching<Pair<`fun`.kirari.hanako.data.AutomationActionRecord, ProcessingResult>> {
            withTimeout(processingTimeoutMillis) {
                val (action, result) = when (models.route) {
                    ProcessingRoute.OCR_THEN_LLM -> {
                        pipeline.validateOcrThenLlmModels(models)
                        val (ocrText, automationResult) = pipeline.streamOcrThenAutomation(
                            models = models,
                            bitmaps = bitmaps,
                            onOcrDelta = { delta ->
                                _uiState.update { current -> current.copy(liveOcrText = current.liveOcrText + delta) }
                            },
                            onThoughtDelta = { delta ->
                                _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                            }
                        )
                        pipeline.buildAutomationResult(baseResult, models, ocrText, automationResult, historyId, screenshotPaths)
                    }

                    ProcessingRoute.MULTIMODAL_DIRECT -> {
                        pipeline.validateVisionModels(models)
                        val automationResult = pipeline.streamAutomationDirect(
                            models = models,
                            bitmaps = bitmaps,
                            onThoughtDelta = { delta ->
                                _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                            }
                        )
                        pipeline.buildAutomationResult(baseResult, models, "", automationResult, historyId, screenshotPaths)
                    }

                    ProcessingRoute.ACCESSIBILITY_TEXT -> {
                        pipeline.validateTextModels(models)
                        val (extractedText, automationResult) = pipeline.streamAccessibilityThenAutomation(
                            models = models,
                            onTextDelta = { delta ->
                                _uiState.update { current -> current.copy(liveOcrText = current.liveOcrText + delta) }
                            },
                            onThoughtDelta = { delta ->
                                _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                            }
                        )
                        pipeline.buildAutomationResult(baseResult, models, extractedText, automationResult, historyId, screenshotPaths)
                    }
                }
                AppDebugLogStore.i(tag, "processAutoBitmaps gateway success resultId=${result.id} action=${action.type}")
                upsertHistory(result)
                action to result
            }
        }.onSuccess { (action, result) ->
            handleAutomationAction(action, result, firstBitmap)
        }.onFailure { error ->
            if (error is CancellationException) {
                AppDebugLogStore.i(tag, "processAutoBitmaps cancelled")
                return
            }
            AppDebugLogStore.e(tag, "processAutoBitmaps failed", error)
            handleError(error, baseResult, isAutoMode = true)
        }
    }

    /**
     * 统一处理自动化动作
     */
    private fun handleAutomationAction(
        action: `fun`.kirari.hanako.data.AutomationActionRecord,
        result: ProcessingResult,
        bitmap: Bitmap?
    ) {
        when (action.type) {
            AutomationActionType.SET_CLIPBOARD -> {
                _uiState.update {
                    it.copy(
                        screenshot = bitmap ?: it.screenshot,
                        selectedBitmap = bitmap ?: it.selectedBitmap,
                        working = false,
                        result = result,
                        liveAnswerText = result.automationThought,
                        autoRunState = AutoRunState.COMPLETED,
                        autoCopiedLabel = action.text,
                        error = null
                    )
                }
                bubbleStateMachine.dispatch(BubbleEvent.CopyComplete(action.text))
            }
            AutomationActionType.SHOW_BUBBLE_LETTERS -> {
                _uiState.update {
                    it.copy(
                        screenshot = bitmap ?: it.screenshot,
                        selectedBitmap = bitmap ?: it.selectedBitmap,
                        working = false,
                        result = result,
                        liveAnswerText = result.automationThought,
                        autoRunState = AutoRunState.COMPLETED,
                        autoCopiedLabel = null,
                        error = null
                    )
                }
                bubbleStateMachine.dispatch(BubbleEvent.LettersComplete(action.text))
            }
            AutomationActionType.AUTO_FILL_INPUT -> {
                val success = `fun`.kirari.hanako.accessibility.AccessibilityActions.autoFillInput(action.extra, action.text)
                _uiState.update {
                    it.copy(
                        working = false,
                        result = result,
                        liveAnswerText = if (success) "已自动填写: ${action.text}" else "自动填写失败",
                        autoRunState = AutoRunState.COMPLETED,
                        autoCopiedLabel = null,
                        error = if (success) null else "未找到匹配的输入框"
                    )
                }
                bubbleStateMachine.forceState(`fun`.kirari.hanako.automation.BubbleState.Idle)
            }
            AutomationActionType.AUTO_CLICK_BUTTON -> {
                val success = `fun`.kirari.hanako.accessibility.AccessibilityActions.autoClickButton(action.text)
                _uiState.update {
                    it.copy(
                        working = false,
                        result = result,
                        liveAnswerText = if (success) "已点击: ${action.text}" else "自动点击失败",
                        autoRunState = AutoRunState.COMPLETED,
                        autoCopiedLabel = null,
                        error = if (success) null else "未找到匹配的按钮"
                    )
                }
                bubbleStateMachine.forceState(`fun`.kirari.hanako.automation.BubbleState.Idle)
            }
            AutomationActionType.VOICE_READ -> {
                // 自动模式不再自动朗读：由分析页「答案」模块的手动朗读按钮代替（避免强制出声干扰）。
                _uiState.update {
                    it.copy(
                        working = false,
                        result = result,
                        liveAnswerText = action.text,
                        autoRunState = AutoRunState.COMPLETED,
                        autoCopiedLabel = null,
                        error = null
                    )
                }
                bubbleStateMachine.forceState(`fun`.kirari.hanako.automation.BubbleState.Idle)
            }
        }
    }

    private fun cancelActiveAutoProcessing() {
        AppDebugLogStore.i(tag, "cancelActiveAutoProcessing")
        activeAutoProcessingJob?.cancel()
        activeAutoProcessingJob = null
        _uiState.update {
            it.copy(
                working = false,
                autoRunState = AutoRunState.IDLE,
                autoCopiedLabel = null,
                error = null
            )
        }
        bubbleStateMachine.dispatch(BubbleEvent.CancelProcessing)
        if (bubbleStateMachine.currentState !is BubbleState.Idle) {
            bubbleStateMachine.forceState(BubbleState.Idle)
        }
    }

    private suspend fun handleError(error: Throwable, baseResult: ProcessingResult, isAutoMode: Boolean = false) {
        val isTimeout = error is TimeoutCancellationException
        val message = error.message?.ifBlank { null } ?: if (isTimeout) "请求超时（90 秒）" else "处理失败"
        upsertHistory(
            baseResult.copy(
                status = if (isTimeout) ProcessingStatus.TIMEOUT else ProcessingStatus.ERROR,
                detail = message,
                extractedText = _uiState.value.liveOcrText,
                answer = if (isAutoMode) "" else _uiState.value.liveAnswerText,
                automationThought = if (isAutoMode) _uiState.value.liveAnswerText.orEmpty() else "",
                events = baseResult.events + ProcessingEvent(
                    title = if (isTimeout) "请求超时" else "请求失败",
                    detail = message
                )
            )
        )
        _uiState.update {
            it.copy(
                working = false,
                autoRunState = AutoRunState.IDLE,
                error = message
            )
        }
        if (isAutoMode) {
            bubbleStateMachine.forceState(BubbleState.Idle)
        }
    }

    private suspend fun upsertHistory(result: ProcessingResult) {
        repository.update { current ->
            val history = listOf(result) + current.history.filterNot { it.id == result.id }
            current.copy(lastResult = result, history = history)
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory {
            val container = (appContext.applicationContext as `fun`.kirari.hanako.HanakoApplication).container
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return OverlayViewModel(
                        appContext = appContext,
                        repository = container.settingsRepository,
                        pipeline = ProcessingPipeline(appContext, container.unifiedLLMClient, container.localOcrManager),
                        chatSessionHolder = container.chatSessionHolder,
                        chatModelCatalog = container.chatModelCatalog
                    ) as T
                }
            }
        }
    }
}
