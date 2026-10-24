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
    /** 内存缓存上限（条）：超出后按先进先出丢弃最旧条目，导出时可见覆盖范围。 */
    private const val maxEntries = 400

    /**
     * append / clear 的并发保护。
     *
     * 诊断日志来自无障碍 Binder 线程、悬浮窗主线程、网络 SSE 线程等多条线程；
     * 原实现的 `_entries.value = (_entries.value + entry)` 是「读-改-写」，
     * 并发调用会相互覆盖而**丢日志**，导致应用内导出缺行。加锁后每次追加都不会丢失。
     */
    private val lock = Any()
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
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
        synchronized(lock) { _entries.value = emptyList() }
    }

    /**
     * 导出全部内存日志（供「复制全部」与「分享为 .txt」使用）。
     *
     * 头部显式标注导出时间、覆盖范围（首条 ~ 末条、条数）与缓存上限，
     * 便于判断「看到的日志是否覆盖了复现时段」，而不必猜测。
     * 正文只包含 时间戳 / 级别 / tag / 消息，消息由各调用点保证不含题干、聊天正文、API Key 或认证信息。
     */
    fun exportText(): String {
        val snapshot = synchronized(lock) { _entries.value }
        return buildString {
            append("Hanako 诊断日志\n")
            append("导出时间: ").append(formatTime(System.currentTimeMillis())).append('\n')
            append("覆盖范围: ")
            if (snapshot.isEmpty()) {
                append("暂无日志")
            } else {
                append(formatTime(snapshot.first().timestamp))
                    .append(" ~ ")
                    .append(formatTime(snapshot.last().timestamp))
                    .append("，共 ").append(snapshot.size).append(" 条")
            }
            append("（内存缓存上限 ").append(maxEntries).append(" 条，超出自动丢弃最旧记录）\n")
            append("说明: 仅含应用内诊断日志（时间戳/级别/tag/阶段与耗时），")
            append("不含题干、选项、聊天正文、API Key 或认证信息。\n")
            append("---\n")
            append(snapshot.joinToString("\n\n") { entry ->
                "${formatTime(entry.timestamp)} ${entry.level}/${entry.tag}\n${entry.message}"
            })
        }
    }

    private fun append(level: String, tag: String, message: String) {
        val entry = AppDebugLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        synchronized(lock) {
            _entries.value = (_entries.value + entry).takeLast(maxEntries)
        }
    }

    private fun formatTime(timestamp: Long): String = synchronized(timeFormatter) {
        timeFormatter.format(Date(timestamp))
    }
}
