package `fun`.kirari.hanako.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import `fun`.kirari.hanako.debug.AppDebugLogStore
import java.io.File

/**
 * 用户已发送图片的本地副本存储。
 *
 * 用途：多模态请求只携带 base64，聊天气泡需要可长期回显的图片引用。
 * 相册 / 拍照返回的 `content://` Uri 的读权限在进程重启后会失效，
 * 因此发送时把同一份图片字节写入应用私有目录，并把路径记录在 [ChatMessage.imagePaths] 中。
 *
 * 本类不参与模型上下文，也不改变发送的 base64 内容。
 */
internal object ChatImageStore {

    private const val DIR_NAME = "chat_images"

    /**
     * 把一张图片 Uri 编码为 base64（多模态请求用）。
     *
     * 聊天页与迷你窗共用：迷你窗宿主是 overlay Service，同样只能通过 contentResolver 读取相册。
     */
    fun encodeUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
        }
    }.getOrNull()

    /**
     * 把本次发送图片的 base64 原样落盘（字节与发送给模型的一致），返回可回显的本地路径。
     *
     * 单张失败只跳过该张，不影响其余图片与正常发送。
     */
    fun saveAll(context: Context, imagesBase64: List<String>): List<String> {
        if (imagesBase64.isEmpty()) return emptyList()
        val dir = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
        val stamp = System.currentTimeMillis()
        val paths = imagesBase64.mapIndexedNotNull { index, encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val file = File(dir, "chat_${stamp}_$index.img")
                file.writeBytes(bytes)
                file.absolutePath
            }.getOrNull()
        }
        // 仅记录数量与首条路径，便于真机核对「图片是否真的落盘」；不含图片内容。
        AppDebugLogStore.i(
            "chat_image",
            "save_all in=${imagesBase64.size} out=${paths.size} first=${paths.firstOrNull() ?: "-"}"
        )
        return paths
    }
}
