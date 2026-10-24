package `fun`.kirari.hanako.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.DisplayMetrics
import android.view.WindowManager
import `fun`.kirari.hanako.data.ScreenCaptureMethod
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.overlay.OverlayLaunchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** 截图显隐生命周期相关诊断日志标签。 */
private const val captureGateLogTag = "HanakoCapture"

/** 整屏尺寸（px），即窗口矩形与整屏截图共同的坐标基准。 */
private data class ScreenSize(val width: Int, val height: Int)

internal sealed interface ScreenCaptureStartResult {
    data object Started : ScreenCaptureStartResult
    data class UserActionRequired(val message: String) : ScreenCaptureStartResult
    data class Failed(val message: String) : ScreenCaptureStartResult
}

internal interface ScreenCaptureBackend {
    val method: ScreenCaptureMethod
    val sessionActive: StateFlow<Boolean>

    fun hasActiveSession(): Boolean

    /** 本进程内该截图方式是否曾有会话成功建立（区分「从未连上」与「曾连上后已断开」）。 */
    fun hasEverEstablishedSession(): Boolean

    fun requestStart(context: Context, launchMode: OverlayLaunchMode): ScreenCaptureStartResult

    /**
     * 取当前屏幕一帧。
     *
     * [minFrameTimestampNanos] 非空表示「Hanako 窗口已在该时刻完成隐藏」：只接受不早于该时刻的帧，
     * 避免把隐藏前产出的旧帧（仍含 Hanako 浮窗）交给 OCR。无法证明帧新鲜度的后端（如 Shizuku
     * shell screencap）忽略该参数，由调用方在诊断日志中区分。
     */
    suspend fun captureLatestBitmap(context: Context, minFrameTimestampNanos: Long? = null): Bitmap

    fun stop(context: Context)
}

internal object ScreenCaptureManager {
    private val backends: Map<ScreenCaptureMethod, ScreenCaptureBackend> = listOf(
        MediaProjectionCaptureBackend,
        ShizukuCaptureBackend
    ).associateBy(ScreenCaptureBackend::method)

    fun requestStart(
        context: Context,
        method: ScreenCaptureMethod,
        launchMode: OverlayLaunchMode
    ): ScreenCaptureStartResult = backend(method).requestStart(context, launchMode)

    /**
     * 取一帧当前屏幕位图。
     *
     * [occludingCaptureRequired] 为用户请求触发的**强制采集**：此时必须拿到未被 Hanako 浮窗遮挡的
     * 真实目标页面，因此优先「真正隐藏窗口 → 等待隐藏后产出的新帧 → 结束时恢复」的事务路径
     * （[HanakoCaptureUiStrategy.HIDE_WINDOWS]）；位图遮罩只会把被覆盖区域涂黑，无法还原其背后内容，
     * 不能作为该场景的答案。后台低频探测传 false，保持窗口可见以避免闪烁。
     */
    suspend fun captureLatestBitmap(
        context: Context,
        method: ScreenCaptureMethod,
        occludingCaptureRequired: Boolean = false
    ): Bitmap {
        // 重叠截图必须串行：悬浮窗可见性记账实例是共享的，后进入者会清空前者的原状快照。
        return CaptureLifecycleLock.runExclusive {
            val strategy = chooseCaptureUiStrategy(
                maskProviderAvailable = CaptureUiGate.isMaskProviderAvailable(),
                occludingCaptureRequired = occludingCaptureRequired,
                hideProviderAvailable = CaptureUiGate.isHideProviderAvailable()
            )
            when (strategy) {
                // 默认：保持窗口可见 + 位图遮罩（不再每次采集都隐藏/恢复窗口 → 消除闪烁）。
                HanakoCaptureUiStrategy.MASK_WINDOWS -> captureWithMaskedHanakoWindows(context, method)
                // 用户请求强制采集 / 无遮罩提供者：隐藏窗口 → 取新帧 → 恢复（既有安全路径）。
                HanakoCaptureUiStrategy.HIDE_WINDOWS -> captureWithHiddenHanakoWindows(context, method)
            }
        }
    }

