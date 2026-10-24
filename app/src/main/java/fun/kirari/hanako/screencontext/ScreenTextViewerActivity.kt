package `fun`.kirari.hanako.screencontext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.HanakoApplication
import `fun`.kirari.hanako.ui.theme.HanakoTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实时采集诊断区的刷新间隔（毫秒）。
 *
 * 诊断区只读取采集器**内存中已有**的状态快照（不截屏、不重新读屏、不发请求），
 * 因此按 1 秒轮询即可反映当前采集情况，同时避免无意义的过度刷新。
 */
private const val REALTIME_STATUS_REFRESH_MILLIS = 1_000L

/**
 * 屏幕识别「完整文本」查看界面（独立全屏 Activity）。
 *
 * 为什么需要它：迷你聊天窗口高度有限且不支持完整滚动，无法检查本次请求到底把哪些屏幕文本
 * 送进了模型。这里按 [EXTRA_TRACE_ID] 从 [ScreenContextManager] 的**请求级快照**取出
 * 该次请求实际使用的报告，展示 [ScreenReadReport.injectedText] 的**完整文本**（不做任何
 * 截断、整理或纠错）。
 *
 * 除完整文本外，还按同一 traceId 展示 [CaptureFrameStore] 记录的**本次送入本地 OCR 的那一帧**
 * 内存快照，用于直接判断「悬浮窗是否被拍进截图 / 是否截到隐藏前的旧帧 / 截图干净但 OCR 错乱」，
 * 无需再靠字符数与指纹猜测。
 *
 * 关键约束（务必保持）：
 * - 只按 traceId 取「该次请求」的快照，**不**在打开界面时重新读取当前屏幕上下文；
 * - 因此快速切题、缓存或延迟回调都不会把上一题的文本显示成本次结果；
 * - 取不到报告或 [ScreenReadReport.injectedText] 为空时，显示明确的状态说明，
 *   绝不回退展示任何旧文本；
 * - 完整文本仅在界面内展示与复制，**不写 Logcat**。
 *
 * 阶段 2 增补：本界面额外提供一个**独立标注**的「实时持续采集 / 文本池」只读诊断区，
 * 数据来自采集器内存中已有状态（不截屏、不重新读取当前屏幕、不发起请求），
 * 与「本次请求注入模型的文本」在界面上被明确分开，避免把当前文本池误读成本次请求结果。
 *
 * 以 Activity 为载体（而非悬浮窗）是为了不影响 OverlayService 的窗口管理与屏幕捕获流程：
 * 本界面不进入截图窗口隐藏名单，关闭后原有刷题 / 聊天界面照常使用。
 */
class ScreenTextViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val requestedTraceId = intent?.getStringExtra(EXTRA_TRACE_ID)
        val container = (application as HanakoApplication).container
        // 按 traceId 查「本次请求」的报告快照；查不到即明确显示「无记录」，不读当前屏幕。
        val report = container.screenContextManager.reportFor(requestedTraceId)
        // 同样按 traceId 取「本次实际送入本地 OCR 的截图」内存快照；取不到即显示「未保留」。
        val frameSnapshot = container.captureFrameStore.snapshotFor(requestedTraceId)
        setContent {
            HanakoTheme {
                // 阶段 2：实时持续采集/文本池的只读诊断状态。读取的**只是**采集器内存中已有的状态，
                // 不截屏、不重新读取当前屏幕、不发起任何网络或模型请求；按固定间隔刷新，
                // 使诊断反映**当前**采集情况，而不是打开界面时的一次旧快照，
                // 也就不会用「重新读取当前屏幕」掩盖此前的采集失败。
                var collectionStatus by remember {
                    mutableStateOf(container.realtimeViewportCollector.status())
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        collectionStatus = container.realtimeViewportCollector.status()
                        delay(REALTIME_STATUS_REFRESH_MILLIS)
                    }
                }
                ScreenTextViewerScreen(
                    requestedTraceId = requestedTraceId,
                    report = report,
                    frameSnapshot = frameSnapshot,
                    collectionStatus = collectionStatus,
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        /** 目标请求的追踪 ID（即聊天链路的助手消息 id）。 */
        const val EXTRA_TRACE_ID = "extra_screen_text_trace_id"

        /**
         * 构造跳转本界面的 Intent。
         *
         * 调用方（迷你聊天窗，宿主为 overlay Service，无 Activity token）需附带
         * [Intent.FLAG_ACTIVITY_NEW_TASK]，否则会因非 Activity 上下文启动而抛异常。
         */
        fun intent(context: Context, traceId: String?): Intent =
            Intent(context, ScreenTextViewerActivity::class.java).apply {
                putExtra(EXTRA_TRACE_ID, traceId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

/**
 * 全屏查看界面主体。
 *
 * 布局要点：正文区放在 [Column] 的 `weight(1f)` 区域内并叠加 [verticalScroll]，
 * 保证父容器给出的是**有限高度**、滚动区域真实可用（不会被父布局裁剪成不可滚动）；
 * 文本用 [SelectionContainer] 包裹以支持长按选择与系统复制。
 */
@Composable
private fun ScreenTextViewerScreen(
    requestedTraceId: String?,
    report: ScreenReadReport?,
    frameSnapshot: CaptureFrameStore.Snapshot?,
    collectionStatus: RealtimeCollectionStatus,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val fullText = report?.injectedText
    val hasText = !fullText.isNullOrBlank()
    // 把该请求的截图内存快照解码成可展示位图：只解码一次，随快照变化重算。
    // 展示的是**本次真正送入本地 OCR 的那一帧**，不是重新截取的当前屏幕。
    val frameBitmap: ImageBitmap? = remember(frameSnapshot) {
        frameSnapshot?.jpeg?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            // 顶栏：关闭 / 标题 / 复制全文。
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                    Text(
                        text = "屏幕识别完整文本",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasText) {
                        TextButton(onClick = { copyFullText(context, fullText!!) }) {
                            Text("复制全文", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // 元数据区（固定，不参与正文滚动）：请求标识 / 来源 / 读取时间 / 状态判定。
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = "请求标识 traceId: ${report?.traceId?.ifBlank { "-" } ?: requestedTraceId?.ifBlank { "-" } ?: "-"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "文本来源: ${sourceLabel(report?.source)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (report?.bitmapWidth != null && report?.bitmapHeight != null) {
                    // 这是**真正送入本地 OCR** 的位图尺寸（在调用 OCR 的位置记录），
                    // 与下方诊断缩略图的尺寸不是一回事，避免把缩略图当成 OCR 输入尺寸。
                    Text(
                        text = "OCR 输入位图尺寸: ${report.bitmapWidth}x${report.bitmapHeight}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                report?.optionAssociation?.let { association ->
                    Text(
                        text = "选项关联可信度: $association（COMPLETE 才表示标签与正文可靠对应）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "读取时间: ${formatReadTime(report?.readAtMillis)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "状态: ${screenContextPreviewDiagnosis(report)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (hasText) {
                    Text(
                        text = "最终注入模型的文本长度: ${fullText!!.length} 字（按坐标整理后的结果，未经截断）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FrameSnapshotSection(frameSnapshot = frameSnapshot, frameBitmap = frameBitmap)

            // 阶段 2：实时持续采集 / 文本池的只读诊断区。
            RealtimePoolSection(status = collectionStatus)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            if (!hasText) {
                // 没有可取用的本次请求文本：明确说明状态，绝不用旧文本或当前屏幕冒充本次结果。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(20.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    Text(
                        text = buildString {
                            append("本次请求没有可展示的完整屏幕文本。\n\n")
                            append("原因说明：${screenContextPreviewDiagnosis(report)}\n\n")
                            append(
                                "为避免误导，本界面不会回退展示当前屏幕内容或任何历史文本。" +
                                    "请回到题目页面重新发起一次实时互动请求后再查看。"
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 正文区：有限高度（weight）内的可滚动容器，能滚到最后一行；长按可选中复制。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    SectionTitle("最终注入模型的文本（结构化整理结果）")
                    SelectionContainer {
                        Text(
                            text = fullText!!,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    // 原始 OCR 结果：与上面的整理结果并列展示，用于逐字核对
                    // 「整理过程有没有擅自增删文字」。取不到原始文本时如实说明。
                    val rawOcrText = report?.rawOcrText
                    Spacer(modifier = Modifier.height(18.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    SectionTitle("引擎原始 OCR 结果（未整理，供对比）")
                    if (rawOcrText.isNullOrBlank()) {
                        Text(
                            text = "本次请求没有可用的引擎原始文本记录。",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = rawOcrText,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/** 正文区分区标题（用于把「整理后结果」与「引擎原始结果」明确分开，避免混淆）。 */
@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)
    )
}

/**
 * 「本次送入本地 OCR 的截图」诊断区。
 *
 * 目的：直接回答「污染到底发生在哪个环节」——是悬浮窗被拍进了截图，还是截到的是隐藏前的旧帧，
 * 抑或截图干净但 OCR 引擎识别错乱。这里展示 [frameSnapshot]（由 [CaptureFrameStore] 在调用
 * OCR **之前**按 traceId 登记的内存快照），因此它与本次请求一一对应，且取不到时如实说「未保留」，
 * 绝不用重新截取的当前屏幕冒充。截图只在本机内存中，不落公共目录、不上传、不写 Logcat。
 */
@Composable
private fun FrameSnapshotSection(
    frameSnapshot: CaptureFrameStore.Snapshot?,
    frameBitmap: ImageBitmap?
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        Text(
            text = "本次送入 OCR 的截图（请求级内存快照，仅本机可见）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (frameBitmap != null) {
            val snapshot = frameSnapshot!!
            Text(
                text = "诊断缩略图尺寸: ${snapshot.width}x${snapshot.height}（由该次 OCR 输入图等比缩小，**不是** OCR 原始输入尺寸）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
            Image(
                bitmap = frameBitmap,
                contentDescription = "本次送入 OCR 的截图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Text(
                text = "未保留本次请求的输入截图快照（可能未执行本地 OCR，或快照已过期），" +
                    "因此无法据此判断截图是否被悬浮窗污染。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
        }
    }
}

/**
 * 阶段 2：「实时持续采集 / 文本池」只读诊断区。
 *
 * 与上方「本次请求注入模型的文本」严格区分：这里展示的是后台持续采集器**当前**的文本池状态
 * （采集是否启用、最近成功更新时间、累计片段数、当前题目上下文标识、最近一次分类与合并结果、
 * 池内容与完整度）。它是内存快照，不截屏、不重新读取当前屏幕、不发起任何模型请求。
 */
@Composable
private fun RealtimePoolSection(status: RealtimeCollectionStatus) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Text(
            text = "实时持续采集 / 文本池（只读诊断，非本次请求）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "以下数据来自后台持续采集的**当前**文本池，与上方「本次请求注入模型的文本」不是同一份数据；" +
                "采集器只更新本地文本池，不参与发送请求路径。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )
        // 明细区限制最大高度并支持内部滚动：避免固有高度超过可视区导致父容器裁切、内容不可达。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            realtimeCollectionDiagnosticLines(status).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "当前文本池内容（${status.poolLines.size} 行 / ${status.poolText.length} 字）:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectionContainer {
                Text(
                    text = status.poolText.ifBlank { "（空）" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 把整段完整文本写入系统剪贴板（与界面展示的文本完全一致）。 */
private fun copyFullText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        Toast.makeText(context, "当前设备不支持复制", Toast.LENGTH_SHORT).show()
        return
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("屏幕识别完整文本", text))
    Toast.makeText(context, "已复制全文（${text.length} 字）", Toast.LENGTH_SHORT).show()
}

private fun sourceLabel(source: ScreenContextSource?): String = when (source) {
    ScreenContextSource.ACCESSIBILITY -> "无障碍节点树提取"
    ScreenContextSource.LOCAL_OCR -> "本地 OCR 识别"
    ScreenContextSource.POOL_SNAPSHOT -> "同题累计快照"
    null -> "-"
}

private fun formatReadTime(millis: Long?): String {
    if (millis == null || millis <= 0L) return "-"
    return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(millis))
}
