package `fun`.kirari.hanako.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 面板内容区的下拉拖拽拦截宿主。
 *
 * 与顶部把手一致：使用 [MotionEvent.getRawY]（屏幕绝对坐标，零漂移）驱动面板高度变化，
 * 避免 Compose 局部坐标随窗口高度异步更新产生的反馈回路抖动，保证面板 1:1 跟随手指。
 *
 * 仅当内容区滚动到顶部（[contentAtTop] = true）且手指向下移动时才拦截触摸，
 * 交给 [onTouchEvent] 处理（下拉收缩 / 上滑展开）；否则放行，内容区可正常上下滚动。
 */
class PanelDragInterceptFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 内容区是否已滚动到顶部：为 true 时才允许拦截下拉手势（CROP/WEBVIEW 模式默认 false，不拦截）。 */
    var contentAtTop: Boolean = false
        set(value) {
            field = value
            if (!value) intercepted = false
        }

    /** 下拉跟随回调（deltaY > 0 为下拉），由 OverlayService.dragPanelBy 驱动窗口高度。 */
    var onDrag: ((Float) -> Unit)? = null

    /** 松手吸附回调（超过面板高度约 1/3 关闭，否则回弹全展开），由 OverlayService.settlePanel 处理。 */
    var onSettle: (() -> Unit)? = null

    private var lastRawY = 0f
    private var intercepted = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawY = ev.rawY
                intercepted = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!intercepted && contentAtTop && ev.rawY > lastRawY) {
                    intercepted = true
                    return true
                }
                if (!intercepted) lastRawY = ev.rawY
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (intercepted) {
                    onDrag?.invoke(ev.rawY - lastRawY)
                    lastRawY = ev.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (intercepted) {
                    intercepted = false
                    onSettle?.invoke()
                }
            }
        }
        return intercepted || super.onTouchEvent(ev)
    }
}
