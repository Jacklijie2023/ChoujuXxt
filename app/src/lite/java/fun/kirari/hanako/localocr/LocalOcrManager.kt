package `fun`.kirari.hanako.localocr

import android.content.Context
import android.graphics.Bitmap
import `fun`.kirari.hanako.screencontext.OcrResult

data class LocalOcrInstallStatus(
    val installed: Boolean,
    val state: String? = null
)

class LocalOcrManager(@Suppress("UNUSED_PARAMETER") private val context: Context) {
    companion object {
        const val LOG_TAG = "LocalOcr"
    }

    suspend fun installationStatus(): LocalOcrInstallStatus {
        return LocalOcrInstallStatus(
            installed = false,
            state = "NOT_AVAILABLE"
        )
    }

    internal suspend fun recognize(bitmap: Bitmap): OcrResult {
        throw UnsupportedOperationException("Local OCR is not available in lite version")
    }
}
