package `fun`.kirari.hanako.evidence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import `fun`.kirari.hanako.debug.AppDebugLogStore
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 应用私有目录中的原始图像资产。
 *
 * 关键边界（需求 §3.1 / §6.1）：
 * - **原始证据文件**与**派生副本**分开保存：原图 `sha.png` 不被覆盖；供 OCR / 预览 / 模型输入使用的
 *   缩放副本是 `sha_preview.jpg`，可随时重建；
 * - 同一画面重复采集按 SHA-256 共享同一个文件，但每次采集事件仍各自保留证据记录；
 * - 文件缺失时返回 null 并由调用方标记 `IMAGE_MISSING`，绝不谎称图像可用。
 */
internal class EvidenceImageFiles(
    context: Context,
    /** 派生副本最长边（预览 / 测试中心展示）。 */
    private val previewMaxSide: Int = 1280,
    /** 送入模型前的最长边（控制 token 与带宽）。 */
    private val requestMaxSide: Int = 1280
) {
    private val tag = "evidence_image"
    private val dir = File(context.filesDir, "evidence/images")

    /** 一次截图保存的结果。 */
    internal data class SavedImage(
        val originalRef: String,
        val previewRef: String?,
        val sha256: String,
        val width: Int,
        val height: Int
    )

    /** 保存原始截图（PNG）并生成派生预览副本（JPEG）。返回 null 表示保存失败（磁盘/编码异常）。 */
    fun saveCapture(bitmap: Bitmap): SavedImage? {
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("无法创建证据图像目录")
            }
            val pngBytes = ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("PNG 编码失败")
                }
                out.toByteArray()
            }
            val sha = sha256Hex(pngBytes)
            val original = File(dir, "$sha.png")
            if (!original.exists() || original.length() != pngBytes.size.toLong()) {
                original.writeBytes(pngBytes)
            }

            val previewBitmap = scaleToMaxSide(bitmap, previewMaxSide)
            val previewRef = runCatching {
                val jpeg = ByteArrayOutputStream().use { out ->
                    if (!previewBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
                        throw IllegalStateException("JPEG 编码失败")
                    }
                    out.toByteArray()
                }
                val file = File(dir, "${sha}_preview.jpg")
                if (!file.exists()) file.writeBytes(jpeg)
                file.name
            }.getOrNull()

            SavedImage(
                originalRef = original.name,
                previewRef = previewRef,
                sha256 = sha,
                width = bitmap.width,
                height = bitmap.height
            )
        }.onFailure {
            AppDebugLogStore.e(tag, "save_capture_failed ${it.javaClass.simpleName}", it)
        }.getOrNull()
    }

    fun fileOf(ref: String?): File? {
        val name = ref?.trim().orEmpty()
        if (name.isEmpty()) return null
        val file = File(dir, name)
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    fun exists(ref: String?): Boolean = fileOf(ref) != null

    /**
     * 读取用于模型的图像 base64（JPEG）。
     *
     * 优先使用派生预览副本；缺失时回退原图并现场缩放。任一环节失败都返回 null，
     * 由调用方把「图像未发送」如实写进上下文状态与请求追踪。
     */
    fun readBase64ForModel(image: ImageAssetRecord): String? {
        val preview = fileOf(image.previewRef)
        if (preview != null) {
            val raw = runCatching { preview.readBytes() }.getOrNull()
            if (raw != null && raw.isNotEmpty()) return Base64.encodeToString(raw, Base64.NO_WRAP)
        }
        val original = fileOf(image.originalRef) ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(original.absolutePath) }.getOrNull() ?: return null
        val scaled = scaleToMaxSide(bitmap, requestMaxSide)
        val bytes = runCatching {
            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                    throw IllegalStateException("JPEG 编码失败")
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 测试中心展示用：读取可解码的预览图位图（不用于网络请求）。 */
    fun decodePreview(image: ImageAssetRecord): Bitmap? {
        val file = fileOf(image.previewRef) ?: fileOf(image.originalRef) ?: return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** 删除孤儿文件（未被任何证据引用的历史文件），返回删除数量。保留策略由证据库决定。 */
    fun pruneOrphans(keepRefs: Set<String>): Int {
        val files = dir.listFiles() ?: return 0
        var removed = 0
        files.forEach { file ->
            if (file.isFile && file.name !in keepRefs) {
                if (file.delete()) removed += 1
            }
        }
        return removed
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun scaleToMaxSide(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
