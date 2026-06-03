package `fun`.kirari.hanako.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录导出工具
 * 支持导出为 JSON 和 CSV 格式
 */
object HistoryExporter {
    private const val TAG = "HistoryExporter"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * 导出历史记录为 JSON 文件
     */
    fun exportToJson(context: Context, history: List<ProcessingResult>): File? {
        return try {
            val exportData = ExportData(
                exportedAt = System.currentTimeMillis(),
                count = history.size,
                items = history.map { it.toExportItem() }
            )
            val jsonString = json.encodeToString(exportData)
            val file = createExportFile(context, "json")
            file.writeText(jsonString)
            AppDebugLogStore.i(TAG, "Exported ${history.size} items to JSON: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            AppDebugLogStore.e(TAG, "Failed to export JSON", e)
            null
        }
    }

    /**
     * 导出历史记录为 CSV 文件
     */
    fun exportToCsv(context: Context, history: List<ProcessingResult>): File? {
        return try {
            val file = createExportFile(context, "csv")
            file.bufferedWriter().use { writer ->
                // 写入表头
                writer.write("时间,助手,路由,状态,OCR文本,答案,自动化思考,自动化动作")
                writer.newLine()
                // 写入数据
                history.forEach { result ->
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(result.createdAtMillis))
                    val ocr = result.extractedText.replace("\"", "\"\"").take(200)
                    val answer = result.answer.replace("\"", "\"\"").take(200)
                    val thought = result.automationThought.replace("\"", "\"\"").take(100)
                    val action = "${result.automationAction?.type ?: ""}: ${result.automationAction?.text ?: ""}"
                    writer.write("\"$time\",\"${result.assistantName}\",\"${result.route}\",\"${result.status}\",\"$ocr\",\"$answer\",\"$thought\",\"$action\"")
                    writer.newLine()
                }
            }
            AppDebugLogStore.i(TAG, "Exported ${history.size} items to CSV: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            AppDebugLogStore.e(TAG, "Failed to export CSV", e)
            null
        }
    }

    /**
     * 分享导出文件
     */
    fun shareFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = when (file.extension) {
                    "json" -> "application/json"
                    "csv" -> "text/csv"
                    else -> "*/*"
                }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出历史记录").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            AppDebugLogStore.e(TAG, "Failed to share file", e)
        }
    }

    private fun createExportFile(context: Context, extension: String): File {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        return File(dir, "hanako_history_$timestamp.$extension")
    }

    private fun ProcessingResult.toExportItem(): ExportItem = ExportItem(
        id = id,
        time = createdAtMillis,
        assistant = assistantName,
        route = route.name,
        status = status.name,
        ocrText = extractedText,
        answer = answer,
        automationThought = automationThought,
        automationAction = automationAction?.let { "${it.type}: ${it.text}" }
    )
}

@Serializable
private data class ExportData(
    val exportedAt: Long,
    val count: Int,
    val items: List<ExportItem>
)

@Serializable
private data class ExportItem(
    val id: String,
    val time: Long,
    val assistant: String,
    val route: String,
    val status: String,
    val ocrText: String,
    val answer: String,
    val automationThought: String,
    val automationAction: String?
)
