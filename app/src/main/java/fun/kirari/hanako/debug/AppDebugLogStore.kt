package `fun`.kirari.hanako.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppDebugLogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String
)

object AppDebugLogStore {
    private const val maxEntries = 400
    private val timeFormatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val _entries = MutableStateFlow<List<AppDebugLogEntry>>(emptyList())
    val entries: StateFlow<List<AppDebugLogEntry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append(
            level = "E",
            tag = tag,
            message = buildString {
                append(message)
                throwable?.let {
                    append('\n')
                    append(Log.getStackTraceString(it))
                }
            }
        )
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun exportText(): String {
        return _entries.value.joinToString("\n\n") { entry ->
            "${formatTime(entry.timestamp)} ${entry.level}/${entry.tag}\n${entry.message}"
        }
    }

    private fun append(level: String, tag: String, message: String) {
        val entry = AppDebugLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        _entries.value = (_entries.value + entry).takeLast(maxEntries)
    }

    private fun formatTime(timestamp: Long): String = synchronized(timeFormatter) {
        timeFormatter.format(Date(timestamp))
    }
}
