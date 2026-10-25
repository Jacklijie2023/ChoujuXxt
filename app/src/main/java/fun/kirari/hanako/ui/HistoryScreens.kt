@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package `fun`.kirari.hanako.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.chat.ChatModelCatalog
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.copyToClipboardWithToast
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.decodeHistoryBitmap
import `fun`.kirari.hanako.data.loadHistoryBitmap
import `fun`.kirari.hanako.overlay.MarkdownLatexText
import `fun`.kirari.hanako.overlay.QuestionExtractor
import `fun`.kirari.hanako.overlay.TtsHelper
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
internal fun HistoryDetailScreen(
    result: ProcessingResult?,
    /** 与主聊天页/悬浮窗共享的会话，供「继续追问」内嵌聊天复用。 */
    chatHolder: ChatSessionHolder? = null,
    chatProviders: List<ModelProviderConfig> = emptyList(),
    chatCatalog: ChatModelCatalog? = null
) {
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
    // 「继续追问」展开状态与朗读状态（与悬浮窗分析页共用同一 TtsHelper 引擎）。
    var chatExpanded by remember(result.id) { mutableStateOf(false) }
    var chatHeaderPx by remember { mutableStateOf(0) }
    val readingMessageId by TtsHelper.readingMessageId.collectAsState()
    val listState = rememberLazyListState()

    // OCR / 无障碍模式：与悬浮窗分析页同一套提取逻辑，把 OCR 原文还原成 材料 → 题干 → 选项。
    val isTextRoute = result.route == ProcessingRoute.OCR_THEN_LLM ||
        result.route == ProcessingRoute.ACCESSIBILITY_TEXT
    val extractedQuestion = remember(result.id, result.extractedText) {
        QuestionExtractor.extract(result.extractedText)
    }
    val questionText = remember(extractedQuestion) {
        buildString {
            extractedQuestion.material.forEach {
                append(it)
                append("\n\n")
            }
            if (extractedQuestion.stem.isNotBlank()) {
                append(extractedQuestion.stem)
                append("\n\n")
            }
            extractedQuestion.options.forEach {
                append("${it.label}. ${it.text}")
                append("\n\n")
            }
        }.trim()
    }
    val showQuestionCard = isTextRoute && extractedQuestion.hasContent && questionText.isNotBlank()
    val hasAutomationSection = result.automationAction != null || result.automationThought.isNotBlank()

    // 「继续追问」卡片固定为列表最后一项；展开后要把它滚到「标题行被顶出可视区」，需要知道下标。
    val chatItemIndex = 1 + // 顶部标题
        (if (screenshots.isNotEmpty()) 1 else 0) +
        (if (isTextRoute) 1 else 0) +
        (if (showQuestionCard) 1 else 0) +
        (if (result.detail.isNotBlank()) 1 else 0) +
        (if (result.events.isNotEmpty()) 1 else 0) +
        (if (hasAutomationSection) 2 else 1) // 思考过程 + 工具调用 / 答案

    // 历史详情页在 Activity 内，键盘由系统 resize + imePadding 处理，无需额外的窗口逻辑。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        val viewportHeight = maxHeight
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        LaunchedEffect(chatExpanded, imeBottom, viewportHeight) {
            if (!chatExpanded) return@LaunchedEffect
            withFrameNanos { }
            repeat(3) {
                // 卡片高度 = 可视区高度 + 标题行高度：把卡片顶边滚出可视区，
                // 对话页正好从可视区顶边铺到键盘上沿（与悬浮窗分析页一致）。
                listState.scrollToItem(index = chatItemIndex, scrollOffset = chatHeaderPx)
                withFrameNanos { }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 展开时去掉底部内边距：卡片下边缘正好是内容末尾，滚到底即贴住可视区底边。
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (chatExpanded) 0.dp else 16.dp),
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
        if (showQuestionCard) {
            // 「完整材料和题目」：与 Hanako 处理页同一模块，用于核对答案是否对应这道原题。
            item {
                HistoryResultCard(
                    title = "完整材料和题目",
                    action = {
                        Row {
                            val isReading = readingMessageId == historyQuestionSpeechId(result.id)
                            TextButton(
                                onClick = {
                                    if (isReading) {
                                        TtsHelper.stop()
                                    } else {
                                        TtsHelper.speak(questionText, historyQuestionSpeechId(result.id))
                                    }
                                }
                            ) {
                                Text(if (isReading) "停止" else "朗读")
                            }
                            TextButton(
                                onClick = {
                                    copyToClipboardWithToast(context, "Hanako 题目", questionText, "已复制题目与选项")
                                }
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.size(4.dp))
                                Text("复制")
                            }
                        }
                    }
                ) {
                    MarkdownLatexText(
                        content = questionText,
                        modifier = Modifier.fillMaxWidth()
                    )
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
        // 「继续追问」：与 Hanako 处理页/底部导航聊天页同一个 ChatScreen（同会话、同功能）。
        // 展开时卡片高度 = 可视区高度 + 标题行高度，滚到底后标题行被顶出可视区、
        // 对话页铺满整屏；向上滚动仍可找回标题并收起。
        item {
            val chatHeaderHeight = with(LocalDensity.current) { chatHeaderPx.toDp() }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (chatExpanded) {
                            Modifier.height(viewportHeight + chatHeaderHeight)
                        } else {
                            Modifier
                        }
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .then(if (chatExpanded) Modifier.fillMaxSize() else Modifier)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { chatHeaderPx = it.size.height }
                            .clickable { chatExpanded = !chatExpanded }
                            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "继续追问",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (chatExpanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (chatExpanded) {
                        val holder = chatHolder
                        val catalog = chatCatalog
                        if (holder != null && catalog != null) {
                            ChatScreen(
                                holder = holder,
                                providers = chatProviders,
                                catalog = catalog,
                                applyStatusBarPadding = false,
                                // 键盘内边距由本页统一处理，聊天页内部不再叠加。
                                applyImePadding = false,
                                inputBottomPadding = 2.dp,
                                inputTopPadding = 2.dp,
                                contextProvider = { historyPageContext(result) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        } else {
                            Text(
                                "聊天窗口暂不可用",
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
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

/**
 * 「完整材料和题目」朗读用的消息 id：按历史记录 id 区分，避免与聊天页/分析页的朗读抢占。
 */
private fun historyQuestionSpeechId(resultId: String): String = "history_question_speech_$resultId"

/**
 * 从历史记录组装「继续追问」的聊天上下文：截图 / OCR 原文 / 当时的答案。
 *
 * 与悬浮窗分析页的 [fun.kirari.hanako.overlay.overlayResultPageContext] 同一思路，
 * 让助手能就这条历史记录的内容继续追问；没有可用内容时返回 null（退回普通聊天）。
 */
private fun historyPageContext(result: ProcessingResult): String? {
    val parts = mutableListOf<String>()
    val pages = result.allScreenshotPaths.size
    if (pages > 0 || result.screenshotBase64 != null) {
        parts += if (pages > 1) "原图：共 $pages 屏长截图" else "原图：当前截图 1 屏"
    }
    if (result.extractedText.isNotBlank()) {
        parts += "OCR / 无障碍提取原文：\n${result.extractedText.trim()}"
    }
    if (result.answer.isNotBlank()) {
        parts += "Hanako 当时的答案：\n${result.answer.trim()}"
    }
    if (parts.isEmpty()) return null
    return buildString {
        appendLine("以下是 Hanako 历史记录详情页中的内容（用户正在就此追问）：")
        parts.forEach {
            appendLine(it)
            appendLine()
        }
    }.trim()
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
