package `fun`.kirari.hanako.capture

import kotlin.math.roundToInt

/** 屏幕坐标系下的矩形（左 / 上 / 右 / 下，单位 px；right/bottom 取开区间）。 */
internal data class WindowRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top
}

/**
 * 一个待遮罩窗口：屏幕坐标矩形 + 是否仍附着。
 *
 * **未附着的窗口不参与遮罩**：其矩形可能陈旧或与真实内容不符，用它遮罩会误伤真实题目区域。
 */
internal data class MaskWindow(
    val rect: WindowRect,
    val attached: Boolean
)

/** 把矩形夹取到 `[0,screenWidth] x [0,screenHeight]`；完全越界或退化为空则返回 null。 */
internal fun WindowRect.clampToScreen(screenWidth: Int, screenHeight: Int): WindowRect? {
    if (screenWidth <= 0 || screenHeight <= 0) return null
    val clamped = WindowRect(
        left.coerceIn(0, screenWidth),
        top.coerceIn(0, screenHeight),
        right.coerceIn(0, screenWidth),
        bottom.coerceIn(0, screenHeight)
    )
    return clamped.takeIf { !it.isEmpty }
}

/**
 * 计算需要在截图位图中遮罩的 Hanako 自身窗口区域（屏幕坐标）。
 *
 * 规则：只保留**已附着**且夹取后仍非空的窗口矩形。窗口保持可见时，其覆盖区域在截图里已被窗口本身
 * 遮挡，遮罩这块区域既不会丢失额外可见内容（窗口下方的题目本就不可见），又能阻止 OCR 把 Hanako
 * 自身内容误识别成题目。被窗口遮住的题目/选项将由无障碍文本补足（见报告说明）。
 */
internal fun hanakoMaskRegions(
    windows: List<MaskWindow>,
    screenWidth: Int,
    screenHeight: Int
): List<WindowRect> = windows
    .filter { it.attached }
    .mapNotNull { it.rect.clampToScreen(screenWidth, screenHeight) }

/**
 * 当位图尺寸与屏幕尺寸不一致（低分辨率虚拟显示 / 缩放）时，把屏幕坐标矩形等比缩放到**位图**坐标。
 *
 * 尺寸一致（MediaProjection 以显示尺寸镜像、Shizuku screencap 取全屏）时直接返回原矩形，
 * 保证坐标系无额外误差。
 */
