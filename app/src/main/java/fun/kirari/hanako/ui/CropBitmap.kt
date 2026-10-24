package `fun`.kirari.hanako.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * 把预览区里的拖拽选择换算成**原图坐标系**下的裁剪矩形。
 *
 * 返回 null 表示「没有有效选择」（未拖动或画布尺寸未知），调用方应使用整张原图。
 */
fun cropRectOf(
    sourceWidth: Int,
    sourceHeight: Int,
    start: Offset,
    end: Offset,
    canvasSize: IntSize
): Rect? {
    if (canvasSize.width == 0 || canvasSize.height == 0) return null
    if (start == end) return null
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    val canvasWidth = canvasSize.width.toFloat()
    val canvasHeight = canvasSize.height.toFloat()
    val scale = minOf(canvasWidth / sourceWidth.toFloat(), canvasHeight / sourceHeight.toFloat())
    val displayedWidth = sourceWidth * scale
    val displayedHeight = sourceHeight * scale
    val imageLeft = (canvasWidth - displayedWidth) / 2f
    val imageTop = (canvasHeight - displayedHeight) / 2f
    val imageRight = imageLeft + displayedWidth
    val imageBottom = imageTop + displayedHeight

    val left = minOf(start.x, end.x).coerceIn(imageLeft, imageRight)
    val top = minOf(start.y, end.y).coerceIn(imageTop, imageBottom)
    val right = maxOf(start.x, end.x).coerceIn(imageLeft, imageRight)
    val bottom = maxOf(start.y, end.y).coerceIn(imageTop, imageBottom)

    val cropLeft = ((left - imageLeft) / scale).toInt().coerceIn(0, sourceWidth - 1)
    val cropTop = ((top - imageTop) / scale).toInt().coerceIn(0, sourceHeight - 1)
    val cropWidth = ((right - left) / scale).toInt().coerceAtLeast(1).coerceAtMost(sourceWidth - cropLeft)
    val cropHeight = ((bottom - top) / scale).toInt().coerceAtLeast(1).coerceAtMost(sourceHeight - cropTop)
    if (cropWidth <= 1 || cropHeight <= 1) return null
    return Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)
}

fun cropBitmap(
    source: Bitmap,
    start: Offset,
    end: Offset,
    canvasSize: IntSize
): Bitmap {
    if (canvasSize.width == 0 || canvasSize.height == 0) return source
    if (start == end) return source
    val rect = cropRectOf(source.width, source.height, start, end, canvasSize) ?: return source
    return Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
}
