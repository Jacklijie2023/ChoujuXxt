package `fun`.kirari.hanako

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

fun formatDebugTime(timestamp: Long): String = synchronized(timeFormatter) {
    timeFormatter.format(Date(timestamp))
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    `fun`.kirari.hanako.data.ClipboardHistory.record(text, label)
}

fun copyToClipboardWithToast(context: Context, label: String, text: String, toastText: String) {
    copyToClipboard(context, label, text)
    Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
}

fun easeOutCubic(fraction: Float): Float {
    val inverse = 1f - fraction
    return 1f - inverse * inverse * inverse
}