    /**
     * 默认采集路径：Hanako 窗口**保持可见**，只取一次窗口矩形后截图，再把自身区域从位图遮罩掉。
     *
     * 依据：MediaProjection 虚拟显示使用 `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`（全屏合成镜像，未加
     * `OWN_CONTENT_ONLY`），因此 Hanako 自有悬浮窗一定会被拍进截图，必须在位图阶段排除，而不是靠
     * 反复隐藏窗口。窗口矩形只在主线程读一次（`View.getLocationOnScreen` 绑定主线程），全程不改变
     * 任何窗口可见性，因此不会产生「隐藏→恢复」的闪烁，也不会重新引入 CalledFromWrongThreadException。
     *
     * 坐标基准（R8.1 修复）：窗口矩形（`getLocationOnScreen`）与整屏截图位图同处**整屏坐标系**，
     * 故屏幕基准取整屏尺寸 [realScreenSize]（`getRealMetrics`），**不再**使用
     * `resources.displayMetrics`（应用可用区，通常不含状态栏/导航栏，会把底部矩形错误裁短并引入多余缩放）。
     *
     * 安全策略（R8.1 修复）：预期需遮罩却取不到有效区域、位图复制失败、绘制失败时一律**抛异常中止**
     * 本次采集，绝不静默返回未遮罩原图给 OCR。
     */
    private suspend fun captureWithMaskedHanakoWindows(
        context: Context,
        method: ScreenCaptureMethod
    ): Bitmap {
        val frames = withContext(Dispatchers.Main.immediate) { CaptureUiGate.hanakoWindowFrames() }
        val bitmap = backend(method).captureLatestBitmap(context)
        val screen = realScreenSize(context)
        val plan = planHanakoMask(
            windows = frames,
            screenWidth = screen.width,
            screenHeight = screen.height,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height
        )
        return when (plan.decision) {
            // 已确认无窗口需要遮罩：原图可安全使用，不算故障。
            MaskDecision.NO_WINDOWS -> {
                logMaskCapture(method, plan, bitmap, screen, masked = false, reason = "no_windows")
                bitmap
            }
            // 预期需遮罩却取不到有效区域：不安全，中止本次采集，绝不返回未遮罩原图。
            MaskDecision.NO_REGIONS -> {
                logMaskCapture(
                    method, plan, bitmap, screen,
                    masked = false, reason = MaskFailureReason.NO_REGIONS.logName
                )
                throw HanakoMaskException(
                    MaskFailureReason.NO_REGIONS,
                    "存在 ${plan.attachedCount} 个已附着 Hanako 窗口，但夹取后无任何有效遮罩区域"
                )
            }
            MaskDecision.MASK -> {
                val masked = try {
                    maskBitmapRegions(bitmap, plan.regions)
                } catch (maskError: HanakoMaskException) {
                    logMaskCapture(
                        method, plan, bitmap, screen,
                        masked = false, reason = maskError.reason.logName
                    )
                    throw maskError
                }
                logMaskCapture(method, plan, bitmap, screen, masked = true, reason = null)
                masked
            }
        }
    }

    /**
     * 整屏尺寸（`Display.getRealMetrics`），即窗口矩形与整屏截图**共同**的坐标基准。
     *
     * **不得**改用 `resources.displayMetrics`：后者是应用可用区（通常不含状态栏/导航栏），
     * 会把靠近屏幕底部的窗口矩形错误裁短，并对矩形引入多余缩放。
     */
    private fun realScreenSize(context: Context): ScreenSize {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return ScreenSize(metrics.widthPixels, metrics.heightPixels)
    }

