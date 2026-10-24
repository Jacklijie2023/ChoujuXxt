package `fun`.kirari.hanako.capture

/**
 * 截图期间悬浮球可见性 / 遮罩来源开关。
 *
 * 采集策略（见 [chooseCaptureUiStrategy]）：
 * - **默认**：窗口保持可见，只取一次 Hanako 自有窗口矩形（[hanakoWindowFrames]，须在主线程读取），
 *   截图后对位图中这些区域做遮罩，从而不再每次采集都改变窗口可见性（消除闪烁）；
 * - **回退**：无遮罩提供者时，仍走既有的隐藏/恢复路径 [notifyCaptureStart]/[notifyCaptureEnd]，
 *   该安全路径保留不删除。
 *
 * [onCaptureStart] / [onCaptureEnd] 必须在主线程执行（由调用方用 `Dispatchers.Main.immediate` 保证）。
 */
internal object CaptureUiGate {
    @Volatile
    private var onCaptureStart: (() -> Unit)? = null

    @Volatile
    private var onCaptureEnd: (() -> Unit)? = null

    /** Hanako 自有窗口的屏幕坐标矩形来源（含是否附着）；无则为 null（触发隐藏回退）。 */
    @Volatile
    private var windowFrames: (() -> List<MaskWindow>)? = null

    fun register(
        start: () -> Unit,
        end: () -> Unit,
        frames: (() -> List<MaskWindow>)? = null
    ) {
        onCaptureStart = start
        onCaptureEnd = end
        windowFrames = frames
    }

    fun unregister() {
        onCaptureStart = null
        onCaptureEnd = null
        windowFrames = null
    }

    /** 是否具备遮罩能力（决定采集策略）。 */
    fun isMaskProviderAvailable(): Boolean = windowFrames != null

    /**
     * 是否具备「真正隐藏 / 恢复 Hanako 窗口」的能力。
     *
     * 用户请求触发的采集必须用这条路径：位图遮罩只会把被浮窗覆盖的区域涂黑，无法还原其背后的
     * 目标题目页面。仅当 OverlayService 注册了 start 与 end 时才算可用。
     */
    fun isHideProviderAvailable(): Boolean = onCaptureStart != null && onCaptureEnd != null

    /**
     * 读取 Hanako 自有窗口的当前屏幕矩形。**必须在主线程调用**（[android.view.View.getLocationOnScreen]）。
     */
    fun hanakoWindowFrames(): List<MaskWindow> = windowFrames?.invoke().orEmpty()

    fun notifyCaptureStart() {
        onCaptureStart?.invoke()
    }

    fun notifyCaptureEnd() {
        onCaptureEnd?.invoke()
    }
}
