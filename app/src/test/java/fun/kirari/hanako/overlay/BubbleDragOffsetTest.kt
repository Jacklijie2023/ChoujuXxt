package `fun`.kirari.hanako.overlay

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球拖动方向换算的纯逻辑单元测试。
 *
 * 生产接线：悬浮球窗口 params 为 `Gravity.END or Gravity.BOTTOM`（见 `OverlayService.showBubble`），
 * 因此 params.x/y 从屏幕右/下边缘量起，拖动必须经 [windowOffsetDeltaFor] 取反，否则方向反转。
 *
 * 说明（JVM 与真机边界）：本测试只校验「屏幕位移 → LayoutParams 偏移」的符号换算；
 * 真机上 `WindowManager.updateViewLayout` 的实际落点仍需真机验收。
 */
class BubbleDragOffsetTest {

    @Test
    fun productionBubbleGravityIsAnchoredToEndAndBottom() {
        val bubbleGravity = Gravity.END or Gravity.BOTTOM
        assertTrue("悬浮球使用 END|BOTTOM，横向应锚定屏幕右边缘", isAnchoredToScreenEnd(bubbleGravity))
        assertTrue("悬浮球使用 END|BOTTOM，纵向应锚定屏幕底边", isAnchoredToScreenBottom(bubbleGravity))
    }

    @Test
    fun startAndTopGravityIsNotInverted() {
        val gravity = Gravity.START or Gravity.TOP
        assertFalse("START 不应被判为右锚点", isAnchoredToScreenEnd(gravity))
        assertFalse("TOP 不应被判为底锚点", isAnchoredToScreenBottom(gravity))
    }

    @Test
    fun leftGravityIsNotMistakenForRightAnchor() {
        // LEFT(0x03) 与 RIGHT(0x05) 掩码后不同，必须严格区分，避免把左锚点当成右锚点。
        assertFalse(isAnchoredToScreenEnd(Gravity.LEFT or Gravity.TOP))
        assertTrue(isAnchoredToScreenEnd(Gravity.RIGHT or Gravity.TOP))
    }

    @Test
    fun explicitRightAndEndBothCountAsEndAnchor() {
        assertTrue(isAnchoredToScreenEnd(Gravity.RIGHT))
        assertTrue(isAnchoredToScreenEnd(Gravity.END))
        assertTrue(isAnchoredToScreenBottom(Gravity.BOTTOM))
    }

    @Test
    fun dragRightMovesWindowRightWhenAnchoredToScreenEnd() {
        // 生产悬浮球：END|BOTTOM。用户向右拖（屏幕 dx>0）→ params.x 应减小（窗口向右移动）。
        val delta = windowOffsetDeltaFor(screenDx = 40, screenDy = 0, anchoredToEnd = true, anchoredToBottom = true)
        assertEquals("向右拖时 params.x 必须减小，窗口才向右移动", -40, delta.x)
    }

    @Test
    fun dragLeftMovesWindowLeftWhenAnchoredToScreenEnd() {
        val delta = windowOffsetDeltaFor(screenDx = -40, screenDy = 0, anchoredToEnd = true, anchoredToBottom = true)
        assertEquals("向左拖时 params.x 必须增大，窗口才向左移动", 40, delta.x)
    }

    @Test
    fun dragDownMovesWindowDownWhenAnchoredToScreenBottom() {
        val delta = windowOffsetDeltaFor(screenDx = 0, screenDy = 30, anchoredToEnd = true, anchoredToBottom = true)
        assertEquals("向下拖时 params.y 必须减小，窗口才向下移动", -30, delta.y)
    }

    @Test
    fun dragUpMovesWindowUpWhenAnchoredToScreenBottom() {
        val delta = windowOffsetDeltaFor(screenDx = 0, screenDy = -30, anchoredToEnd = true, anchoredToBottom = true)
        assertEquals("向上拖时 params.y 必须增大，窗口才向上移动", 30, delta.y)
    }

    @Test
    fun startAnchoredWindowKeepsPositiveDirection() {
        // 非反锚点（START|TOP）保持原方向：向右/向下拖即 params.x/y 增大。
        val delta = windowOffsetDeltaFor(screenDx = 40, screenDy = 30, anchoredToEnd = false, anchoredToBottom = false)
        assertEquals(40, delta.x)
        assertEquals(30, delta.y)
    }

    @Test
    fun zeroScreenDeltaKeepsStartPositionUnchanged() {
        val delta = windowOffsetDeltaFor(screenDx = 0, screenDy = 0, anchoredToEnd = true, anchoredToBottom = true)
        assertEquals("按下后未移动时起点不得改变", 0, delta.x)
        assertEquals("按下后未移动时起点不得改变", 0, delta.y)
    }
}