    /** 脱敏记录一次遮罩结果：只记尺寸/数量/结论，不含任何画面内容或题面文字。 */
    private fun logMaskCapture(
        method: ScreenCaptureMethod,
        plan: MaskPlan,
        bitmap: Bitmap,
        screen: ScreenSize,
        masked: Boolean,
        reason: String?
    ) {
        AppDebugLogStore.i(
            captureGateLogTag,
            "Hanako capture masked backend=$method windows=${plan.windowCount} " +
                "attached=${plan.attachedCount} regions=${plan.regions.size} " +
                "screen=${screen.width}x${screen.height} bitmap=${bitmap.width}x${bitmap.height} " +
                "masked=$masked${reason?.let { " reason=$it" } ?: ""} " +
                "thread=${Thread.currentThread().name}"
        )
    }

    /**
     * 隐藏/恢复采集路径：真正隐藏 Hanako 窗口（含窗口级 alpha=0）→ 取**隐藏后产出的新帧** → 恢复。
     *
     * 关键：把「隐藏完成」时刻作为 `minFrameTimestampNanos` 传给后端，`ImageReader` 里缓存的
     * 隐藏前旧帧（仍含浮窗）会被丢弃并计入 `frame-stale-discarded`，超时则抛 [StaleFrameException]
     * 明确失败 —— 绝不把含浮窗的旧帧交给 OCR。
     */
    private suspend fun captureWithHiddenHanakoWindows(
        context: Context,
        method: ScreenCaptureMethod
    ): Bitmap {
        // System.nanoTime() 与 MediaProjection 的 `Image.getTimestamp()` 同基准
        // （见 ProjectionSessionManager.captureLatestBitmap 的 ageMillis 计算）。
        var hideCompletedNanos: Long? = null
        val bitmap = runWithCaptureWindowsHidden(
            // 窗口显隐必须在**主线程**执行：View 的 visibility/alpha 以及
            // WindowManager.updateViewLayout 都绑定在主线程的 ViewRootImpl 上；
            // 从截图协程（后台线程）直接操作会抛 CalledFromWrongThreadException，
            // 导致隐藏中途异常退出、恢复被跳过、悬浮球与迷你窗永久不可见。
            // 用 Main.immediate 且 await 完成（不是异步 post），保证「隐藏真正完成后」才取帧。
            hide = {
                withContext(Dispatchers.Main.immediate) {
                    CaptureUiGate.notifyCaptureStart()
                    // 隐藏真正完成（窗口已 GONE 且窗口级 alpha=0）之后才取新鲜度基准。
                    hideCompletedNanos = System.nanoTime()
                }
            },
            restore = { withContext(Dispatchers.Main.immediate) { CaptureUiGate.notifyCaptureEnd() } },
            capture = { backend(method).captureLatestBitmap(context, hideCompletedNanos) },
            // 恢复失败只上报，不掩盖原始异常。
            onRestoreFailure = {
                AppDebugLogStore.e(captureGateLogTag, "restore Hanako windows failed", it)
            }
        )
        AppDebugLogStore.i(
            captureGateLogTag,
            "Hanako capture occluding backend=$method minTs=${hideCompletedNanos ?: "-"} " +
                "bitmap=${bitmap.width}x${bitmap.height} thread=${Thread.currentThread().name}"
        )
        return bitmap
    }

    /**
     * 把 [regions]（位图坐标）涂黑，阻止 OCR 把 Hanako 自身内容误识别成题目。
     *
     * 位图复制失败或绘制失败时抛 [HanakoMaskException]（`copy_failed` / `mask_failed`），
     * **绝不**返回未遮罩位图。
     */
    private fun maskBitmapRegions(bitmap: Bitmap, regions: List<WindowRect>): Bitmap =
        maskRegionsInto(
            bitmap = bitmap,
            regions = regions,
            isMutable = Bitmap::isMutable,
            copyMutable = { it.copy(Bitmap.Config.ARGB_8888, true) },
            drawRegions = ::drawBlackRegions
        )

