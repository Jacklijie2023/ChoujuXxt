package `fun`.kirari.hanako.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.automation.BubbleState
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.ProcessingResult

internal val SheetDockOffset = 88.dp
internal val PanelHandleYOffset = 38.dp
internal const val SheetAnimationDurationMs = 260

internal enum class OverlaySheetMode {
    CROP,
    RESULT,
    WEBVIEW
}

internal enum class OverlayLaunchMode {
    NORMAL,
    AUTO
}

internal enum class AutoRunState {
    IDLE,
    RUNNING,
    COMPLETED
}

internal data class LongPageSegment(
    /** 该屏在累计长图中的起始行（像素）。 */
    val offsetY: Int,
    /** 该屏自身的高度（像素）。 */
    val height: Int
)

internal data class OverlayUiState(
    val settings: AppSettings = AppSettings(),
    val screenshot: Bitmap? = null,
    val selectedBitmap: Bitmap? = null,
    val liveOcrText: String = "",
    val liveAnswerText: String = "",
    val result: ProcessingResult? = null,
    val error: String? = null,
    val working: Boolean = false,
    val sheetVisible: Boolean = false,
    val sheetMode: OverlaySheetMode = OverlaySheetMode.CROP,
    /** 手动长截图会话进行中：再次点击悬浮球会把新一屏追加到 [longScreenshotBitmap] 下方。 */
    val longCaptureActive: Boolean = false,
    /**
     * 已截图但**尚未拼接**的当前屏：面板里显示这一屏，等用户框选好区域后
     * 点「继续截图」/「确认处理」时再按这次框选裁剪并拼到 [longScreenshotBitmap] 下方。
     *
     * 这样第 2 屏、第 N 屏都能使用「自己这一次」的框选区域，而不是永远沿用第 1 屏的区域。
     */
    val longPagePending: Boolean = false,
    /** 长截图已拼接的屏数（含第一屏）。 */
    val longCapturePages: Int = 0,
    /**
     * 「原图」区域展示的累计拼接长图（第 1 屏在顶部，后续每屏依次接在下方）。
     *
     * 与 [screenshot]（当前这一屏的原始截图）分开保存：面板下方仍显示本次截图，
     * 上方可滚动的原图区域显示累计结果；「确认处理」发送的也是这张长图。
     */
    val longScreenshotBitmap: Bitmap? = null,
    /** 「原图」中每一屏在累计长图里的纵向区段：点击长图时按它判断用户想查看哪一屏。 */
    val longPageSegments: List<LongPageSegment> = emptyList(),
    /**
     * 长截图使用的裁剪区域（原图坐标）；null 表示未框选、使用整屏。
     *
     * 用户框选后，第 1 屏与后续每一屏都按同一区域裁剪再拼接，
     * 保证「原图」里只有用户选中的那条区域，而不是整张屏幕。
     */
    val longCaptureCropRect: Rect? = null,
    val launchMode: OverlayLaunchMode = OverlayLaunchMode.NORMAL,
    val autoRunState: AutoRunState = AutoRunState.IDLE,
    val autoCopiedLabel: String? = null,
    val bubbleState: BubbleState = BubbleState.Idle,
    val webViewUrl: String = "",
    /**
     * 迷你聊天小窗可见性。
     *
     * 独立于 [sheetVisible]/[sheetMode]（全屏底部抽屉），二者互斥，互不复用字段，
     * 避免迷你聊天与截图抽屉状态相互污染。
     */
    val miniChatVisible: Boolean = false
)