internal fun scaleRegionsToBitmap(
    regions: List<WindowRect>,
    screenWidth: Int,
    screenHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int
): List<WindowRect> {
    if (screenWidth <= 0 || screenHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return emptyList()
    if (screenWidth == bitmapWidth && screenHeight == bitmapHeight) {
        return regions.mapNotNull { it.clampToScreen(bitmapWidth, bitmapHeight) }
    }
    val sx = bitmapWidth.toFloat() / screenWidth
    val sy = bitmapHeight.toFloat() / screenHeight
    return regions.mapNotNull { r ->
        WindowRect(
            (r.left * sx).roundToInt(),
            (r.top * sy).roundToInt(),
            (r.right * sx).roundToInt(),
            (r.bottom * sy).roundToInt()
        ).clampToScreen(bitmapWidth, bitmapHeight)
    }
}

/**
 * 遮罩决策。区分「无需遮罩」「已获得有效区域」「预期需遮罩但取不到区域」三种情形，
 * 避免把正常的「无窗口」误判为故障，也避免把「取不到区域」静默当成成功。
 */
internal enum class MaskDecision {
    /** 已获得有效遮罩区域，应执行位图遮罩。 */
    MASK,

    /** 已确认没有任何**已附着**的 Hanako 窗口需要遮罩：原图可安全使用（不算故障）。 */
    NO_WINDOWS,

    /** 存在已附着窗口，但夹取/缩放后得不到任何有效区域：**不安全**，本次截图不得进入 OCR。 */
    NO_REGIONS
}

/** [planHanakoMask] 的纯计算结果（不含任何 Android 类型，可直接单元测试）。 */
internal data class MaskPlan(
    val decision: MaskDecision,
    val regions: List<WindowRect>,
    val windowCount: Int,
    val attachedCount: Int
)

/**
 * 计算本次截图的遮罩计划。
 *
 * 坐标系约定（关键）：
 * - [screenWidth]/[screenHeight] 是窗口矩形（`View.getLocationOnScreen`）与整屏截图**共同**的
 *   整屏坐标基准，即 `Display.getRealMetrics()`；**不得**使用 `resources.displayMetrics`
 *   （那是应用可用区，通常不含状态栏/导航栏，会把靠近底部的矩形错误裁短并引入多余缩放）。
 * - [bitmapWidth]/[bitmapHeight] 是最终截图位图尺寸；仅当它与整屏基准不一致时才做等比缩放。
 */
internal fun planHanakoMask(
    windows: List<MaskWindow>,
    screenWidth: Int,
    screenHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int
): MaskPlan {
    val attachedCount = windows.count { it.attached }
    val regions = scaleRegionsToBitmap(
        hanakoMaskRegions(windows, screenWidth, screenHeight),
        screenWidth,
        screenHeight,
        bitmapWidth,
        bitmapHeight
    )
    val decision = when {
        attachedCount == 0 -> MaskDecision.NO_WINDOWS
        regions.isEmpty() -> MaskDecision.NO_REGIONS
        else -> MaskDecision.MASK
    }
    return MaskPlan(
        decision = decision,
        regions = regions,
        windowCount = windows.size,
        attachedCount = attachedCount
    )
}

/** 遮罩失败原因（[logName] 固定，便于真机按关键字筛选）。 */
internal enum class MaskFailureReason(val logName: String) {
    /** 存在已附着窗口却得不到有效区域。 */
    NO_REGIONS("no_regions"),

    /** 位图不可变、复制为可变位图失败。 */
    COPY_FAILED("copy_failed"),

    /** 绘制遮罩过程失败。 */
    MASK_FAILED("mask_failed")
}

/**
 * 遮罩无法安全完成时抛出。
 *
 * 调用方**必须**按「本次截图失败」处理：不得把未遮罩的原始位图继续送入 OCR。
 */
internal class HanakoMaskException(
    val reason: MaskFailureReason,
    detail: String,
    cause: Throwable? = null
) : IllegalStateException("Hanako 遮罩失败（${reason.logName}）：$detail", cause)

/**
 * 把 [regions] 涂黑到 [bitmap] 上的通用实现。
 *
 * 与 Android `Bitmap`/`Canvas` 解耦（位图类型、可变性判定、复制、绘制全部以函数注入），
 * 因此可在纯 JVM 单元测试中验证「复制失败 / 绘制失败 / 成功」三条分支。
 *
 * 任一失败分支都**抛出** [HanakoMaskException]，绝不返回未遮罩位图。
 */
internal fun <B : Any> maskRegionsInto(
    bitmap: B,
    regions: List<WindowRect>,
    isMutable: (B) -> Boolean,
    copyMutable: (B) -> B?,
    drawRegions: (B, List<WindowRect>) -> Unit
): B {
    val target = if (isMutable(bitmap)) {
        bitmap
    } else {
        copyMutable(bitmap)
            ?: throw HanakoMaskException(MaskFailureReason.COPY_FAILED, "位图不可变且复制失败")
    }
    try {
        drawRegions(target, regions)
    } catch (t: Throwable) {
        throw HanakoMaskException(
            MaskFailureReason.MASK_FAILED,
            t.message ?: t.javaClass.simpleName,
            t
        )
    }
    return target
}

/** 采集时的 Hanako 界面策略。 */
internal enum class HanakoCaptureUiStrategy {
    /** 保持窗口可见，改用位图遮罩排除 Hanako 自身区域（默认，避免闪烁）。 */
    MASK_WINDOWS,

    /** 回退：截图期间隐藏窗口、截图后恢复（既有安全路径，仅在无遮罩提供者时使用）。 */
    HIDE_WINDOWS
}

/**
 * 选择采集界面策略。
 *
 * 关键判据：**位图遮罩无法还原被遮挡的目标页面**。窗口保持可见时，它覆盖的那块区域在截图里
 * 本就被窗口挡着，遮罩只是「把已经不可见的区域涂黑」；用户提问触发的采集必须拿到**未被遮挡的
 * 真实页面**，因此 [occludingCaptureRequired] 为 true 且隐藏能力可用时，一律走
 * [HanakoCaptureUiStrategy.HIDE_WINDOWS]（真隐藏窗口 + 等待新帧 + 结束时恢复）。
 *
 * - [occludingCaptureRequired]：本次采集是否由用户请求强制触发（见
 *   `ScreenCaptureManager.captureLatestBitmap`）。后台低频探测保持 false，避免反复隐藏窗口造成闪烁。
 * - 无隐藏能力（OverlayService 未注册 start/end）时降级为位图遮罩，并在诊断日志里体现，绝不
 *   静默把被涂黑的区域当成已读取到的目标页面。
 * - 其余情形维持既有行为：有遮罩提供者优先 [HanakoCaptureUiStrategy.MASK_WINDOWS]，否则隐藏回退。
 */
internal fun chooseCaptureUiStrategy(
    maskProviderAvailable: Boolean,
    occludingCaptureRequired: Boolean = false,
    hideProviderAvailable: Boolean = false
): HanakoCaptureUiStrategy = when {
    occludingCaptureRequired && hideProviderAvailable -> HanakoCaptureUiStrategy.HIDE_WINDOWS
    maskProviderAvailable -> HanakoCaptureUiStrategy.MASK_WINDOWS
    else -> HanakoCaptureUiStrategy.HIDE_WINDOWS
}
