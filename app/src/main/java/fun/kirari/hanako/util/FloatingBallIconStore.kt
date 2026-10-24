package `fun`.kirari.hanako.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 悬浮球自定义图标存储。
 *
 * 用户上传的图片必须「高清缩小」后再保存：
 * - 保存前等比缩放到最长边不超过 [MAX_EDGE_PX]，避免原图过大导致悬浮球只展示某个「像素」，
 *   观感上变成一个色块而不是完整图片；
 * - 渲染时再按图标显示尺寸二次缩小解码，控制内存占用。
 */
internal object FloatingBallIconStore {
    private const val FILE_NAME = "floating_ball_custom.png"

    /** 保存尺寸上限（最长边像素）。高清缩小后既能完整展示，又不至于过大。 */
    private const val MAX_EDGE_PX = 512

    /** 渲染解码目标尺寸（悬浮球图标显示区域约 20dp，按 3x 密度约 60px，放宽到 160px）。 */
    private const val DECODE_EDGE_PX = 160

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 从图片选择器 Uri 解码并高清缩小保存，返回保存后的文件；失败返回 null。 */
    fun save(context: Context, uri: Uri): File? {
        val bitmap = decodeUri(context, uri) ?: return null
        return persist(context, bitmap)
    }

    /** 从已有 Bitmap 高清缩小保存（供单元测试 / 后续扩展复用）。 */
    fun save(context: Context, bitmap: Bitmap): File = persist(context, bitmap)

    /** 解码用户所选图片：先采样避免 OOM，再等比缩放到 [MAX_EDGE_PX] 以内。 */
    private fun decodeUri(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE_PX * 2 && bounds.outHeight / sample > MAX_EDGE_PX * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun persist(context: Context, bitmap: Bitmap): File {
        val maxEdge = max(bitmap.width, bitmap.height)
        val scaled = if (maxEdge > MAX_EDGE_PX) {
            val ratio = MAX_EDGE_PX.toFloat() / maxEdge
            val w = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
            val h = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true).also { if (it !== bitmap) bitmap.recycle() }
        } else {
            bitmap
        }
        val out = file(context)
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (scaled !== bitmap) scaled.recycle()
        return out
    }

    /** 按悬浮球图标显示尺寸二次缩小解码为 BitmapDrawable（渲染时使用，不做 tint）。 */
    fun loadDrawable(context: Context, path: String): BitmapDrawable? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > DECODE_EDGE_PX && bounds.outHeight / sample > DECODE_EDGE_PX) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(path, opts) ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    /** 删除自定义图标文件（恢复默认悬浮球时调用）。 */
    fun delete(context: Context) {
        file(context).delete()
    }
}
