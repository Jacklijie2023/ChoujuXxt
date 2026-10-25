package `fun`.kirari.hanako.overlay

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.HanakoApplication
import `fun`.kirari.hanako.chat.ChatModelCatalog
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.data.ChatImageStore
import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.screencontext.ScreenTextViewerActivity
import `fun`.kirari.hanako.screencontext.reportBelongsToCurrentSend
import `fun`.kirari.hanako.screencontext.screenReadReportLines
import `fun`.kirari.hanako.screencontext.realtimeCollectionDiagnosticLines
import `fun`.kirari.hanako.screencontext.realtimePerceptionStatusText
import `fun`.kirari.hanako.ui.ChatImageViewer
import `fun`.kirari.hanako.ui.ChatInputActionPanel
import `fun`.kirari.hanako.ui.ChatMessageItem
import `fun`.kirari.hanako.ui.ChatSettingsContent
import `fun`.kirari.hanako.ui.GreetingBubble
import `fun`.kirari.hanako.ui.ModelChip
import `fun`.kirari.hanako.ui.SessionHistoryDrawer
import `fun`.kirari.hanako.ui.hasPhotoReadPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 后台持续采集诊断区的刷新间隔（毫秒）。
 *
 * 采集器只提供同步快照读取（不截图、不发请求），因此用轮询即可让顶部状态条反映**当前**采集情况，
 * 而不必为它引入额外的状态流；间隔取 1 秒，兼顾实时性与耗电。
 */
private const val REALTIME_STATUS_REFRESH_MILLIS = 1_000L

