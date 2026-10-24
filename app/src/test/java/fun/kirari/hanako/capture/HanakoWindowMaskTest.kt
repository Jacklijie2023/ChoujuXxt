package `fun`.kirari.hanako.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图遮罩区域计算的纯逻辑单元测试。
 *
 * 覆盖：屏幕内夹取、越界丢弃、未附着窗口不参与、位图缩放、采集策略选择。
 * 真机上「遮罩矩形是否精确覆盖 Hanako 窗口」仍需真机验收。
 */
class HanakoWindowMaskTest {

    @Test
    fun maskRegionsKeepAttachedInScreenRects() {
        val regions = hanakoMaskRegions(
            windows = listOf(
                MaskWindow(WindowRect(0, 0, 100, 200), attached = true),
                MaskWindow(WindowRect(900, 1800, 1080, 1920), attached = true)
            ),
            screenWidth = 1080,
            screenHeight = 1920
        )
        assertEquals(2, regions.size)
        assertEquals(WindowRect(0, 0, 100, 200), regions[0])
        assertEquals(WindowRect(900, 1800, 1080, 1920), regions[1])
    }

    @Test
    fun detachedWindowIsExcludedFromMask() {
        val regions = hanakoMaskRegions(
            windows = listOf(
                MaskWindow(WindowRect(0, 0, 100, 100), attached = false),
                MaskWindow(WindowRect(200, 200, 300, 300), attached = true)
            ),
            screenWidth = 1080,
            screenHeight = 1920
        )
        assertEquals("未附着窗口不得参与遮罩（矩形可能陈旧）", 1, regions.size)
        assertEquals(WindowRect(200, 200, 300, 300), regions.single())
    }

    @Test
    fun outOfScreenRectIsClampedToScreen() {
        val clamped = WindowRect(1000, 1900, 1200, 2000).clampToScreen(1080, 1920)
        assertEquals(WindowRect(1000, 1900, 1080, 1920), clamped)
    }

    @Test
    fun fullyOutOfScreenRectBecomesNull() {
        assertNull(WindowRect(1200, 0, 1300, 100).clampToScreen(1080, 1920))
    }

    @Test
    fun degenerateRectBecomesNull() {
        assertNull(WindowRect(10, 10, 10, 100).clampToScreen(1080, 1920))
        assertNull(WindowRect(0, 0, 0, 0).clampToScreen(1080, 1920))
    }

    @Test
    fun maskRegionsDropsOutOfScreenAndDegenerateWindows() {
        val regions = hanakoMaskRegions(
            windows = listOf(
                MaskWindow(WindowRect(1200, 0, 1300, 100), attached = true), // 完全越界
                MaskWindow(WindowRect(5, 5, 5, 5), attached = true)          // 退化
            ),
            screenWidth = 1080,
            screenHeight = 1920
        )
        assertTrue("越界/退化矩形不得产生遮罩区域", regions.isEmpty())
    }

    @Test
    fun scaleIsIdentityWhenBitmapMatchesScreen() {
        val original = listOf(WindowRect(10, 20, 110, 220))
        val scaled = scaleRegionsToBitmap(original, 1080, 1920, 1080, 1920)
        assertEquals(original, scaled)
    }

    @Test
    fun scaleMapsScreenRectsToSmallerBitmap() {
        // 屏幕 1080x1920 的窗口 (540,960)-(1080,1920)，映射到 540x960 位图 → (270,480)-(540,960)。
        val scaled = scaleRegionsToBitmap(listOf(WindowRect(540, 960, 1080, 1920)), 1080, 1920, 540, 960)
        assertEquals(WindowRect(270, 480, 540, 960), scaled.single())
    }

    @Test
    fun scaleReturnsEmptyForInvalidDimensions() {
        assertTrue(scaleRegionsToBitmap(listOf(WindowRect(0, 0, 10, 10)), 0, 1920, 1080, 1920).isEmpty())
        assertTrue(scaleRegionsToBitmap(listOf(WindowRect(0, 0, 10, 10)), 1080, 1920, 0, 0).isEmpty())
    }

    @Test
    fun maskStrategyIsChosenWhenProviderIsAvailable() {
        assertEquals(HanakoCaptureUiStrategy.MASK_WINDOWS, chooseCaptureUiStrategy(maskProviderAvailable = true))
    }

    @Test
    fun hideStrategyIsFallbackWhenProviderUnavailable() {
        assertEquals(HanakoCaptureUiStrategy.HIDE_WINDOWS, chooseCaptureUiStrategy(maskProviderAvailable = false))
    }

