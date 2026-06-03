@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package `fun`.kirari.hanako.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.copyToClipboardWithToast
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.decodeHistoryBitmap
import `fun`.kirari.hanako.data.loadHistoryBitmap
import `fun`.kirari.hanako.overlay.MarkdownLatexText
import `fun`.kirari.hanako.ui.components.ImagePreviewOverlay
import `fun`.kirari.hanako.ui.components.SectionCard
import kotlin.math.roundToInt

@Composable
fun HistorySubScreen(
    settings: AppSettings,
    onClearHistory: () -> Unit,
    onDeleteHistoryItem: (String) -> Unit,
    onOpenHistoryDetail: (String) -> Unit
) {
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val historyStorageText = remember(settings.history) {
        val fileBytes = settings.history.sumOf { result ->
            result.screenshotPath?.let { path ->
                runCatching { java.io.File(path).length() }.getOrDefault(0)
            } ?: 0
        }
        val base64Bytes = settings.history.sumOf { it.screenshotBase64?.length ?: 0 }.toLong()
        formatHistorySize(fileBytes + base64Bytes)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "历史记录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    historyStorageText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onClearHistory,
                    enabled = settings.history.isNotEmpty()
                ) {
                    Text("清空")
                }
            }
        }
        if (settings.history.isEmpty()) {
            item {
                SectionCard(title = "暂无历史") {
                    Text(
                        "悬浮窗处理过的记录会显示在这里。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(settings.history, key = { it.id }) { result ->
                HistoryListItem(
                    result = result,
                    onClick = { onOpenHistoryDetail(result.id) },
                    onLongClick = { deleteTargetId = result.id }
                )
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    val deleteTarget = settings.history.firstOrNull { it.id == deleteTargetId }
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text("删除历史记录") },
            text = { Text("确认删除 ${deleteTarget.assistantName} 的这条记录？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteHistoryItem(deleteTarget.id)
                        deleteTargetId = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) {
                    Text("取消")
                }
            },
            icon = {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        )
    }
}

@Composable
private fun HistoryListItem(
    result: ProcessingResult,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val thumbnail = remember(result.screenshotPath, result.screenshotBase64) {
        result.screenshotPath?.loadHistoryBitmap()
            ?: result.screenshotBase64?.decodeHistoryBitmap()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    result.assistantName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "模式：${result.route.displayName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "状态：${result.status.displayName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = historyStatusColor(result.status)
                )
                Text(
                    text = historyPreviewText(result),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                modifier = Modifier.size(width = 84.dp, height = 112.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryDetailScreen(result: ProcessingResult?) {
    if (result == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "未找到该历史记录。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val screenshots = remember(result.allScreenshotPaths, result.screenshotBase64) {
        val bitmaps = result.allScreenshotPaths.mapNotNull { it.loadHistoryBitmap() }.toMutableList()
        if (bitmaps.isEmpty()) {
            result.screenshotBase64?.decodeHistoryBitmap()?.let { bitmaps.add(it) }
        }
        bitmaps
    }
    val context = LocalContext.current
    var previewImageIndex by remember { mutableStateOf(-1) }
    var imageBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = formatHistoryDetailHeader(result),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (screenshots.isNotEmpty()) {
            item {
                if (screenshots.size == 1) {
                    Image(
                        bitmap = screenshots[0].asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInWindow()
                                val size = coords.size
                                imageBounds = android.graphics.Rect(
                                    pos.x.roundToInt(),
                                    pos.y.roundToInt(),
                                    (pos.x + size.width).roundToInt(),
                                    (pos.y + size.height).roundToInt()
                                )
                            }
                            .clickable { previewImageIndex = 0 },
                        contentScale = ContentScale.FillWidth
                    )
                } else {
                    Column {
                        Text(
                            text = "截图（${screenshots.size} 张）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            itemsIndexed(screenshots) { index, bitmap ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier
                                        .width(280.dp)
                                        .heightIn(min = 200.dp, max = 400.dp)
                                        .clickable { previewImageIndex = index }
                                ) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "截图 ${index + 1}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (result.route == ProcessingRoute.OCR_THEN_LLM || result.route == ProcessingRoute.ACCESSIBILITY_TEXT) {
            item {
                HistoryResultCard(
                    title = if (result.route == ProcessingRoute.ACCESSIBILITY_TEXT) "无障碍提取结果" else "OCR 结果",
                    action = {
                        TextButton(
                            onClick = {
                                copyToClipboardWithToast(context, "Hanako OCR 原文", result.extractedText, "已复制 OCR 原文")
                            },
                            enabled = result.extractedText.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("复制原文")
                        }
                    }
                ) {
                    if (result.extractedText.isNotBlank()) {
                        MarkdownLatexText(
                            content = result.extractedText,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("暂无内容")
                    }
                }
            }
        }
        if (result.detail.isNotBlank()) {
            item {
                HistoryResultCard(title = "请求详情") {
                    Text(result.detail)
                }
            }
        }
        if (result.events.isNotEmpty()) {
            item {
                HistoryResultCard(title = "处理步骤") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        result.events.forEach { event ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(event.title, fontWeight = FontWeight.SemiBold)
                                if (event.detail.isNotBlank()) {
                                    Text(
                                        event.detail,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (result.automationAction != null || result.automationThought.isNotBlank()) {
            item {
                HistoryResultCard(title = "思考过程") {
                    if (result.automationThought.isNotBlank()) {
                        MarkdownLatexText(
                            content = result.automationThought,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("暂无内容")
                    }
                }
            }
            item {
                val actionText = result.automationAction?.text.orEmpty()
                HistoryResultCard(
                    title = "工具调用",
                    action = {
                        TextButton(
                            onClick = {
                                copyToClipboardWithToast(context, "Hanako 自动模式工具内容", actionText, "已复制工具内容")
                            },
                            enabled = actionText.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("复制内容")
                        }
                    }
                ) {
                    Text("调用了工具：${automationActionLabel(result)}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(actionText.ifBlank { "暂无内容" })
                }
            }
        } else {
            item {
                HistoryResultCard(
                    title = "答案",
                    action = {
                        TextButton(
                            onClick = {
                                copyToClipboardWithToast(context, "Hanako 原始答案", result.answer, "已复制原文")
                            },
                            enabled = result.answer.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("复制原文")
                        }
                    }
                ) {
                    if (result.answer.isNotBlank()) {
                        MarkdownLatexText(
                            content = result.answer,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("暂无内容")
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (previewImageIndex >= 0 && previewImageIndex < screenshots.size) {
        ImagePreviewOverlay(
            visible = true,
            bitmap = screenshots[previewImageIndex],
            fileName = "hanako_history_${result.id}_$previewImageIndex",
            onDismiss = { previewImageIndex = -1 },
            sourceBounds = imageBounds
        )
    }
}

private fun historyPreviewText(result: ProcessingResult): String {
    return when {
        result.detail.isNotBlank() && result.status != ProcessingStatus.SUCCESS -> result.detail
        result.automationAction != null -> "${automationActionLabel(result)}：${result.automationAction.text}"
        result.answer.isNotBlank() -> result.answer
        else -> "暂无回答"
    }
}

@Composable
private fun historyStatusColor(status: ProcessingStatus): Color {
    return when (status) {
        ProcessingStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ProcessingStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
        ProcessingStatus.ERROR -> MaterialTheme.colorScheme.error
        ProcessingStatus.TIMEOUT -> MaterialTheme.colorScheme.error
    }
}

private fun ProcessingStatus.displayName(): String = when (this) {
    ProcessingStatus.RUNNING -> "进行中"
    ProcessingStatus.SUCCESS -> "成功"
    ProcessingStatus.ERROR -> "失败"
    ProcessingStatus.TIMEOUT -> "超时"
}

private fun automationActionLabel(result: ProcessingResult): String {
    return when (result.automationAction?.type) {
        AutomationActionType.SET_CLIPBOARD -> "设置剪贴板"
        AutomationActionType.SHOW_BUBBLE_LETTERS -> "显示悬浮球字母"
        AutomationActionType.AUTO_FILL_INPUT -> "自动填写"
        AutomationActionType.AUTO_CLICK_BUTTON -> "自动点击"
        AutomationActionType.VOICE_READ -> "语音朗读"
        null -> "未调用工具"
    }
}

@Composable
private fun HistoryResultCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                action?.invoke()
            }
            content()
        }
    }
}

private fun ProcessingRoute.displayName(): String = when (this) {
    ProcessingRoute.OCR_THEN_LLM -> "OCR"
    ProcessingRoute.MULTIMODAL_DIRECT -> "多模态"
    ProcessingRoute.ACCESSIBILITY_TEXT -> "无障碍"
}

private fun formatHistoryDetailHeader(result: ProcessingResult): String {
    val providerOrModel = result.modelSummary
        .substringAfter('（', missingDelimiterValue = "")
        .substringBefore('）')
        .ifBlank { result.modelSummary }

    return buildList {
        add(result.assistantName)
        add(result.route.displayName())
        providerOrModel.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")
}

private fun formatHistorySize(bytes: Long): String {
    if (bytes < 1024L) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1fKB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1fMB", mb)
}