/**
 * 迷你聊天小窗（悬浮窗形态，圆角卡片）。
 *
 * 与主 App「聊天」页共享同一个 [ChatSessionHolder] 单例，会话与历史完全一致，
 * 不做局部状态持有。气泡与输入框样式与聊天页统一（[ChatMessageItem] 精简模式）。
 *
 * 功能与主聊天页同步：
 * - 顶栏：侧边栏（历史抽屉）/ 模型切换 / 新建对话 / 打开主 App / 关闭。
 * - 空会话内置默认招呼词（[GreetingBubble]，仅 UI 展示不写入历史）。
 * - 输入区：「+」展开功能面板（拍照 / 相册 / 本地文件 + 照片网格）；
 *   网格里点选的照片进入附件区，发送时与文字一起发出并在气泡中回显缩略图。
 * - 历史抽屉 / 聊天设置 / 功能面板内弹层全部窗口内覆盖渲染：
 *   overlay Service 宿主无 Activity token，Dialog / DropdownMenu / Popup 会触发 BadTokenException。
 *
 * @param providers `settings.providers` 快照，作为模型选择弹窗的数据源。
 * @param catalog 聊天模型全量列表内存缓存（与主聊天页共用同一实例）。
 * @param backgroundAlpha 迷你聊天窗整体透明度（0.3~1.0，来自设置页「迷你聊天窗」）。
 * @param onHeaderDrag 顶部标题栏拖拽回调，参数为本次增量像素位移（更新窗口位置）。
 * @param onOpenMainApp 点击「打开」跳转主 App 聊天页。
 * @param onClose 关闭迷你小窗。
 * @param onLongPressResize 长按迷你聊天窗，在 OverlayService 侧切换小/大窗口尺寸。
 * @param pageContext 发送时附带的当前页面上下文（分析页内容）；返回 null/空白表示普通聊天。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MiniChatPanel(
    holder: ChatSessionHolder,
    providers: List<ModelProviderConfig>,
    catalog: ChatModelCatalog,
    backgroundAlpha: Float = 0.92f,
    onHeaderDrag: (dx: Float, dy: Float) -> Unit,
    onOpenMainApp: () -> Unit,
    onClose: () -> Unit,
    onLongPressResize: () -> Unit = {},
    pageContext: (() -> String?)? = null,
    modifier: Modifier = Modifier
) {
    val resizeInteractionSource = remember { MutableInteractionSource() }
    val session by holder.session.collectAsState()
    val sessions by holder.sessions.collectAsState()
    val isStreaming = session.isStreaming
    var input by rememberSaveable { mutableStateOf("") }
    // 「聊天设置」面板：overlay Service 无 Activity token，无法弹 Dialog，
    // 改为在悬浮窗内覆盖渲染（见下方 if (showSettings) 层）。
    var showSettings by remember { mutableStateOf(false) }
    // 对话历史抽屉（与主聊天页侧边栏功能一致）：窗口内覆盖渲染。
    var showDrawer by remember { mutableStateOf(false) }
    // 「+」输入功能面板（拍照 / 相册 / 本地文件 + 照片网格）展开状态。
    var panelExpanded by remember { mutableStateOf(false) }
    // 全屏图片查看：点击气泡里的已发送图片时记录其本地路径（null 表示未打开）。
    var viewingImagePath by remember { mutableStateOf<String?>(null) }
    // 「+」面板里点选的图片：与主聊天页同一语义（选中即进入附件区，发送时随消息一起发出）。
    // 迷你窗宿主无 Activity token，无法拉起系统相册页，因此直接用窗口内网格选图。
    var imageAttachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val toggleImageAttachment: (Uri) -> Unit = { uri ->
        val key = uri.toString()
        imageAttachments = if (imageAttachments.any { it.toString() == key }) {
            imageAttachments.filterNot { it.toString() == key }
        } else {
            imageAttachments + uri
        }
    }
    val scope = rememberCoroutineScope()
    // 实时感知状态条点击展开的本次诊断区域。
    var showDiagnostics by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    // 实时屏幕感知运行状态（来自全局 ScreenContextManager，供拍摄中状态提示）。
    val appContainer = (context.applicationContext as HanakoApplication).container
    val screenContextActive by appContainer.screenContextManager.active.collectAsState()
    val screenContextSnapshot by appContainer.screenContextManager.snapshot.collectAsState()
    val screenContextReport by appContainer.screenContextManager.lastReport.collectAsState()
    // 阶段 2：后台持续采集器的**实时**状态（启用/停止、最近读取成功/空文本/失败、最近滚动、
    // 最近探测、最近后台 OCR、最近池更新）。采集器对外只提供同步快照读取（不截图、不发请求），
    // 因此这里按固定间隔轮询刷新 —— 使顶部状态条反映**当前**采集情况，而不是打开面板时的一次旧快照。
    var realtimeCollectionStatus by remember {
        mutableStateOf(appContainer.realtimeViewportCollector.status())
    }
    LaunchedEffect(screenContextActive) {
        if (!screenContextActive) return@LaunchedEffect
        while (true) {
            realtimeCollectionStatus = appContainer.realtimeViewportCollector.status()
            delay(REALTIME_STATUS_REFRESH_MILLIS)
        }
    }
    // 当前会话最近一条助手消息 id 即「本次/最近一次发送」的请求追踪 ID
    // （ChatSessionHolder 以 assistantId 同时作为聊天链路与屏幕读取链路的 traceId）。
    // 据此判断面板中的屏幕读取报告是否属于当前这次发送，避免把上一次读取的旧报告
    // 误当成本次发送的结果（不覆盖真实历史数据，仅如实标注归属）。
    val lastAssistantTraceId = session.messages.lastOrNull { it.role == ChatRole.Assistant }?.id
    // 相册读取权限：迷你窗宿主是 overlay Service（无 Activity token），
    // 不能用 rememberLauncherForActivityResult 申请运行时权限；每次展开面板时刷新状态，
    // 无权限时提示用户到主界面聊天页授权（见 ChatInputActionPanel 权限提示卡）。
    var hasPhotoPermission by remember { mutableStateOf(hasPhotoReadPermission(context)) }
    LaunchedEffect(panelExpanded) {
        if (panelExpanded) {
            hasPhotoPermission = hasPhotoReadPermission(context)
        }
    }

    LaunchedEffect(session.messages.size, session.messages.lastOrNull()?.content) {
        if (session.messages.isNotEmpty()) {
            listState.animateScrollToItem(session.messages.lastIndex)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .alpha(backgroundAlpha)
            // 长按整窗触发放大/缩小切换（onLongPressResize 由 OverlayService 宿主实现）。
            .combinedClickable(
                interactionSource = resizeInteractionSource,
                indication = null,
                onClick = {},
                onLongClick = onLongPressResize
            ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部标题栏（与主聊天页对齐）：侧边栏（历史抽屉）/ 模型切换 / 新建对话 / 打开主 App / 关闭。
                // 整条可拖拽移动窗口。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onHeaderDrag(dragAmount.x, dragAmount.y)
                            }
                        }
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 侧边栏按钮：打开对话历史抽屉（与主聊天页功能一致，窗口内覆盖渲染）。
                    IconButton(onClick = { showDrawer = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "对话历史",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    // 迷你窗宿主为 overlay Service（无 Activity token），不能在窗口内弹 Dialog，
                    // 点击胶囊时回调宿主，由宿主在窗口内覆盖渲染「聊天设置」。
                    ModelChip(
                        holder = holder,
                        providers = providers,
                        catalog = catalog,
                        useBottomSheet = false,
                        onOpenSettings = { showSettings = true }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { holder.newSession() }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "新建对话",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onOpenMainApp, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "打开主界面聊天页",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 实时屏幕感知运行状态提示（仅在实时模式开启时显示）；点击展开本次诊断。
                if (screenContextActive) {
                    // 顶部状态**绑定后台持续采集器的实时状态**（阶段 2 修复）：
                    // 从而把「后台采集未启动」「已启动但没收到事件」「当前视口没读到文本」
                    // 「最近一次后台 OCR 失败」「文本池已更新」区分开，不再统一显示成
                    // 「未获取到屏幕内容」；仅在采集器尚无任何观察时才回退到既有单次读取语义。
                    val statusText = realtimePerceptionStatusText(
                        active = screenContextActive,
                        snapshot = screenContextSnapshot,
                        report = screenContextReport,
                        collection = realtimeCollectionStatus
                    )
                    val collectorHasData = realtimeCollectionStatus.lastEventKind != null ||
                        realtimeCollectionStatus.lastTextReadStatus != null ||
                        realtimeCollectionStatus.fragmentCount > 0
                    // 报告归属：本次发送 vs 最近一次读取（可能是上一次请求留下的旧报告）。
                    // 仅当顶部文案仍来自单次读取报告时才追加该归属标注，避免与实时采集状态混淆。
                    val reportIsForCurrentSend = reportBelongsToCurrentSend(screenContextReport, lastAssistantTraceId)
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            text = when {
                                screenContextReport == null || collectorHasData -> "$statusText ▾"
                                reportIsForCurrentSend -> "$statusText ▾"
                                else -> "$statusText（最近一次读取，非本次发送）▾"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { showDiagnostics = !showDiagnostics }
                                .padding(vertical = 2.dp)
                        )
                        if (showDiagnostics) {
                            // 完整文本查看入口必须**最先**出现在展开区顶部。
                            // 之前的实现把它放在诊断行与 400 字预览之后：诊断块是主 Column 的
                            // 非 weight 直接子项，其固有高度会超出迷你窗可视区，末尾内容被窗口
                            // 裁剪，入口因此既看不到也点不到。现在移到顶部，无需滚动即可点击。
                            // 绑定 traceId 一律取自**当前诊断报告自身**（screenContextReport.traceId），
                            // 而不是全局 lastReport，保证题目 A / 题目 B 各自打开自己的文本。
                            val viewerTraceId = screenContextReport?.traceId
                            if (!viewerTraceId.isNullOrBlank()) {
                                TextButton(
                                    onClick = {
                                        context.startActivity(
                                            ScreenTextViewerActivity.intent(context, viewerTraceId)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "打开完整 OCR 文本",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = "请求 trace: $viewerTraceId",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                            // 诊断明细自身限高 + 内部滚动：行数再多也不会把消息区与输入框
                            // 挤出窗口可视区（同时保证顶部入口始终可见）。
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 148.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(start = 4.dp, bottom = 6.dp)
                            ) {
                                screenReadReportLines(screenContextReport).forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                                // 后台持续采集诊断（与「本次发送」相互独立，随面板每秒刷新，
                                // 只读状态：不触发截图、不触发 OCR、不发模型请求）。
                                Text(
                                    text = "— 后台持续采集（非本次请求）—",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 1.dp)
                                )
                                realtimeCollectionDiagnosticLines(realtimeCollectionStatus).forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (session.messages.isEmpty()) {
                    // 空会话默认招呼词（与主聊天页一致，仅 UI 展示不写入历史）。
                    GreetingBubble(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(session.messages, key = { it.id }) { message ->
                            ChatMessageItem(
                                message = message,
                                onRetry = holder::retry,
                                onCopy = {},
                                onRegenerate = holder::retry,
                                compact = true,
                                showQuickActions = false,
                                // 迷你窗内同样支持点击图片全屏查看（见窗口根布局最上层的 ChatImageViewer）。
                                onImageClick = { viewingImagePath = it }
                            )
                        }
                    }
                }

                // 大圆角输入容器（与聊天页统一样式，精简字号）：
                // 「+」（旋转 45° 变 ×）/ 输入框 / 麦克风 / 发送或停止
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .imePadding()
                ) {
                    // 已选图片提示：面板收起后仍能看出本次会带图发送。
                    if (imageAttachments.isNotEmpty()) {
                        Text(
                            text = "已选 ${imageAttachments.size} 张图片，发送时一并发出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val plusRotation by animateFloatAsState(
                                targetValue = if (panelExpanded) 45f else 0f,
                                label = "plusRotation"
                            )
                            IconButton(onClick = { panelExpanded = !panelExpanded }) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = if (panelExpanded) "收起输入功能" else "展开输入功能",
                                    modifier = Modifier.rotate(plusRotation)
                                )
                            }
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("询问任何问题…", style = MaterialTheme.typography.bodySmall) },
                                textStyle = MaterialTheme.typography.bodySmall,
                                shape = RoundedCornerShape(20.dp),
                                maxLines = 3,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                )
                            )
                            IconButton(
                                onClick = {
                                    Toast.makeText(context, "语音功能正在接入", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "语音输入",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isStreaming) {
                                IconButton(onClick = { holder.stop() }) {
                                    Icon(Icons.Default.Stop, contentDescription = "停止")
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        val text = input
                                        val images = imageAttachments
                                        input = ""
                                        imageAttachments = emptyList()
                                        scope.launch {
                                            // 与主聊天页同一套链路：同一份字节既发给模型，也落盘供气泡回显。
                                            val (encoded, imagePaths) = withContext(Dispatchers.IO) {
                                                val encoded = images.mapNotNull {
                                                    ChatImageStore.encodeUri(context, it)
                                                }
                                                encoded to ChatImageStore.saveAll(context, encoded)
                                            }
                                            // 分析页「继续追问」/迷你窗场景额外携带当前处理页内容作为上下文。
                                            holder.send(text, encoded, imagePaths, pageContext?.invoke())
                                        }
                                    },
                                    enabled = input.isNotBlank() || imageAttachments.isNotEmpty()
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = "发送")
                                }
                            }
                        }
                    }

                    // 「+」展开后的功能面板（与主聊天页一致；overlaySafe：弹层均在窗口内渲染）。
                    AnimatedVisibility(
                        visible = panelExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        ChatInputActionPanel(
                            hasPermission = hasPhotoPermission,
                            onRequestPermission = {
                                // 迷你窗宿主无 Activity token，无法直接拉起系统权限弹窗，
                                // 提示用户到主界面聊天页展开功能面板授权。
                                Toast.makeText(context, "请在主界面聊天页授权相册权限", Toast.LENGTH_SHORT).show()
                            },
                            // 迷你窗宿主是 overlay Service，无 Activity token，
                            // 无法直接拉起系统相机 / 相册 / 文件选择器，提示到主界面聊天页使用。
                            onCamera = {
                                Toast.makeText(context, "请在主界面聊天页使用拍照", Toast.LENGTH_SHORT).show()
                            },
                            onOpenAlbumPage = {
                                Toast.makeText(context, "点选下方照片即可加入发送", Toast.LENGTH_SHORT).show()
                            },
                            onAddPhoto = toggleImageAttachment,
                            attachedUris = imageAttachments.map { it.toString() }.toSet(),
                            onFile = {
                                Toast.makeText(context, "请在主界面聊天页选择本地文件", Toast.LENGTH_SHORT).show()
                            },
                            overlaySafe = true,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // 模型/聊天设置面板：窗口内覆盖渲染。overlay Service 宿主无 Activity token，
            // Dialog / AlertDialog / ModalBottomSheet 均会因 token 为空触发 BadTokenException，
            // 因此不用任何弹窗，直接在悬浮窗内铺满一层设置内容。
            if (showSettings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showSettings = false }) {
                                Icon(Icons.Default.Close, contentDescription = "关闭聊天设置")
                            }
                            Text(
                                text = "聊天设置",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        ChatSettingsContent(
                            holder = holder,
                            providers = providers,
                            catalog = catalog,
                            onDismiss = { showSettings = false },
                            showTitle = false
                        )
                    }
                }
            }

            // 对话历史抽屉（与主聊天页侧边栏功能一致）：窗口内覆盖渲染。
            // 宿主无 Activity token，禁用 ModalNavigationDrawer，改用全屏覆盖层。
            if (showDrawer) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    SessionHistoryDrawer(
                        // 仅展示「已使用」的会话（有消息），与主聊天页历史保持一致。
                        sessions = sessions.filter { it.messages.isNotEmpty() },
                        currentSessionId = session.id,
                        overlaySafe = true,
                        onClose = { showDrawer = false },
                        onSelect = { id ->
                            holder.selectSession(id)
                            showDrawer = false
                        },
                        onRename = holder::renameSession,
                        onDelete = holder::deleteSession,
                        onDeleteSessions = holder::deleteSessions,
                        onTogglePin = holder::togglePin,
                        onNew = {
                            holder.newSession()
                            showDrawer = false
                        }
                    )
                }
            }

            // 已发送图片的全屏查看层：窗口内覆盖渲染（Service 宿主无 Activity token，不能用 Dialog）。
            ChatImageViewer(
                imagePath = viewingImagePath,
                onDismiss = { viewingImagePath = null }
            )
        }
    }
}