    // ---- R8.5：用户请求级「强制采集」必须真正隐藏窗口，而不是只把被覆盖区域涂黑 ----

    @Test
    fun occludingCaptureUsesHideStrategyEvenWhenMaskProviderAvailable() {
        // 真机根因：位图遮罩只能把被浮窗覆盖的区域涂黑，无法还原其背后的目标题目页面。
        // 用户提问触发的强制采集必须在遮罩能力可用时也走「真正隐藏 → 取新帧 → 恢复」。
        assertEquals(
            HanakoCaptureUiStrategy.HIDE_WINDOWS,
            chooseCaptureUiStrategy(
                maskProviderAvailable = true,
                occludingCaptureRequired = true,
                hideProviderAvailable = true
            )
        )
    }

    @Test
    fun occludingCaptureDegradesToMaskWhenHideUnavailable() {
        // 无隐藏能力（OverlayService 未注册 start/end）时安全降级为遮罩：不因「强制采集」而崩，
        // 但调用侧会据实际策略在诊断日志中体现「只涂黑、未还原」。
        assertEquals(
            HanakoCaptureUiStrategy.MASK_WINDOWS,
            chooseCaptureUiStrategy(
                maskProviderAvailable = true,
                occludingCaptureRequired = true,
                hideProviderAvailable = false
            )
        )
    }

    @Test
    fun backgroundProbeKeepsMaskStrategyToAvoidFlicker() {
        // 后台低频探测不得触发窗口隐藏（避免反复闪烁）：occludingCaptureRequired=false 维持遮罩。
        assertEquals(
            HanakoCaptureUiStrategy.MASK_WINDOWS,
            chooseCaptureUiStrategy(
                maskProviderAvailable = true,
                occludingCaptureRequired = false,
                hideProviderAvailable = true
            )
        )
    }

    // ---- R8.1：遮罩坐标基准（整屏尺寸，而非 resources.displayMetrics）与失败安全 ----

    /** 屏幕基准 == 位图尺寸时，矩形不发生多余缩放（覆盖 MediaProjection / Shizuku 两条后端产帧情况）。 */
    @Test
    fun planIsIdentityForBothBackendsWhenBitmapMatchesFullScreen() {
        // MediaProjection（虚拟显示按 getRealMetrics 建）与 Shizuku（screencap 取整屏）都以整屏尺寸产帧。
        listOf(1080 to 2400, 1440 to 3120).forEach { (w, h) ->
            val plan = planHanakoMask(
                windows = listOf(MaskWindow(WindowRect(10, 700, 200, 900), attached = true)),
                screenWidth = w,
                screenHeight = h,
                bitmapWidth = w,
                bitmapHeight = h
            )
            assertEquals("整屏基准 == 位图时应恒等", MaskDecision.MASK, plan.decision)
            assertEquals(WindowRect(10, 700, 200, 900), plan.regions.single())
        }
    }

    /**
     * `resources.displayMetrics`（应用可用区，示例高 2274）与整屏位图（2400）不同，
     * 但窗口矩形用的是**整屏坐标**：只要传入正确的整屏基准，结果仍无多余缩放、无错误裁短。
     */
    @Test
    fun planUsesFullScreenBasisNotAppDisplayMetrics() {
        // 整屏基准仍取 2400（getRealMetrics），位图也是 2400 → 恒等，与 2274 这一错误基准无关。
        val plan = planHanakoMask(
            windows = listOf(MaskWindow(WindowRect(0, 2200, 300, 2399), attached = true)),
            screenWidth = 1080,
            screenHeight = 2400,
            bitmapWidth = 1080,
            bitmapHeight = 2400
        )
        assertEquals(WindowRect(0, 2200, 300, 2399), plan.regions.single())
    }

    /** 靠近屏幕底部的窗口（bottom 接近整屏高度）不得因偏小的基准被裁短。 */
    @Test
    fun bottomAnchoredRectIsNotClippedAgainstFullScreenHeight() {
        val plan = planHanakoMask(
            windows = listOf(MaskWindow(WindowRect(0, 2300, 120, 2399), attached = true)),
            screenWidth = 1080,
            screenHeight = 2400,
            bitmapWidth = 1080,
            bitmapHeight = 2400
        )
        assertEquals(WindowRect(0, 2300, 120, 2399), plan.regions.single())
    }