    /** 用不透明黑色填充 [regions]（位图坐标）。 */
    private fun drawBlackRegions(bitmap: Bitmap, regions: List<WindowRect>) {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        regions.forEach { r ->
            canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), paint)
        }
    }

    fun hasActiveSession(method: ScreenCaptureMethod): Boolean = backend(method).hasActiveSession()

    fun hasEverEstablishedSession(method: ScreenCaptureMethod): Boolean =
        backend(method).hasEverEstablishedSession()

    fun stop(context: Context, method: ScreenCaptureMethod) {
        backend(method).stop(context)
    }

    private fun backend(method: ScreenCaptureMethod): ScreenCaptureBackend {
        return backends.getValue(method)
    }
}

internal object MediaProjectionCaptureBackend : ScreenCaptureBackend {
    override val method: ScreenCaptureMethod = ScreenCaptureMethod.MEDIA_PROJECTION
    override val sessionActive: StateFlow<Boolean> = ProjectionSessionManager.sessionActive

    override fun hasActiveSession(): Boolean = ProjectionSessionManager.hasActiveSession()

    override fun hasEverEstablishedSession(): Boolean = ProjectionSessionManager.hasEverEstablished()

    override fun requestStart(context: Context, launchMode: OverlayLaunchMode): ScreenCaptureStartResult {
        context.startActivity(
            Intent(context, ProjectionPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ProjectionPermissionActivity.EXTRA_LAUNCH_MODE, launchMode.name)
            }
        )
        return ScreenCaptureStartResult.Started
    }

    override suspend fun captureLatestBitmap(context: Context, minFrameTimestampNanos: Long?): Bitmap {
        // minTs 非空时只接受隐藏完成后产出的新帧，旧帧（仍含浮窗）会被丢弃 / 超时失败。
        return ProjectionSessionManager.captureLatestBitmap(minFrameTimestampNanos)
    }

    override fun stop(context: Context) {
        context.stopService(
            Intent(context, MediaProjectionForegroundService::class.java).apply {
                action = MediaProjectionForegroundService.ACTION_STOP
            }
        )
    }
}

internal object ShizukuCaptureBackend : ScreenCaptureBackend {
    override val method: ScreenCaptureMethod = ScreenCaptureMethod.SHIZUKU_ADB
    override val sessionActive: StateFlow<Boolean> = ShizukuAuthorizationManager.authorized

    override fun hasActiveSession(): Boolean = ShizukuAuthorizationManager.authorized.value

    override fun hasEverEstablishedSession(): Boolean = ShizukuAuthorizationManager.authorized.value

    override fun requestStart(context: Context, launchMode: OverlayLaunchMode): ScreenCaptureStartResult {
        if (!Shizuku.pingBinder()) {
            return ScreenCaptureStartResult.Failed("Shizuku 未运行，请先启动 Shizuku 服务。")
        }
        context.startActivity(
            Intent(context, ShizukuPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ProjectionPermissionActivity.EXTRA_LAUNCH_MODE, launchMode.name)
            }
        )
        return ScreenCaptureStartResult.Started
    }

    override suspend fun captureLatestBitmap(context: Context, minFrameTimestampNanos: Long?): Bitmap {
        if (!Shizuku.pingBinder()) {
            error("Shizuku 未运行，请先启动 Shizuku 服务。")
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ShizukuAuthorizationManager.setAuthorized(false)
            error("Shizuku 尚未授权，请重新启动悬浮球完成授权。")
        }
        ShizukuAuthorizationManager.setAuthorized(true)
        // shell screencap 在调用时刻现取现拍，天然是「隐藏之后」的画面，无需也不支持时间戳过滤；
        // 因此忽略 minFrameTimestampNanos（诊断日志仍由调用方记录 minTs 实际值）。
        return ShellScreencap.captureBitmap(ShizukuShellScreencap)
    }

    override fun stop(context: Context) {
        ShizukuUserServiceClient.invalidate()
    }
}
