package `fun`.kirari.hanako.localocr

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.screencontext.OcrResult
import `fun`.kirari.hanako.screencontext.OcrTextLine

data class LocalOcrInstallStatus(
    val installed: Boolean,
    val state: String? = null
)

class LocalOcrManager(@Suppress("UNUSED_PARAMETER") private val context: Context) {
    companion object {
        const val LOG_TAG = "LocalOcr"
    }

    private fun recognizer(): TextRecognizer {
        return TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun installationStatus(): LocalOcrInstallStatus {
        AppDebugLogStore.i(LOG_TAG, "installationStatus bundled=true")
        return LocalOcrInstallStatus(
            installed = true,
            state = "BUNDLED"
        )
    }

    /**
     * 执行一次本地 OCR（ML Kit 中文文本识别），返回**结构化结果**而非拼接字符串。
     *
     * 为什么必须返回结构：ML Kit 提供 `Text → TextBlock → Line → Element` 层级，
     * 每一级都有 `boundingBox`。旧实现直接把 `TextBlock.text` 按引擎返回顺序拼接，
     * 一旦引擎把「A、B、C、D」标签与选项正文拆成不同的行/块返回，
     * 拼接结果就会标签集中、正文分散、选项前后半段分离（真机已复现）。
     * 因此这里同时保留：
     * - [OcrResult.rawText]：引擎自身的拼接结果（原样，仅用于诊断对比）；
     * - [OcrResult.lines]：带坐标的行，交由 `organizeOcrText` 按坐标恢复阅读顺序并关联选项。
     *
     * 行优先取更细的 `Element`（同一 Line 内 ML Kit 只在间距足够大时才拆分，
     * 正是「标签」与「正文」分离的场景），拿不到时退回 `Line`；两者都是引擎自己保证
     * 「同一 Line 内沿水平方向对齐」的粒度，不会跨行。
     */
    internal suspend fun recognize(bitmap: Bitmap): OcrResult {
        val recognizer = recognizer()
        return try {
            AppDebugLogStore.i(LOG_TAG, "recognize start bitmap=${bitmap.width}x${bitmap.height}")
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image))
            val rawText = buildString {
                result.textBlocks.forEachIndexed { index, block ->
                    if (index > 0) append('\n')
                    append(block.text)
                }
            }.trim()

            val lines = ArrayList<OcrTextLine>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val elements = line.elements
                    if (elements.isNotEmpty()) {
                        for (element in elements) {
                            val box = element.boundingBox ?: line.boundingBox ?: continue
                            val text = element.text
                            if (text.isBlank()) continue
                            lines += OcrTextLine(text, box.left, box.top, box.right, box.bottom)
                        }
                    } else {
                        val box = line.boundingBox ?: continue
                        if (line.text.isBlank()) continue
                        lines += OcrTextLine(line.text, box.left, box.top, box.right, box.bottom)
                    }
                }
            }
            AppDebugLogStore.i(
                LOG_TAG,
                "recognize done rawLength=${rawText.length} lines=${lines.size} blocks=${result.textBlocks.size}"
            )
            OcrResult(rawText = rawText, lines = lines)
        } catch (t: Throwable) {
            AppDebugLogStore.e(LOG_TAG, "recognize failed", t)
            throw t
        } finally {
            recognizer.close()
        }
    }
}