    /** 整屏基准 > 位图（低分辨率产帧）时，按等比正确缩放。 */
    @Test
    fun planScalesFromFullScreenToSmallerBitmap() {
        val plan = planHanakoMask(
            windows = listOf(MaskWindow(WindowRect(540, 1200, 1080, 2400), attached = true)),
            screenWidth = 1080,
            screenHeight = 2400,
            bitmapWidth = 540,
            bitmapHeight = 1200
        )
        assertEquals(WindowRect(270, 600, 540, 1200), plan.regions.single())
    }

    /** 正常路径：有已附着窗口且落在屏内 → MASK，区域正确。 */
    @Test
    fun planMasksWhenAttachedWindowIsOnScreen() {
        val plan = planHanakoMask(
            windows = listOf(
                MaskWindow(WindowRect(0, 0, 100, 200), attached = true),
                MaskWindow(WindowRect(0, 0, 0, 0), attached = false)
            ),
            screenWidth = 1080,
            screenHeight = 1920,
            bitmapWidth = 1080,
            bitmapHeight = 1920
        )
        assertEquals(MaskDecision.MASK, plan.decision)
        assertEquals(1, plan.attachedCount)
        assertEquals(WindowRect(0, 0, 100, 200), plan.regions.single())
    }

    /** 正常路径：确认无任何已附着窗口 → NO_WINDOWS（不算故障，原图可安全使用）。 */
    @Test
    fun planReportsNoWindowsWhenNothingAttached() {
        val plan = planHanakoMask(
            windows = listOf(
                MaskWindow(WindowRect(0, 0, 100, 200), attached = false),
                MaskWindow(WindowRect(0, 0, 0, 0), attached = false)
            ),
            screenWidth = 1080,
            screenHeight = 1920,
            bitmapWidth = 1080,
            bitmapHeight = 1920
        )
        assertEquals(MaskDecision.NO_WINDOWS, plan.decision)
        assertEquals(0, plan.attachedCount)
        assertTrue(plan.regions.isEmpty())
    }

    /** 异常路径：有已附着窗口但全部越界/退化，取不到有效区域 → NO_REGIONS（不安全）。 */
    @Test
    fun planReportsNoRegionsWhenAttachedButNoValidRegion() {
        val plan = planHanakoMask(
            windows = listOf(
                MaskWindow(WindowRect(1200, 0, 1300, 100), attached = true), // 完全越界
                MaskWindow(WindowRect(5, 5, 5, 5), attached = true)          // 退化
            ),
            screenWidth = 1080,
            screenHeight = 1920,
            bitmapWidth = 1080,
            bitmapHeight = 1920
        )
        assertEquals(MaskDecision.NO_REGIONS, plan.decision)
        assertEquals(2, plan.attachedCount)
        assertTrue(plan.regions.isEmpty())
    }

    /** 正常遮罩应用：可变形位图原地涂黑，区域原样交给绘制。 */
    @Test
    fun maskRegionsIntoSucceedsWhenMutable() {
        val drawn = mutableListOf<List<WindowRect>>()
        val out = maskRegionsInto(
            bitmap = "bitmap",
            regions = listOf(WindowRect(1, 2, 3, 4)),
            isMutable = { true },
            copyMutable = { error("可变位图不应触发复制") },
            drawRegions = { _, rs -> drawn += rs }
        )
        assertEquals("bitmap", out)
        assertEquals(listOf(WindowRect(1, 2, 3, 4)), drawn.single())
    }

    /** 不可变且复制失败 → COPY_FAILED，抛异常（绝不返回未遮罩位图）。 */
    @Test
    fun maskRegionsIntoThrowsCopyFailedWhenCopyReturnsNull() {
        val error = assertThrows(HanakoMaskException::class.java) {
            maskRegionsInto(
                bitmap = "bitmap",
                regions = listOf(WindowRect(1, 2, 3, 4)),
                isMutable = { false },
                copyMutable = { null },
                drawRegions = { _, _ -> }
            )
        }
        assertEquals(MaskFailureReason.COPY_FAILED, error.reason)
        assertEquals("copy_failed", error.reason.logName)
    }

    /** 绘制阶段抛异常 → MASK_FAILED，抛异常（绝不静默返回）。 */
    @Test
    fun maskRegionsIntoThrowsMaskFailedWhenDrawingThrows() {
        val error = assertThrows(HanakoMaskException::class.java) {
            maskRegionsInto(
                bitmap = "bitmap",
                regions = listOf(WindowRect(1, 2, 3, 4)),
                isMutable = { true },
                copyMutable = { it },
                drawRegions = { _, _ -> error("draw boom") }
            )
        }
        assertEquals(MaskFailureReason.MASK_FAILED, error.reason)
        assertEquals("mask_failed", error.reason.logName)
    }
}
