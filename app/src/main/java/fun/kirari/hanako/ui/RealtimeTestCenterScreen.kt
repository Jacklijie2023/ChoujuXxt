package `fun`.kirari.hanako.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `fun`.kirari.hanako.AppContainer
import `fun`.kirari.hanako.evidence.BlockVerdict
import `fun`.kirari.hanako.evidence.ClassifiedBlock
import `fun`.kirari.hanako.evidence.EvidenceRecord
import `fun`.kirari.hanako.evidence.RequestTraceRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实时互动测试中心：**全部使用真实业务数据**（运行时状态、原始证据、过滤结果、请求追踪）。
 *
 * 不包含任何静态样例、模拟成功状态或伪造指标；无法取得的数据一律显示为「-」或明确原因。
 */
@Composable
internal fun RealtimeTestCenterScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<AppContainer.RealtimeTestSnapshot?>(null) }
    var evidence by remember { mutableStateOf<List<EvidenceRecord>>(emptyList()) }
    var traces by remember { mutableStateOf<List<RequestTraceRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var actionMessage by remember { mutableStateOf("") }
    var previewInstruction by remember { mutableStateOf("这道题的解题思路是什么？") }
    var previewText by remember { mutableStateOf("") }
    var selectedEvidence by remember { mutableStateOf<EvidenceRecord?>(null) }
    var selectedBlocks by remember { mutableStateOf<List<ClassifiedBlock>>(emptyList()) }
    var selectedImage by remember { mutableStateOf<Bitmap?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    suspend fun load() {
        loading = true
        snapshot = container.realtimeTestSnapshot()
        evidence = withContext(Dispatchers.IO) { container.recentEvidenceForTest(30) }
        traces = withContext(Dispatchers.IO) { container.recentRequestTracesForTest(15) }
        loading = false
    }

    LaunchedEffect(refreshTick) { load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = "A. 运行状态（真实运行时）") {
                val s = snapshot
                if (s == null) {
                    Text("尚未读取到运行时状态", color = MaterialTheme.colorScheme.error)
                } else {
                    KeyValue("实时互动模式", if (s.realtimeEnabled) "已启用" else "未启用")
                    KeyValue("前台运行状态", if (s.runtimeRunning) "运行中" else "未运行")
                    KeyValue("无障碍服务", if (s.accessibilityRunning) "已启用" else "未启用")
                    KeyValue("无障碍连接", if (s.accessibilityConnected) "已连接前台应用" else "未连接")
                    KeyValue("当前前台应用", s.currentWindowPackage ?: "-")
                    KeyValue("已绑定目标应用", s.boundTargetPackage ?: "未绑定")
                    KeyValue("截图会话", if (s.captureSessionActive) "可用（${s.captureMethod}）" else "不可用（${s.captureMethod}）")
                    KeyValue("本地 OCR", s.ocrInstallation)
                    KeyValue("采集器", if (s.collectorEnabled) "运行中" else "已停止")
                    KeyValue(
                        "最近一次判定",
                        "${s.collectorLastResult} / 来源 ${s.collectorLastSource}"
                    )
                    KeyValue(
                        "观察序号",
                        "${s.collectorSequence}（已接受 ${s.collectorAcceptedSequence}）" +
                            if (s.collectorOcrInFlight) " · OCR 在途" else ""
                    )
                    KeyValue("文本池字符", s.collectorTextChars.toString())
                    KeyValue("当前题目", s.collectorQuestionKey ?: "尚未建立")
                    KeyValue("会话", s.sessionId)
                    KeyValue(
                        "最近采集",
                        s.lastCaptureAtMillis?.let { formatTime(it) } ?: "无"
                    )
                    if (s.lastFailureStage != null) {
                        KeyValue("最近失败", "${s.lastFailureStage}: ${s.lastFailureMessage ?: "-"}")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !loading,
                        onClick = { refreshTick += 1 }
                    ) { Text("刷新") }
                    OutlinedButton(
                        enabled = !loading,
                        onClick = {
                            scope.launch {
                                actionMessage = container.manualCaptureForTest()
                                refreshTick += 1
                            }
                        }
                    ) { Text("触发一次受控采集") }
                }
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("读取中…", fontSize = 12.sp)
                    }
                }
                if (actionMessage.isNotBlank()) {
                    Text(actionMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item {
            SectionCard(title = "B. 原始证据与可回溯性") {
                val s = snapshot
                Text(
                    "证据 ${s?.evidenceCount ?: "-"} 条 · 图像 ${s?.imageCount ?: "-"} 张" +
                        " · 缺失 ${s?.imageMissingCount ?: "-"} 张" +
                        " · 图像占用 ${(s?.imageBytes ?: 0L) / 1024} KB",
                    fontSize = 12.sp
                )
                Text(
                    "原始证据只追加、不改写；过滤/合并结果单独保存，可重跑对照。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                evidence.forEach { record ->
                    EvidenceRow(
                        record = record,
                        onClick = {
                            selectedEvidence = record
                            scope.launch {
                                selectedBlocks = withContext(Dispatchers.IO) {
                                    container.classificationsForTest(record.evidenceId)
                                }
                                selectedImage = withContext(Dispatchers.IO) {
                                    container.realtimeEvidence.currentContext()?.images
                                        ?.firstOrNull { it.evidenceId == record.evidenceId }
                                        ?.let { container.decodeEvidenceImageForTest(it) }
                                }
                            }
                        }
                    )
                }
                if (evidence.isEmpty()) {
                    Text("暂无证据：请先开启实时互动并正常浏览题目。", fontSize = 12.sp)
                }
            }
        }

        item {
            SectionCard(title = "C. 过滤前后对照 + D. 当前题目上下文") {
                val context = snapshot?.context
                if (context == null) {
                    Text("当前没有已建立的题目上下文。", fontSize = 12.sp)
                } else {
                    KeyValue("状态", context.status.name)
                    KeyValue("题目标识", context.questionId ?: "-")
                    KeyValue("版本", "v${context.version}")
                    KeyValue("来源应用", context.sourceApp ?: "-")
                    KeyValue(
                        "新鲜度",
                        "${context.freshnessMillis / 1000} 秒前（采集 ${context.capturedAtMillis?.let(::formatTime) ?: "-"}）"
                    )
                    KeyValue("注入字符", context.text.length.toString())
                    KeyValue("排除噪声行", context.excludedCount.toString())
                    KeyValue("待确认行", context.uncertainCount.toString())
                    KeyValue("图像证据", "${context.availableImages.size} 张可用 / ${context.images.size} 张关联")
                    if (context.missing.isNotEmpty()) {
                        Text("明确缺失 / 无法确认：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        context.missing.forEach { Text("· $it", fontSize = 11.sp) }
                    }
                    Text(
                        "最终注入文本（可重建，原文仍保存在证据库）：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        context.text.ifBlank { "（空）" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (context.images.isNotEmpty()) {
                        Text("图像证据预览：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        context.images.forEach { ref ->
                            var bitmap by remember(ref.imageId) { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(ref.imageId) {
                                bitmap = withContext(Dispatchers.IO) {
                                    container.decodeEvidenceImageForTest(ref)
                                }
                            }
                            Text(
                                "${ref.imageId} · 可用=${ref.available} · ${formatTime(ref.capturedAtMillis)}",
                                fontSize = 11.sp
                            )
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "图像证据",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val rebuilt = container.rebuildContextForTest()
                            actionMessage = "上下文已重建：${rebuilt.status} v${rebuilt.version} " +
                                "字符=${rebuilt.text.length} 缺失=${rebuilt.missing.size}"
                            refreshTick += 1
                        }
                    }) { Text("按证据重建上下文") }
                }
            }
        }

        item {
            SectionCard(title = "E. 失败与阻断场景") {
                val failures = evidence.filter { it.sourceType.name == "FAILURE" || it.availability.name != "STORED" }
                if (failures.isEmpty()) {
                    Text("暂无失败记录。", fontSize = 12.sp)
                } else {
                    failures.forEach { record ->
                        Text(
                            "${formatTime(record.capturedAtMillis)} · ${record.errorStage ?: record.availability.name}" +
                                " · ${record.errorMessage ?: "-"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "F. 模型请求追踪（实际发送了什么）") {
                if (traces.isEmpty()) {
                    Text("还没有请求记录：请先在迷你聊天窗口提一次问。", fontSize = 12.sp)
                }
                traces.forEach { trace ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    KeyValue("trace", trace.traceId)
                    KeyValue("题目 / 上下文", "${trace.questionId ?: "-"} / v${trace.contextVersion} ${trace.contextStatus}")
                    KeyValue("文字", "${trace.textChars} 字符 · fp=${trace.textFingerprint.take(12)}")
                    KeyValue("图像", "${trace.imageCount} 张 · ${trace.imageIds.joinToString(",").ifBlank { "-" }}")
                    KeyValue("视觉能力", trace.visionStatus)
                    KeyValue(
                        "发送结果",
                        if (trace.sent) "已发送" else "未发送（${trace.failStage ?: "-"}：${trace.errorMessage ?: "-"}）"
                    )
                }
            }
        }

        item {
            SectionCard(title = "G. 隐私、保留与导出") {
                Text(
                    "原始截图与文本保存在应用私有目录（hanako_evidence.db + evidence/images），" +
                        "不会上传；默认保留 7 天，活动题目证据不会被自动清理。",
                    fontSize = 11.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            actionMessage = container.pruneEvidenceForTest()
                            refreshTick += 1
                        }
                    }) { Text("按保留策略清理") }
                }
            }
        }

        item {
            SectionCard(title = "H. 请求预览（不发送）") {
                OutlinedTextField(
                    value = previewInstruction,
                    onValueChange = { previewInstruction = it },
                    label = { Text("用户问题") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = {
                    previewText = container.previewRequestTextForTest(previewInstruction)
                }) { Text("生成注入文本预览") }
                if (previewText.isNotBlank()) {
                    Text(previewText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    selectedEvidence?.let { record ->
        EvidenceDetailDialog(
            record = record,
            blocks = selectedBlocks,
            image = selectedImage,
            onRerunFilter = {
                scope.launch {
                    selectedBlocks = container.rerunFilterForTest(record.evidenceId)
                    actionMessage = "已重新执行过滤：${selectedBlocks.size} 行"
                    refreshTick += 1
                }
            },
            onDismiss = {
                selectedEvidence = null
                selectedBlocks = emptyList()
                selectedImage = null
            }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(key, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EvidenceRow(record: EvidenceRecord, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "${record.sourceType.name} · ${formatTime(record.capturedAtMillis)} · ${record.evidenceId}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "来源 ${record.sourceApp ?: "-"} · 题目 ${record.questionId ?: "-"} · " +
                    "原始字符 ${record.rawText.length} · 图像 ${record.imageRef ?: "无"} · ${record.availability.name}",
                fontSize = 10.sp
            )
            Text(
                record.rawText.take(80).replace('\n', ' ').ifBlank { "（空）" },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun EvidenceDetailDialog(
    record: EvidenceRecord,
    blocks: List<ClassifiedBlock>,
    image: Bitmap?,
    onRerunFilter: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("证据详情 ${record.evidenceId}") },
        text = {
            LazyColumn(modifier = Modifier.height(420.dp)) {
                item {
                    Text("原始文本（不可改写）：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        record.rawText.ifBlank { "（空）" },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                item {
                    Text(
                        "过滤结果（规则 ${blocks.firstOrNull()?.ruleVersion ?: "-"}）：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(blocks) { block ->
                    Text(
                        "${block.verdict.name} · ${block.category.name} · ${block.text.take(60)}",
                        fontSize = 10.sp,
                        color = if (block.verdict == BlockVerdict.EXCLUDE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        "理由：${block.reason}",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (image != null) {
                    item {
                        Text("图像证据：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = "证据图像",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRerunFilter) { Text("重跑过滤") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
