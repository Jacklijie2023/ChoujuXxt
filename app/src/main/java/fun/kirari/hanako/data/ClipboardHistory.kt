package `fun`.kirari.hanako.data

import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 剪贴板历史记录管理器
 * 记录所有自动复制的内容
 */
object ClipboardHistory {
    private const val TAG = "ClipboardHistory"
    private const val MAX_HISTORY_SIZE = 100

    private val _history = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val history: StateFlow<List<ClipboardEntry>> = _history.asStateFlow()

    /**
     * 记录一次剪贴板操作
     */
    fun record(text: String, source: String = "auto") {
        if (text.isBlank()) return
        val entry = ClipboardEntry(
            text = text,
            source = source,
            timestamp = System.currentTimeMillis()
        )
        val current = _history.value.toMutableList()
        current.add(0, entry)
        if (current.size > MAX_HISTORY_SIZE) {
            current.removeAt(current.lastIndex)
        }
        _history.value = current
        AppDebugLogStore.d(TAG, "Recorded clipboard: ${text.take(30)}...")
    }

    /**
     * 获取格式化的历史记录
     */
    fun getFormattedHistory(): String {
        val entries = _history.value
        if (entries.isEmpty()) return "暂无剪贴板历史"
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return entries.mapIndexed { index, entry ->
            val time = sdf.format(Date(entry.timestamp))
            "${index + 1}. [$time] ${entry.text.take(50)}${if (entry.text.length > 50) "..." else ""}"
        }.joinToString("\n")
    }

    /**
     * 清空历史
     */
    fun clear() {
        _history.value = emptyList()
    }
}

data class ClipboardEntry(
    val text: String,
    val source: String,
    val timestamp: Long
)
