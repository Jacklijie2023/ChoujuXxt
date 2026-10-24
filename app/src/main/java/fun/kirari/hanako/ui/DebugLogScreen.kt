package `fun`.kirari.hanako.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.HanakoApplication
import `fun`.kirari.hanako.copyToClipboardWithToast
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.formatDebugTime
import `fun`.kirari.hanako.ui.components.SectionCard
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugLogScreen(
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    val entries by AppDebugLogStore.entries.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionCard(title = "调试日志") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "这里会缓存最近的诊断日志（上限 400 条，超出丢弃最旧）。" +
                            "Logcat 看不到日志时，可先“写入自检日志”再“分享为 .txt”，无需手动抄录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                // 固定自检日志：与启动自检使用同一 tag / 前缀，便于在 Logcat 与导出文本中检索。
                                AppDebugLogStore.i(
                                    HanakoApplication.DIAG_TAG,
                                    "${HanakoApplication.SELF_TEST_MARKER} manual_test"
                                )
                                Toast.makeText(context, "已写入自检日志", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("写入自检日志")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                copyToClipboardWithToast(context, "Hanako Debug Logs", AppDebugLogStore.exportText(), "日志已复制")
                            }
                        ) {
                            Text("复制全部")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { shareLogFile(context) }
                        ) {
                            Text("分享为 .txt")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = onClearLogs
                        ) {
                            Text("清空日志")
                        }
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = "暂无日志",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(entries.reversed(), key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" }) { entry ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${formatDebugTime(entry.timestamp)} ${entry.level}/${entry.tag}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

/**
 * 把应用内诊断日志写入缓存目录并调起系统分享面板导出为 `.txt`。
 *
 * 复用 [AppDebugLogStore.exportText]（不新建第二套日志系统）：
 * 即使 Logcat 不可见，只要日志已进入内存缓存，这里就能导出。
 * 文件落在 `cacheDir/exports/`（由 `file_paths.xml` 中的 `exports` 路径放行给 FileProvider），
 * 通过系统分享面板发送给任意应用或保存到本地，避免手动抄录手机诊断页面。
 */
private fun shareLogFile(context: Context) {
    try {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "hanako_debug_log_$stamp.txt")
        val text = AppDebugLogStore.exportText()
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            // 完整日志可达数百 KB，整段塞进 EXTRA_TEXT 会超出 Binder 事务上限，
            // 系统直接抛 RuntimeException("Failure from system") → 分享面板打不开。
            // 文件本身已包含全部内容，这里只附带前 2000 字预览，供只处理文本的接收方使用。
            putExtra(Intent.EXTRA_TEXT, text.take(2_000))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "导出 Hanako 诊断日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (e: Exception) {
        AppDebugLogStore.e("DebugLog", "导出诊断日志失败", e)
        Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
    }
}
