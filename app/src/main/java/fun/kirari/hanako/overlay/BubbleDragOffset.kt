package `fun`.kirari.hanako.overlay

import android.view.Gravity

/**
 * 悬浮球拖动换算结果：`WindowManager.LayoutParams` 的 x/y 偏移增量（相对按下时的起点）。
 */
internal data class WindowOffsetDelta(val x: Int, val y: Int)

/**
 * 横向锚点是否为屏幕「右 / END」。
 *
 * Android gravity 语义：`Gravity.END`（0x00800005）与 `Gravity.RIGHT`（0x00000005）经
 * [Gravity.HORIZONTAL_GRAVITY_MASK] 掩码后同为 `Gravity.RIGHT`，故一次掩码比较即可同时覆盖
 * END 与 RIGHT，且不会把 `Gravity.LEFT`（0x00000003，掩码后为 LEFT）误判为右锚点。
 */
internal fun isAnchoredToScreenEnd(gravity: Int): Boolean =
    (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT

/**
 * 纵向锚点是否为屏幕「底边 / BOTTOM」。
 *
 * `Gravity.BOTTOM`（0x00000050）经 [Gravity.VERTICAL_GRAVITY_MASK] 掩码后即为 BOTTOM；
 * `Gravity.TOP`（0x00000030）掩码后为 TOP，不会误判。
 */
internal fun isAnchoredToScreenBottom(gravity: Int): Boolean =
    (gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM

/**
 * 把「屏幕绝对坐标位移」换算为 `LayoutParams.x/y` 的偏移增量。
 *
 * 依据 Android `WindowManager` + gravity 的真实坐标系：
 * - 横向锚点为 END/RIGHT 时，`params.x` 是「窗口边缘到屏幕**右**边缘」的距离，x 增大窗口**左移**
 *   ⇒ 位移取反；
 * - 纵向锚点为 BOTTOM 时，`params.y` 是「窗口边缘到屏幕**下**边缘」的距离，y 增大窗口**上移**
 *   ⇒ 位移取反；
 * - 锚点为 START/LEFT 或 TOP 时保持正向。
 *
 * 本函数是「屏幕位移 → 窗口偏移」的**唯一**换算点，调用处只做 `startX + delta.x`，
 * 不再对符号做第二次反转，因此方向不会重复翻转。
 */
internal fun windowOffsetDeltaFor(
    screenDx: Int,
    screenDy: Int,
    anchoredToEnd: Boolean,
    anchoredToBottom: Boolean
): WindowOffsetDelta = WindowOffsetDelta(
    x = if (anchoredToEnd) -screenDx else screenDx,
    y = if (anchoredToBottom) -screenDy else screenDy
)
