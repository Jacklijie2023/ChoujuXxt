package `fun`.kirari.hanako.screencontext

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * 「本次实际送入本地 OCR 的截图」请求级内存快照（仅本机诊断用）。
 *
 * 为什么需要它：现有诊断只能给出字符数 / 行数 / 选项标记数 / 结果指纹，
 * 无法区分「悬浮窗进入了被 OCR 处理的截图」与「截图本身干净、但 OCR 引擎识别错乱」。
 * 只有直接看这张图才能区分，因此把真正交给 OCR 的 Bitmap 做一次等比缩放与 JPEG 压缩后
 * 按 traceId 保存在内存中。
 *
 * 硬性约束：
 * - 只存内存，不写入公共目录、不上传、不写 Logcat（不落盘、不联网、不打印像素）；
 * - 请求级绑定：按 traceId 取，取不到即明确说明「未保留」，绝不用当前屏幕或旧图冒充；
 * - 有界：最多保留 [maxEntries] 份（按登记顺序淘汰最早的一份），且只存压缩后的字节。
 */
internal class CaptureFrameStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxLongestSide: Int = DEFAULT_MAX_LONGEST_SIDE,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY
) {
    /** 一份快照：JPEG 字节 + 缩放后尺寸（尺寸仅用于界面标注）。 */
    internal class Snapshot(val jpeg: ByteArray, val width: Int, val height: Int)

    private val snapshots = LinkedHashMap<String, Snapshot>()
    private val lock = Any()

    /** 登记本次请求实际送入 OCR 的截图；[traceId] 为空或 [bitmap] 不可用时忽略。 */
    fun record(traceId: String?, bitmap: Bitmap?) {
        if (traceId.isNullOrBlank() || bitmap == null || bitmap.isRecycled) return
        val scaled = scaleForDiagnosis(bitmap, maxLongestSide)
        val scaledWidth = scaled.width
        val scaledHeight = scaled.height
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            out.toByteArray()
        }
        // 缩放产生的新图由本方法负责释放；原图（调用方所有）不动。
        if (scaled !== bitmap) scaled.recycle()
        if (bytes.isEmpty()) return
        synchronized(lock) {
            snapshots.remove(traceId)
            snapshots[traceId] = Snapshot(bytes, scaledWidth, scaledHeight)
            while (snapshots.size > maxEntries) {
                val oldest = snapshots.keys.firstOrNull() ?: break
                snapshots.remove(oldest)
            }
        }
    }

    /** 取某次请求的 OCR 输入截图快照；无记录返回 null（调用方必须如实显示「未保留」）。 */
    fun snapshotFor(traceId: String?): Snapshot? {
        if (traceId.isNullOrBlank()) return null
        return synchronized(lock) { snapshots[traceId] }
    }

    fun clear() {
        synchronized(lock) { snapshots.clear() }
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 2
        const val DEFAULT_MAX_LONGEST_SIDE = 720
        const val DEFAULT_JPEG_QUALITY = 80
    }
}

/** 等比缩放到最长边不超过 [maxLongestSide]；未超限时直接返回原图（不复制）。 */
private fun scaleForDiagnosis(bitmap: Bitmap, maxLongestSide: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= 0 || longest <= maxLongestSide) return bitmap
    val ratio = maxLongestSide.toFloat() / longest.toFloat()
    val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
    val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}
