package `fun`.kirari.hanako.overlay

import android.app.Service
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.content.Intent
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import `fun`.kirari.hanako.automation.BubbleRenderer
import `fun`.kirari.hanako.automation.BubbleState
import `fun`.kirari.hanako.capture.CaptureUiGate
import `fun`.kirari.hanako.capture.CaptureWindowVisibility
import `fun`.kirari.hanako.capture.MaskWindow
import `fun`.kirari.hanako.capture.WindowRect
import `fun`.kirari.hanako.util.FloatingBallIconStore
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import `fun`.kirari.hanako.R
import `fun`.kirari.hanako.HanakoApplication
import `fun`.kirari.hanako.capture.MediaProjectionForegroundService
import `fun`.kirari.hanako.capture.ProjectionPermissionActivity
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.easeOutCubic
import `fun`.kirari.hanako.copyToClipboard
import `fun`.kirari.hanako.copyToClipboardWithToast
import `fun`.kirari.hanako.util.HapticHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val logTag = "HanakoOverlaySvc"
    private val dispatcher = ServiceLifecycleDispatcher(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val serviceViewModelStore = ViewModelStore()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val lifecycle: Lifecycle
        get() = dispatcher.lifecycle

    override val savedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = serviceViewModelStore

    private lateinit var windowManager: WindowManager
    private var bubbleView: FrameLayout? = null
    private var bubbleSurfaceView: FrameLayout? = null
    private var bubbleIconView: ImageView? = null
    private var bubbleTextView: TextView? = null
    private var bubbleSpinnerView: ProgressBar? = null
    private var bubbleColorAnimator: ValueAnimator? = null
    private var bubbleCompletionResetJob: Job? = null
    private var lastHandledCompletionId: String? = null
    // 自定义悬浮球图标解码缓存（避免每次 uiState 刷新重复解码文件）。
    private var cachedCustomIconPath: String? = null
    private var cachedCustomIconDrawable: BitmapDrawable? = null
    private var panelView: FrameLayout? = null
    private var panelContentView: androidx.compose.ui.platform.ComposeView? = null
    private var panelHandleView: FrameLayout? = null
    private lateinit var overlayViewModel: OverlayViewModel
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelHandleParams: WindowManager.LayoutParams? = null
    private var panelScreenHeightPx: Int = 0
    private var panelHeightPx: Int = 0
    /** 面板窗口允许的最大高度（常态 = [panelHeightPx]；聊天展开且键盘弹出时临时上扩）。 */
    private var panelMaxHeightPx: Int = 0
    /** 是否因「聊天展开 + 键盘可见」而临时把面板向上扩展（覆盖面板上方的空余屏幕）。 */
    private var panelChatImeExpanded = false
    private var panelDockHeightPx: Int = 0
    private var panelCurrentHeightPx: Int = 0
    private var panelHandleHeightPx: Int = 0
    private var panelHandleWidthPx: Int = 0
    private var panelAnimationJob: Job? = null
    private var panelClosing = false

    // 迷你聊天小窗宿主（独立于全屏底部抽屉）
    private var miniChatView: FrameLayout? = null
    private var miniChatComposeView: androidx.compose.ui.platform.ComposeView? = null
    private var miniChatParams: WindowManager.LayoutParams? = null
    /** 迷你聊天窗当前是否处于放大尺寸（长按切换小/大）。 */
    private var miniChatLarge = false

    /**
     * 截图期间临时隐藏 Hanako 自有悬浮窗（悬浮球 / 分析面板 / 迷你聊天窗）的可见性记账：
     * 避免它们被拍进截图污染 OCR；结束后按截图前的可见性精确恢复。
     */
    private val captureWindowVisibility = CaptureWindowVisibility<View>(
        isVisible = { it.visibility == View.VISIBLE },
        isAttached = { it.isAttachedToWindow },
        setVisible = { view, visible -> view.visibility = if (visible) View.VISIBLE else View.GONE }
    )

    /**
     * 截图隐藏期间被临时置零的**窗口级 alpha 原值**（键为窗口 View）。
     *
     * 与 [captureWindowVisibility] 的可见性记账相互独立：前者是 View 标志，这里记的是
     * `WindowManager.LayoutParams.alpha`（窗口层属性）。两者都必须精确恢复。
     */
    private val captureWindowAlphaSnapshot = LinkedHashMap<View, Float>()

    /**
     * 截图前输入法（软键盘）是否可见，以及当时持有输入焦点的 Hanako 窗口。
     *
     * 真机根因（R8.5）：输入法是**独立于 Hanako 的窗口**，既不属于 [hanakoCaptureWindows]，
     * 也不会被位图遮罩覆盖，因此截图会把键盘按键（`GHI JKL MNO` 等）拍进去并被 OCR 识别成题目。
     * 隐藏 Hanako 窗口前先收起输入法；采集结束后按此记账把键盘恢复回截图前的可见状态。
     */
    private var imeWasVisibleBeforeCapture = false
    private var imeTargetView: View? = null

    // 防截屏检测
    private var secureWindowPollingJob: Job? = null
    private var currentSecureWindowCount = 0

    // 摇一摇检测
    private var shakeDetector: ShakeDetector? = null

    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        HapticHelper.init(this)
        runCatching {
            overlayViewModel = ViewModelProvider(
                this,
                OverlayViewModel.factory(applicationContext)
            )[OverlayViewModel::class.java]
            OverlayRuntimeState.setRunning(true)
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            showBubble()
            observeUiState()
            startSecureWindowPolling()
            startShakeDetection()
            // 截图期间自动隐藏/恢复 Hanako 自有悬浮窗（悬浮球/分析面板/迷你聊天窗），避免遮挡截图内容。
            // 「frames」提供窗口屏幕矩形：采集侧默认据此保持窗口可见并对位图做遮罩（消除闪烁）；
            // 隐藏/恢复仍作为无遮罩能力时的安全回退，路径保留不删除。
            CaptureUiGate.register(
                start = ::hideHanakoWindowsForCapture,
                end = ::restoreHanakoWindowsAfterCapture,
                frames = ::hanakoCaptureWindowFrames
            )
        }.onFailure {
            AppDebugLogStore.e(logTag, "Overlay initialization failed", it)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        dispatcher.onServicePreSuperOnStart()
        AppDebugLogStore.i(
            logTag,
            "onStartCommand action=${intent?.action} launchModeExtra=${intent?.getStringExtra(ProjectionPermissionActivity.EXTRA_LAUNCH_MODE)}"
        )
        when (intent?.action) {
            ACTION_STOP -> {
                dismissPanel()
                stopSelf()
            }
            ACTION_CAPTURE -> {
                // 从通知栏触发截屏处理
                overlayViewModel.setProcessingRoute(`fun`.kirari.hanako.data.ProcessingRoute.OCR_THEN_LLM)
                overlayViewModel.handleSingleTap()
            }
            ACTION_A11Y_EXTRACT -> {
                // 从通知栏触发无障碍提取
                overlayViewModel.setProcessingRoute(`fun`.kirari.hanako.data.ProcessingRoute.ACCESSIBILITY_TEXT)
                overlayViewModel.handleSingleTap()
            }
            else -> {
                intent?.getStringExtra(ProjectionPermissionActivity.EXTRA_LAUNCH_MODE)
                    ?.let { runCatching { OverlayLaunchMode.valueOf(it) }.getOrNull() }
                    ?.let(overlayViewModel::setLaunchMode)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        CaptureUiGate.unregister()
        dismissPanel()
        hideMiniChat()
        stopSecureWindowPolling()
        stopShakeDetection()
        TtsHelper.shutdown()
        bubbleColorAnimator?.cancel()
        bubbleColorAnimator = null
        bubbleCompletionResetJob?.cancel()
        bubbleCompletionResetJob = null
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleSurfaceView = null
        bubbleIconView = null
        bubbleTextView = null
        bubbleSpinnerView = null
        cachedCustomIconPath = null
        cachedCustomIconDrawable = null
        OverlayRuntimeState.setRunning(false)
        serviceViewModelStore.clear()
        serviceScope.cancel()
        super.onDestroy()
        dispatcher.onServicePreSuperOnDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeUiState() {
        serviceScope.launch {
            overlayViewModel.uiState.collect { state ->
                AppDebugLogStore.d(
                    logTag,
                    "uiState launchMode=${state.launchMode} autoRunState=${state.autoRunState} bubble=${state.bubbleState::class.simpleName} sheetVisible=${state.sheetVisible} working=${state.working} resultId=${state.result?.id} error=${state.error}"
                )
                updateBubbleAppearance(state.bubbleState, state.launchMode)
                if (state.sheetVisible) {
                    // 全屏抽屉可见时隐藏悬浮球，避免它压住截图内容/被拍进截图。
                    // 迷你聊天窗不再强制收起：分析页「更多」可在保留抽屉的同时唤出迷你窗，
                    // 让用户边看当前处理页边追问；双击悬浮球仍走「二选一」切换（见 setMiniChatVisible）。
                    setBubbleVisible(false)
                    showOrUpdatePanel(state.sheetMode)
                } else {
                    hidePanelWithAnimation()
                    setBubbleVisible(true)
                }
                if (state.miniChatVisible) showMiniChat() else hideMiniChat()
                if (state.autoRunState == AutoRunState.COMPLETED) {
                    val completionId = state.result?.id ?: state.autoCopiedLabel
                    if (completionId != null && completionId != lastHandledCompletionId) {
                        AppDebugLogStore.i(
                            logTag,
                            "auto completion handled completionId=$completionId copiedLabel=${state.autoCopiedLabel} bubble=${state.bubbleState::class.simpleName}"
                        )
                        lastHandledCompletionId = completionId
                        state.autoCopiedLabel?.let { copyToClipboard(this@OverlayService, "Hanako Auto Copy", it) }
                        HapticHelper.successFeedback()
                        if (state.settings.automation.completionNotificationEnabled) {
                            notifyAutomationCompleted(state.autoCopiedLabel)
                        }
                        bubbleCompletionResetJob?.cancel()
                        bubbleCompletionResetJob = serviceScope.launch {
                            delay(AUTO_COMPLETED_VISIBLE_MS)
                            overlayViewModel.consumeAutoCompletedState()
                        }
                    }
                } else {
                    if (bubbleCompletionResetJob != null) {
                        AppDebugLogStore.d(logTag, "auto completion reset job cancelled because state=${state.autoRunState}")
                    }
                    bubbleCompletionResetJob?.cancel()
                    bubbleCompletionResetJob = null
                }
            }
        }
    }

    /**
     * 悬浮球显隐：截图抽屉可见时隐藏（不遮挡截图内容），抽屉关闭或「继续截图」时恢复。
     *
     * 只在状态实际变化时改 View 可见性，避免每个 uiState 都重复写一次。
     */
    private fun setBubbleVisible(visible: Boolean) {
        val view = bubbleView ?: return
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility == target) return
        view.visibility = target
        AppDebugLogStore.d(logTag, "bubble visibility -> ${if (visible) "VISIBLE" else "GONE"}")
    }

    private fun showBubble() {
        val rootSizePx = (56f * resources.displayMetrics.density).roundToInt()
        val surfaceSizePx = (40f * resources.displayMetrics.density).roundToInt()
        val iconSizePx = (20f * resources.displayMetrics.density).roundToInt()
        val spinnerSizePx = (54f * resources.displayMetrics.density).roundToInt()
        val params = WindowManager.LayoutParams(
            rootSizePx,
            rootSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 悬浮球默认出现在屏幕右下角偏上位置，避免遮挡底部导航/手势区。
            gravity = Gravity.END or Gravity.BOTTOM
            x = (24f * resources.displayMetrics.density).roundToInt()
            y = (170f * resources.displayMetrics.density).roundToInt()
        }
        bubbleParams = params
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val spinnerView = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = FrameLayout.LayoutParams(spinnerSizePx, spinnerSizePx, Gravity.CENTER)
            scaleX = 1.00f
            scaleY = 1.00f
            isIndeterminate = true
            visibility = View.GONE
            alpha = 0f
        }
        val iconView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val textView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(surfaceSizePx, surfaceSizePx, Gravity.CENTER)
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = View.GONE
        }
        val surfaceView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(surfaceSizePx, surfaceSizePx, Gravity.CENTER)
            addView(iconView)
            addView(textView)
        }
        val view = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(rootSizePx, rootSizePx)
            addView(spinnerView)
            addView(surfaceView)
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            var longPressTriggered = false
            val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
            val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
            var lastTapTime = 0L
            var doubleTapDetected = false
            
            val longPressRunnable = Runnable {
                if (!dragging) {
                    longPressTriggered = true
                    performHapticFeedback(
                        HapticFeedbackConstants.LONG_PRESS,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
                    HapticHelper.mediumTap()
                    overlayViewModel.handleLongPress()
                }
            }
            
            val doubleTapTimeoutRunnable = Runnable {
                if (!doubleTapDetected && !longPressTriggered) {
                    // 单击超时，执行单击操作
                    HapticHelper.lightTap()
                    overlayViewModel.handleSingleTap()
                }
                doubleTapDetected = false
            }
            
            setOnTouchListener { _: View, event: MotionEvent ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                        longPressTriggered = false
                        doubleTapDetected = false
                        removeCallbacks(longPressRunnable)
                        removeCallbacks(doubleTapTimeoutRunnable)
                        postDelayed(longPressRunnable, longPressTimeout)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                            dragging = true
                            removeCallbacks(longPressRunnable)
                            removeCallbacks(doubleTapTimeoutRunnable)
                        }
                        if (dragging) {
                            // 悬浮球使用 Gravity.END or Gravity.BOTTOM：此时 params.x/y 是从屏幕
                            // 右/下边缘量起的距离，直接加屏幕位移会让两个方向的移动**反向**。
                            // 统一交给 windowOffsetDeltaFor 依据真实 gravity 换算（END/BOTTOM 取反，
                            // START/TOP 保持正向），调用处不再对符号做二次反转。
                            val delta = windowOffsetDeltaFor(
                                screenDx = dx,
                                screenDy = dy,
                                anchoredToEnd = isAnchoredToScreenEnd(params.gravity),
                                anchoredToBottom = isAnchoredToScreenBottom(params.gravity)
                            )
                            params.x = startX + delta.x
                            params.y = startY + delta.y
                            windowManager.updateViewLayout(this, params)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        removeCallbacks(longPressRunnable)
                        if (!dragging && !longPressTriggered) {
                            val currentTime = android.os.SystemClock.uptimeMillis()
                            val timeSinceLastTap = currentTime - lastTapTime
                            
                            if (timeSinceLastTap < doubleTapTimeout) {
                                // 双击检测
                                doubleTapDetected = true
                                removeCallbacks(doubleTapTimeoutRunnable)
                                HapticHelper.successFeedback()
                                overlayViewModel.handleDoubleTap()
                            } else {
                                // 可能是单击或双击的第一次，等待超时
                                lastTapTime = currentTime
                                postDelayed(doubleTapTimeoutRunnable, doubleTapTimeout)
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(longPressRunnable)
                        removeCallbacks(doubleTapTimeoutRunnable)
                        true
                    }

                    else -> false
                }
            }
        }
        bubbleSurfaceView = surfaceView
        bubbleIconView = iconView
        bubbleTextView = textView
        bubbleSpinnerView = spinnerView
        windowManager.addView(view, params)
        bubbleView = view
        updateBubbleAppearance(BubbleState.Idle, OverlayLaunchMode.NORMAL)
    }

    private fun showOrUpdatePanel(mode: OverlaySheetMode) {
        panelClosing = false
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenHeightPx = metrics.heightPixels
        val density = resources.displayMetrics.density
        val dockHeightPx = resources.displayMetrics.density.times(SheetDockOffset.value).roundToInt()
        val targetHeightPx = (screenHeightPx * if (mode == OverlaySheetMode.CROP) 0.88f else 0.92f)
            .roundToInt()
            .coerceAtLeast(dockHeightPx)

        panelScreenHeightPx = screenHeightPx
        panelHeightPx = targetHeightPx
        if (!panelChatImeExpanded) panelMaxHeightPx = targetHeightPx
        panelDockHeightPx = dockHeightPx
        panelHandleHeightPx = (28f * density).roundToInt()
        panelHandleWidthPx = (88f * density).roundToInt()
        if (panelView == null) {
            panelCurrentHeightPx = dockHeightPx
        } else {
            // 上限取当前允许的最大高度：聊天+键盘期间窗口已上扩，不能被 uiState 刷新缩回常态高度。
            val ceiling = panelMaxHeightPx.coerceAtLeast(targetHeightPx)
            panelCurrentHeightPx = panelCurrentHeightPx.coerceIn(dockHeightPx, ceiling)
        }

        val params = panelParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panelCurrentHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeightPx - panelCurrentHeightPx
        }
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = panelCurrentHeightPx
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = (screenHeightPx - panelCurrentHeightPx).coerceAtLeast(0)
        panelParams = params

        val handleParams = panelHandleParams ?: WindowManager.LayoutParams(
            panelHandleWidthPx,
            panelHandleHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = screenHeightPx - panelCurrentHeightPx
        }
        handleParams.width = panelHandleWidthPx
        handleParams.height = panelHandleHeightPx
        handleParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        handleParams.x = 0
        handleParams.y = panelHandleY(screenHeightPx, panelCurrentHeightPx)
        panelHandleParams = handleParams

        if (panelView == null) {
            val panelRoot = PanelDragInterceptFrameLayout(this).apply {
                setViewTreeLifecycleOwner(this@OverlayService)
                setViewTreeViewModelStoreOwner(this@OverlayService)
                setViewTreeSavedStateRegistryOwner(this@OverlayService)
                clipChildren = true
                clipToPadding = true
            }
            val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@OverlayService)
                setViewTreeViewModelStoreOwner(this@OverlayService)
                setViewTreeSavedStateRegistryOwner(this@OverlayService)
                // 覆盖窗口内 Compose 内容禁用系统栏 inset 填充，避免导航栏 inset 造成底部空隙，
                // 使分析页面内容与相册页一致、沾满到底部区域。
                ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ ->
                    WindowInsetsCompat.CONSUMED
                }
                setContent {
                    val panelSettings = overlayViewModel.uiState.value.settings
                    `fun`.kirari.hanako.ui.theme.HanakoTheme(
                        themeMode = panelSettings.themeMode,
                        colorStyle = panelSettings.colorStyle
                    ) {
                        OverlayPanel(
                            viewModel = overlayViewModel,
                            onDismiss = { overlayViewModel.closeSheet() },
                            panelHeightPx = panelHeightPx,
                            contentAtTopChange = { atTop -> panelRoot.contentAtTop = atTop },
                            onChatExpandedChange = ::setPanelChatFocusable,
                            // 键盘弹出 + 聊天展开：面板窗口向上扩展给消息区更多高度。
                            onChatImeChange = ::setPanelChatImeExpanded
                        )
                    }
                }
            }
            panelRoot.onDrag = ::dragPanelBy
            panelRoot.onSettle = ::settlePanel
            panelRoot.addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            val handleView = createPanelHandleView()
            panelView = panelRoot
            panelContentView = composeView
            panelHandleView = handleView
            windowManager.addView(panelRoot, params)
            windowManager.addView(handleView, handleParams)
            applyPanelHeight(panelCurrentHeightPx)
            animatePanelHeight(
                fromHeightPx = panelCurrentHeightPx,
                toHeightPx = targetHeightPx
            )
        } else {
            runCatching { windowManager.updateViewLayout(panelView, params) }
            runCatching { windowManager.updateViewLayout(panelHandleView, handleParams) }
            applyPanelHeight(panelCurrentHeightPx)
        }
    }

    private fun createPanelHandleView(): FrameLayout {
        var lastRawY = 0f
        return FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                FrameLayout(this@OverlayService).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(86, 86, 86))
                        cornerRadius = 999f * resources.displayMetrics.density
                    }
                },
                FrameLayout.LayoutParams(
                    (68f * resources.displayMetrics.density).roundToInt(),
                    (8f * resources.displayMetrics.density).roundToInt()
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = (10f * resources.displayMetrics.density).roundToInt()
                }
            )
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        panelAnimationJob?.cancel()
                        lastRawY = event.rawY
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        // 跟随手指实时收缩/展开（增量位移，允许低于停靠高度，便于下拉关闭）。
                        dragPanelBy(event.rawY - lastRawY)
                        lastRawY = event.rawY
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        // 松手吸附：下拉超过面板高度约 1/3 → 关闭，否则回弹全展开。
                        settlePanel()
                        true
                    }

                    else -> false
                }
            }
        }
    }

    /**
     * 跟随手指收缩面板高度（内容顶部下拉 / 把手拖拽共用）。
     *
     * 与停靠值下限不同，这里允许高度低于停靠值直至 0，
     * 便于松手后按吸附逻辑决定「回停靠」还是「关闭」。
     */
    private fun dragPanelBy(deltaY: Float) {
        if (panelHeightPx <= 0) return
        panelAnimationJob?.cancel()
        val next = panelCurrentHeightPx - deltaY.roundToInt()
        applyPanelHeight(next.coerceIn(0, panelMaxHeightPx.coerceAtLeast(panelHeightPx)))
    }

    /**
     * 松手吸附（与相册页一致）：下拉位移超过面板高度约 1/3 → 关闭；否则回弹到目标（全展开）高度。
     */
    private fun settlePanel() {
        val current = panelCurrentHeightPx
        val target = panelMaxHeightPx.coerceAtLeast(panelHeightPx)
        if (target <= 0) return
        if (current < target * 2 / 3) {
            overlayViewModel.closeSheet()
        } else if (current != target) {
            animatePanelHeight(current, target)
        }
    }

    private fun applyPanelHeight(heightPx: Int) {
        val view = panelView ?: return
        val params = panelParams
        val handleParams = panelHandleParams
        if (panelHeightPx <= 0 || panelScreenHeightPx <= 0) return
        panelCurrentHeightPx =
            heightPx.coerceIn(0, panelMaxHeightPx.coerceAtLeast(panelHeightPx))
        if (params != null) {
            params.y = (panelScreenHeightPx - panelCurrentHeightPx).coerceAtLeast(0)
            params.height = panelCurrentHeightPx.coerceAtLeast(1)
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        if (handleParams != null) {
            handleParams.y = panelHandleY(panelScreenHeightPx, panelCurrentHeightPx)
            runCatching { windowManager.updateViewLayout(panelHandleView, handleParams) }
        }
    }

    private fun panelHandleY(screenHeightPx: Int, currentHeightPx: Int): Int {
        val offsetPx = (PanelHandleYOffset.value * resources.displayMetrics.density).roundToInt()
        return (screenHeightPx - currentHeightPx - offsetPx).coerceAtLeast(0)
    }

    private fun animatePanelHeight(
        fromHeightPx: Int,
        toHeightPx: Int,
        onEnd: (() -> Unit)? = null
    ) {
        panelAnimationJob?.cancel()
        panelAnimationJob = serviceScope.launch {
            val ceiling = panelMaxHeightPx.coerceAtLeast(panelHeightPx)
            val start = fromHeightPx.coerceIn(0, ceiling)
            val end = toHeightPx.coerceIn(0, ceiling)
            animatePanelHeightSegment(
                startHeightPx = start,
                endHeightPx = end,
                durationMs = SheetAnimationDurationMs,
                easing = ::easeOutCubic
            )
            updatePanelHeightAllowZero(end)
            onEnd?.invoke()
        }
    }

    private suspend fun animatePanelHeightSegment(
        startHeightPx: Int,
        endHeightPx: Int,
        durationMs: Int,
        easing: (Float) -> Float
    ) {
        val startTimeMs = android.os.SystemClock.uptimeMillis()
        while (currentCoroutineContext().isActive) {
            val elapsed = android.os.SystemClock.uptimeMillis() - startTimeMs
            val fraction = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
            val eased = easing(fraction)
            val height = (startHeightPx + (endHeightPx - startHeightPx) * eased).roundToInt()
            updatePanelHeightAllowZero(height)
            if (fraction >= 1f) break
            kotlinx.coroutines.delay(16L)
        }
        updatePanelHeightAllowZero(endHeightPx)
    }

    private fun updatePanelHeightAllowZero(heightPx: Int) {
        applyPanelHeight(heightPx)
    }

    private fun hidePanelWithAnimation() {
        if (panelClosing) return
        val view = panelView ?: return
        panelClosing = true
        val startHeight = panelCurrentHeightPx
        animatePanelHeight(
            fromHeightPx = startHeight,
            toHeightPx = 0
        ) {
            if (panelView === view) {
                removePanelNow()
            }
        }
    }

    private fun updateBubbleAppearance(bubbleState: BubbleState, launchMode: OverlayLaunchMode) {
        AppDebugLogStore.d(
            logTag,
            "updateBubbleAppearance state=${bubbleState::class.simpleName} launchMode=$launchMode"
        )
        val bubble = bubbleSurfaceView ?: return
        val icon = bubbleIconView ?: return
        val textView = bubbleTextView
        val spinner = bubbleSpinnerView
        val appearance = BubbleRenderer.render(bubbleState, launchMode, this)
        
        val existing = bubble.background as? GradientDrawable
        val drawable = existing ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((1.5f * resources.displayMetrics.density).roundToInt(), Color.WHITE)
        }
        val startColor = existing?.color?.defaultColor ?: appearance.backgroundColor
        bubbleColorAnimator?.cancel()
        bubbleColorAnimator = ValueAnimator.ofArgb(startColor, appearance.backgroundColor).apply {
            duration = 220L
            addUpdateListener { animator ->
                drawable.setColor(animator.animatedValue as Int)
                bubble.background = drawable
            }
            start()
        }

        if (appearance.letters != null) {
            textView?.text = appearance.letters
            textView?.setTextColor(appearance.iconTint)
            textView?.visibility = View.VISIBLE
            icon.visibility = View.GONE
        } else {
            textView?.visibility = View.GONE
            icon.visibility = View.VISIBLE
            // 自定义悬浮球图片：优先展示用户上传图标（不做 tint，保持原色）；
            // 文件缺失/损坏时回退默认图标。
            val customPath = overlayViewModel.uiState.value.settings.floatingBall.customIconPath
            val customDrawable = if (customPath != null) {
                if (cachedCustomIconPath != customPath) {
                    cachedCustomIconDrawable = FloatingBallIconStore.loadDrawable(this, customPath)
                    cachedCustomIconPath = customPath
                }
                cachedCustomIconDrawable
            } else {
                cachedCustomIconPath = null
                cachedCustomIconDrawable = null
                null
            }
            val targetTag = if (customDrawable != null) "custom:$customPath" else appearance.iconRes
            val targetDrawable = customDrawable ?: ContextCompat.getDrawable(this, appearance.iconRes)
            val targetTint = if (customDrawable != null) null else ColorStateList.valueOf(appearance.iconTint)
            if (icon.tag != targetTag) {
                icon.animate().cancel()
                icon.animate()
                    .alpha(0f)
                    .scaleX(0.82f)
                    .scaleY(0.82f)
                    .setDuration(120L)
                    .withEndAction {
                        icon.setImageDrawable(targetDrawable)
                        icon.imageTintList = targetTint
                        icon.tag = targetTag
                        icon.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(180L)
                            .start()
                    }
                    .start()
            } else {
                icon.setImageDrawable(targetDrawable)
                icon.imageTintList = targetTint
                icon.alpha = 1f
                icon.scaleX = 1f
                icon.scaleY = 1f
            }
        }

        spinner?.apply {
            // 设置 spinner 颜色
            val drawable = indeterminateDrawable?.mutate()
            if (drawable != null) {
                drawable.setColorFilter(appearance.spinnerColor, PorterDuff.Mode.SRC_IN)
                indeterminateDrawable = drawable
            }
            animate().cancel()
            if (appearance.showSpinner) {
                visibility = View.VISIBLE
                animate().alpha(1f).setDuration(160L).start()
            } else {
                animate()
                    .alpha(0f)
                    .setDuration(160L)
                    .withEndAction { visibility = View.GONE }
                    .start()
            }
        }
    }

    /**
     * 截图开始：临时隐藏所有会遮挡被截内容的 Hanako 自有悬浮窗
     * （悬浮球、分析面板及其把手、迷你聊天窗），避免它们出现在截图中污染 OCR。
     *
     * 用 [CaptureWindowVisibility] 记录每个窗口隐藏前的可见性，结束后按原状恢复：
     * 截图前本就隐藏的窗口不会被强制显示，已关闭/已移除的窗口也不会被重新添加。
     *
     * 同时在 WindowManager 层把这些窗口的 `LayoutParams.alpha` 置 0（见 [captureWindowAlphaSnapshot]）：
     * 只改 View 可见性不足以保证窗口不进入合成结果，窗口级 alpha 才是与是否重绘无关的可靠手段。
     *
     * **必须在主线程执行**（由 [CaptureUiGate] 的调用方经 `Dispatchers.Main.immediate` 保证）：
     * View 的 visibility 与 WindowManager 布局参数都绑定在主线程的 ViewRootImpl 上。
     * 两步都遵循「先记录原状、再改写」，任意一步中途失败都可由 [restoreHanakoWindowsAfterCapture] 回滚；
     * 若隐藏后仍有窗口可见或 alpha 未能可靠置零，则主动抛异常中止本次截图，绝不冒险继续取帧。
     */
    private fun hideHanakoWindowsForCapture() {
        // 步骤 0：先收起输入法软键盘。键盘是**独立于 Hanako 的窗口**，既不在 [hanakoCaptureWindows] 里，
        // 也不会被位图遮罩覆盖；不收起就会随 Hanako 浮窗一起被拍进截图，并被 OCR 识别成题目内容
        // （真机证据：OCR 结果里的 `GHI JKL MNO` / `PQRS TUV WXYZ`）。先记账、再收起，结束后按原状恢复。
        hideImeForCapture()

        val paramsByWindow = hanakoCaptureWindowParams()

        // 步骤 1：把**窗口级 alpha** 置 0（WindowManager 属性，由系统合成阶段作用于整个窗口层）。
        // 为什么必须在「View 可见性」之外补这一步（真机污染证据对应的根因）：
        // 把窗口根 View 设为 GONE 时，ViewRootImpl 会直接跳过本次绘制
        // （Android 15 源码 android/view/ViewRootImpl.java "Not drawing due to not visible"），
        // 该窗口的 last buffer 不会被替换，画面是否真的从合成结果里消失完全取决于系统侧行为，
        // 应用侧既无法保证也无法验证（原自检只回读同一个 View 标志，属于同义反复）。
        // 窗口 alpha 由系统合成阶段生效，与缓冲新旧、是否重绘无关，
        // 因此能确保该窗口层的画面不参与本次截图的合成。
        // 逐个「先记录原值、再改写」，改写失败不静默吞掉（记录窗口与异常类型供诊断）。
        captureWindowAlphaSnapshot.clear()
        val alphaFailures = mutableListOf<String>()
        var alphaApplied = 0
        paramsByWindow.forEach { (view, params) ->
            if (!view.isAttachedToWindow) return@forEach
            captureWindowAlphaSnapshot[view] = params.alpha
            params.alpha = 0f
            try {
                windowManager.updateViewLayout(view, params)
                alphaApplied += 1
            } catch (e: Exception) {
                alphaFailures += "${view.javaClass.simpleName}:${e.javaClass.simpleName}"
            }
        }

        // 步骤 2：View 可见性记账（先记录原状再改写；中途失败也会被恢复）。
        val windows = hanakoCaptureWindows()
        val hidden = captureWindowVisibility.hideAll(windows)
        // 隐藏后立即复核：仍可见的窗口数必须为 0。
        val stillVisible = windows.count { it.isAttachedToWindow && it.visibility == View.VISIBLE }
        AppDebugLogStore.i(
            logTag,
            "Hanako windows hidden for capture thread=${Thread.currentThread().name} " +
                "total=${windows.size} hidden=$hidden stillVisible=$stillVisible " +
                "alphaApplied=$alphaApplied/${paramsByWindow.size} " +
                "alphaFailures=${alphaFailures.ifEmpty { listOf("-") }}"
        )
        // 仍有窗口可见、或窗口级 alpha 未能可靠置零时，本次无法安全隐藏：
        // 主动中止本次截图（调用方捕获后会立即回滚恢复），绝不带着「窗口可能进入截图」的风险继续取帧。
        if (stillVisible > 0 || alphaFailures.isNotEmpty()) {
            throw IllegalStateException(
                "capture hide unsafe: stillVisible=$stillVisible alphaFailures=${alphaFailures.size}"
            )
        }
    }

    /**
     * 截图结束（成功、失败或取消）：只把截图前可见的窗口恢复为可见，并还原窗口级 alpha 原值。
     */
    private fun restoreHanakoWindowsAfterCapture() {
        val windows = hanakoCaptureWindows()
        // 恢复前复核：截图期间应一直保持隐藏，若此处仍可见说明隐藏状态在截图过程中被破坏。
        val hiddenBeforeRestore = windows.count { it.isAttachedToWindow && it.visibility != View.VISIBLE }
        // 按记录的原始可见性逐个写回（不是无条件 VISIBLE）；返回实际完成恢复的窗口数。
        val restored = captureWindowVisibility.restoreAll()

        // 窗口级 alpha 恢复：按记录的原值精确还原（截图前本就 alpha=0 的窗口不会被强制显示）。
        // 失败不静默吞掉（记录窗口与异常类型供诊断）。
        val paramsByWindow = hanakoCaptureWindowParams()
        var alphaRestored = 0
        val alphaFailures = mutableListOf<String>()
        captureWindowAlphaSnapshot.forEach { (view, alpha) ->
            val params = paramsByWindow[view] ?: return@forEach
            if (!view.isAttachedToWindow) return@forEach
            params.alpha = alpha
            try {
                windowManager.updateViewLayout(view, params)
                alphaRestored += 1
            } catch (e: Exception) {
                alphaFailures += "${view.javaClass.simpleName}:${e.javaClass.simpleName}"
            }
        }
        captureWindowAlphaSnapshot.clear()

        // 恢复输入法：仅在截图前键盘确实可见时尝试恢复显示（否则保持收起，避免凭空弹出键盘）。
        val imeRestored = restoreImeAfterCapture()

        // 恢复后逐窗复核最终状态（类名 / 可见性 / 是否附着），确认悬浮球与迷你窗各自回到截图前状态。
        // 仅记录窗口自身状态，不记录任何屏幕文本内容。
        val states = windows.joinToString(prefix = "[", postfix = "]") { view ->
            "${view.javaClass.simpleName}(visibility=${view.visibility},attached=${view.isAttachedToWindow})"
        }
        AppDebugLogStore.i(
            logTag,
            "Hanako windows restored after capture thread=${Thread.currentThread().name} " +
                "hiddenBeforeRestore=$hiddenBeforeRestore restored=$restored alphaRestored=$alphaRestored " +
                "alphaFailures=${alphaFailures.ifEmpty { listOf("-") }} imeRestored=$imeRestored states=$states"
        )
    }

    /** 截图前收起输入法软键盘：先记账（是否可见 + 持有焦点的窗口），再请求收起。 */
    private fun hideImeForCapture() {
        val windows = hanakoCaptureWindows().filter { it.isAttachedToWindow }
        val target = windows.firstOrNull { it.hasWindowFocus() } ?: windows.firstOrNull()
        imeTargetView = target
        imeWasVisibleBeforeCapture = target?.let { isImeVisibleOn(it) } ?: false
        if (target == null) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    /** 输入法是否可见（按 androidx 兼容 API 读取窗口 insets，避免直接依赖 Android 10+ 的 WindowInsets.Type）。 */
    private fun isImeVisibleOn(view: View): Boolean =
        ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /**
     * 采集结束：截图前键盘可见则尝试恢复显示。
     *
     * 返回是否真正发起恢复（供诊断日志确认「已按记账恢复」）。恢复失败只记录、不抛出 ——
     * 窗口可见性恢复是本方法的最高优先级，不能被输入法恢复的异常连累。
     */
    private fun restoreImeAfterCapture(): Boolean {
        val target = imeTargetView
        val wasVisible = imeWasVisibleBeforeCapture
        imeTargetView = null
        imeWasVisibleBeforeCapture = false
        if (!wasVisible || target == null || !target.isAttachedToWindow) return false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return runCatching {
            imm.showSoftInput(target, 0)
        }.onFailure { AppDebugLogStore.w(logTag, "restore IME failed: ${it.javaClass.simpleName}") }.isSuccess
    }

    /** 全部参与截图遮挡规避的 Hanako 自有窗口。 */
    private fun hanakoCaptureWindows(): List<View> =
        listOfNotNull(bubbleView, panelView, panelHandleView, miniChatView)

    /**
     * Hanako 自有窗口的当前屏幕坐标矩形（供截图位图遮罩）。
     *
     * 只在主线程读取（`View.getLocationOnScreen` 绑定主线程；由采集侧经 `Dispatchers.Main.immediate`
     * 调用）。未附着的窗口标记 `attached=false`，不参与遮罩，避免用陈旧矩形误遮真实题目区域。
     */
    private fun hanakoCaptureWindowFrames(): List<MaskWindow> =
        hanakoCaptureWindows().map { view ->
            // 只有「已附着且可见」的窗口才真的参与屏幕合成。隐藏（GONE/INVISIBLE）的窗口若仍按
            // 其陈旧矩形参与遮罩，会在截图里留下一块纯黑方块（悬浮球随截图页隐藏后尤其明显）。
            if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) {
                MaskWindow(WindowRect(0, 0, 0, 0), attached = false)
            } else {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                MaskWindow(
                    WindowRect(
                        location[0],
                        location[1],
                        location[0] + view.width,
                        location[1] + view.height
                    ),
                    attached = true
                )
            }
        }

    /**
     * 参与截图隐藏的窗口与其 `WindowManager.LayoutParams` 对应表。
     *
     * 以当前源码的真实窗口清单为准：悬浮球 [bubbleView]、分析面板 [panelView] 及其把手
     * [panelHandleView]、迷你聊天窗 [miniChatView]；除此之外 OverlayService 不再向
     * WindowManager 添加任何窗口，也没有独立 Popup / 次级 Overlay。
     */
    private fun hanakoCaptureWindowParams(): Map<View, WindowManager.LayoutParams> {
        val map = LinkedHashMap<View, WindowManager.LayoutParams>()
        bubbleView?.let { view -> bubbleParams?.let { map[view] = it } }
        panelView?.let { view -> panelParams?.let { map[view] = it } }
        panelHandleView?.let { view -> panelHandleParams?.let { map[view] = it } }
        miniChatView?.let { view -> miniChatParams?.let { map[view] = it } }
        return map
    }

    private fun dismissPanel() {
        panelAnimationJob?.cancel()
        panelAnimationJob = null
        panelClosing = false
        removePanelNow()
    }

    private fun removePanelNow() {
        setPanelChatFocusable(false)
        // 复位「键盘展开时上扩」状态，避免下次打开面板沿用放大后的高度上限。
        panelChatImeExpanded = false
        panelMaxHeightPx = 0
        panelHandleView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        panelView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        panelHandleView = null
        panelContentView = null
        panelView = null
        panelHandleParams = null
        panelParams = null
        panelCurrentHeightPx = 0
        panelClosing = false
    }

    /**
     * 结果页「继续追问」展开/收起时切换面板窗口能否获取输入焦点。
     *
     * 面板窗口默认 `FLAG_NOT_FOCUSABLE`（不抢底层应用焦点），代价是窗口内输入框无法弹出软键盘。
     * 展开内嵌聊天时临时移除该标志（保留 `FLAG_NOT_TOUCH_MODAL`，窗口外点击继续透传给底层应用），
     * 收起后恢复，避免常驻抢焦点。
     */
    private fun setPanelChatFocusable(focusable: Boolean) {
        val view = panelView ?: return
        val params = panelParams ?: return
        val currentlyFocusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (currentlyFocusable == focusable) return
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        params.softInputMode = if (focusable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+：不移动窗口，改由页面用 Compose 的 imePadding 压缩可视区，
                // 键盘弹出时输入框与整排功能键都停在键盘上方（旧做法 adjustPan 只保证
                // 输入框露出，输入框下方那一截仍会被键盘压住）。
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            } else {
                // 旧版本取不到可靠的 ime insets，退回系统平移，至少保证输入框可见。
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }
        runCatching { windowManager.updateViewLayout(view, params) }.onFailure {
            AppDebugLogStore.e(logTag, "setPanelChatFocusable failed focusable=$focusable", it)
        }
        if (!focusable) {
            // 收起聊天区后主动收起键盘，避免残留键盘压住已缩回的页面。
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            runCatching { imm?.hideSoftInputFromWindow(view.windowToken, 0) }
        }
    }

    /**
     * 「继续追问已展开且键盘可见」时把面板窗口向上扩展给聊天内容区。
     *
     * 面板常态只占屏幕 92%，上方约 8% 是屏幕空余；键盘弹出后又压掉约 1/3 高度，
     * 中间消息区只剩很小一段。此时把窗口上边缘顶到状态栏下沿（[panelMaxHeightPx] 临时放大），
     * 键盘收起或聊天收起后立即恢复常态高度。窗口下边缘始终在屏幕底部，ime 内边距计算不受影响。
     */
    private fun setPanelChatImeExpanded(expanded: Boolean) {
        if (panelChatImeExpanded == expanded) return
        panelChatImeExpanded = expanded
        if (panelClosing) return
        if (panelView == null || panelHeightPx <= 0 || panelScreenHeightPx <= 0) return
        panelMaxHeightPx = if (expanded) {
            (panelScreenHeightPx - statusBarHeightPx()).coerceAtLeast(panelHeightPx)
        } else {
            panelHeightPx
        }
        // 扩展后面板顶部的拖拽把手会跑进状态栏，直接隐藏；恢复常态时再显示。
        panelHandleView?.visibility = if (expanded) View.INVISIBLE else View.VISIBLE
        applyPanelHeight(if (expanded) panelMaxHeightPx else panelHeightPx)
    }

    /** 系统状态栏高度（像素）；取不到时按 24dp 估算。 */
    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) {
            resources.getDimensionPixelSize(resId)
        } else {
            (24f * resources.displayMetrics.density).roundToInt()
        }
    }

    // ========================
    // 迷你聊天小窗
    // ========================

    /**
     * 展示迷你聊天小窗（圆角卡片形态的独立悬浮窗）。
     *
     * 与全屏底部抽屉互斥；窗口可获取输入焦点以弹出软键盘，窗口外点击透传。
     */
    private fun showMiniChat() {
        if (miniChatView != null) return
        // S2 触发点：显示迷你窗（首次主动唤醒）时按实时前台应用建立/重建会话绑定。
        (application as? HanakoApplication)?.container?.bindTargetFromLiveForeground()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val density = resources.displayMetrics.density
        val screenWidthPx = metrics.widthPixels
        val screenHeightPx = metrics.heightPixels
        val widthPx = (300f * density).roundToInt()
            .coerceAtMost((screenWidthPx * 0.9f).roundToInt())
        val heightPx = (400f * density).roundToInt()
            .coerceAtMost((screenHeightPx * 0.72f).roundToInt())

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不设置 FLAG_NOT_FOCUSABLE：允许输入框获取焦点并弹出软键盘；
            // FLAG_NOT_TOUCH_MODAL 让窗口外点击透传给底层应用。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidthPx - widthPx - (16f * density).roundToInt()).coerceAtLeast(0)
            y = (screenHeightPx * 0.18f).roundToInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                val miniProviders by overlayViewModel.providers.collectAsState()
                val miniAlpha by overlayViewModel.miniChatAlpha.collectAsState()
                val miniThemeMode by overlayViewModel.themeMode.collectAsState()
                val miniColorStyle by overlayViewModel.colorStyle.collectAsState()
                `fun`.kirari.hanako.ui.theme.HanakoTheme(
                    themeMode = miniThemeMode,
                    colorStyle = miniColorStyle
                ) {
                    MiniChatPanel(
                        holder = overlayViewModel.chatSessionHolder,
                        providers = miniProviders,
                        catalog = overlayViewModel.chatModelCatalog,
                        backgroundAlpha = miniAlpha,
                        onHeaderDrag = { dx, dy -> moveMiniChatBy(dx, dy) },
                        onOpenMainApp = { openMainChatAndHideMini() },
                        onClose = { overlayViewModel.setMiniChatVisible(false) },
                        onLongPressResize = { toggleMiniChatSize() },
                        // 迷你窗与「继续追问」共用同一会话；发送时实时读取当前分析页内容。
                        pageContext = { overlayResultPageContext(overlayViewModel.uiState.value) }
                    )
                }
            }
        }
        val root = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        miniChatParams = params
        miniChatView = root
        miniChatComposeView = composeView
        runCatching { windowManager.addView(root, params) }.onFailure {
            AppDebugLogStore.e(logTag, "showMiniChat failed", it)
            miniChatView = null
            miniChatComposeView = null
            miniChatParams = null
        }
    }

    private fun hideMiniChat() {
        val view = miniChatView ?: return
        runCatching { windowManager.removeView(view) }
        miniChatView = null
        miniChatComposeView = null
        miniChatParams = null
    }

    /** 迷你窗顶部拖拽：以增量像素位移更新窗口位置。 */
    private fun moveMiniChatBy(dx: Float, dy: Float) {
        val view = miniChatView ?: return
        val params = miniChatParams ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * 长按迷你聊天窗：在小/大两种尺寸间切换（窗口中心保持不变，超屏回落）。
     */
    private fun toggleMiniChatSize() {
        val view = miniChatView ?: return
        val params = miniChatParams ?: return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val density = resources.displayMetrics.density
        val screenWidthPx = metrics.widthPixels
        val screenHeightPx = metrics.heightPixels
        val smallW = (300f * density).roundToInt()
        val smallH = (400f * density).roundToInt()
        val largeW = (440f * density).roundToInt()
        val largeH = (600f * density).roundToInt()
        val targetW = if (miniChatLarge) smallW else largeW
        val targetH = if (miniChatLarge) smallH else largeH
        val newW = targetW.coerceAtMost((screenWidthPx * 0.92f).roundToInt())
        val newH = targetH.coerceAtMost((screenHeightPx * 0.82f).roundToInt())
        // 保持窗口中心不偏移；超出屏幕时回落进屏幕内。
        val centerX = params.x + params.width / 2
        val centerY = params.y + params.height / 2
        params.width = newW
        params.height = newH
        params.x = (centerX - newW / 2).coerceIn(0, (screenWidthPx - newW).coerceAtLeast(0))
        params.y = (centerY - newH / 2).coerceIn(0, (screenHeightPx - newH).coerceAtLeast(0))
        miniChatLarge = !miniChatLarge
        AppDebugLogStore.d(logTag, "toggleMiniChatSize large=${miniChatLarge} w=${newW} h=${newH}")
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** 迷你窗「打开」：收起迷你窗并跳转主 App 聊天页。 */
    private fun openMainChatAndHideMini() {
        overlayViewModel.setMiniChatVisible(false)
        openMainActivityForChat(this)
    }

    // ========================
    // 防截屏保护
    // ========================

    private fun startSecureWindowPolling() {
        if (secureWindowPollingJob?.isActive == true) return
        secureWindowPollingJob = serviceScope.launch {
            AppDebugLogStore.d(logTag, "Secure window polling started")
            while (isActive) {
                val count = SecureWindowDetector.getExternalSecureWindowCount()
                if (count >= 0 && count != currentSecureWindowCount) {
                    currentSecureWindowCount = count
                    applySecureMode(count > 0)
                }
                delay(800L)
            }
        }
    }

    private fun stopSecureWindowPolling() {
        secureWindowPollingJob?.cancel()
        secureWindowPollingJob = null
        currentSecureWindowCount = 0
    }

    /**
     * 切换防截屏模式。
     * 当外部存在 FLAG_SECURE 窗口时，隐藏悬浮球并标记为安全模式；
     * 当外部隐私窗口消失时，恢复正常显示。
     */
    private fun applySecureMode(secure: Boolean) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        AppDebugLogStore.d(logTag, "applySecureMode secure=$secure")
        if (secure) {
            // 隐藏：添加 FLAG_SECURE 并降低透明度
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_SECURE
            runCatching { windowManager.updateViewLayout(view, params) }
            view.alpha = 0.15f
            dismissPanel()
        } else {
            // 恢复：移除 FLAG_SECURE 并恢复透明度
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            runCatching { windowManager.updateViewLayout(view, params) }
            view.alpha = 0.85f
        }
    }

    // ========================
    // 摇一摇检测
    // ========================

    private fun startShakeDetection() {
        shakeDetector = ShakeDetector(this).apply {
            start {
                AppDebugLogStore.i(logTag, "Shake detected, triggering capture")
                overlayViewModel.handleSingleTap()
            }
        }
    }

    private fun stopShakeDetection() {
        shakeDetector?.stop()
        shakeDetector = null
    }

    companion object {
        const val ACTION_STOP = "fun.kirari.hanako.overlay.STOP"
        const val ACTION_CAPTURE = "fun.kirari.hanako.overlay.CAPTURE"
        const val ACTION_A11Y_EXTRACT = "fun.kirari.hanako.overlay.A11Y_EXTRACT"
        internal const val CHANNEL_ID = "overlay_service"
        internal const val AUTOMATION_CHANNEL_ID = "overlay_automation"
        private const val NOTIFICATION_ID = 1001
        internal const val AUTOMATION_COMPLETE_NOTIFICATION_ID = 1002
        private const val AUTO_COMPLETED_VISIBLE_MS = 2800L
    }
}
