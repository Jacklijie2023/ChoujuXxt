package `fun`.kirari.hanako.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import `fun`.kirari.hanako.chat.ChatModelCatalog
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.chat.splitSessionsByTime
import `fun`.kirari.hanako.copyToClipboardWithToast
import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.data.ChatSession
import `fun`.kirari.hanako.data.ModelProviderConfig
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 完整 AI 聊天页面（对标成熟 AI 助手移动端观感）。
 *
 * 页面自带轻量顶栏并处理状态栏 inset（应用层在聊天页不渲染全局 TopAppBar，
 * 见 [fun.kirari.hanako.ui.HanakoApp]），让消息区面积最大化。
 *
 * 顶栏三段：侧边栏按钮（对话历史抽屉）/ 模型切换（[ModelChip]）/ 新建对话。
 * 历史抽屉：时间分组（今天 / 更早）+ 搜索 + 淡紫选中态 + 切换 / 重命名 / 删除。
 * 消息气泡：用户右 / AI 左（头像 + 「Hanako」），AI 下快捷 pill + 操作栏（复制 / 重新生成 / 语音占位）。
 * 输入区：大圆角容器 + 「+」菜单（附件 / 联网 / 思考占位）+ 语音占位 + 发送 / 停止。
 *
 * 会话状态来自全局单例 [ChatSessionHolder]（由 `AppContainer` 注入），
 * 不在本页面使用局部 `remember` 持有会话，以便迷你聊天共享同一实例。
 *
 * @param providers `settings.providers` 快照，作为模型选择弹窗的数据源。
 * @param catalog 聊天模型全量列表内存缓存（聊天页与迷你窗共用）。
 * @param isActive 当前页是否为 pager 的活跃页；切到其它 Tab 置为 false，用于立即收起抽屉，
 *   避免 pager 滑动/切页动画期间抽屉残留闪现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(
    holder: ChatSessionHolder,
    providers: List<ModelProviderConfig>,
    catalog: ChatModelCatalog,
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val session by holder.session.collectAsState()
    val sessions by holder.sessions.collectAsState()
    val isStreaming = session.isStreaming
    var input by rememberSaveable { mutableStateOf("") }
    var panelExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 不使用 rememberDrawerState：其内部为 rememberSaveable，会把「已展开」状态持久化到 pager
    // 页的 saveable 容器；切到其它 Tab 再回来时会自动恢复为展开，导致抽屉卡住/切页闪现，
    // 且只有重启进程才能清除。这里用普通 remember，保证每次进入聊天页都从「收起」开始。
    val drawerState = remember { DrawerState(DrawerValue.Closed) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 聊天页失活（切到其它 Tab）时立即收起抽屉，避免切页动画中残留显示。
    LaunchedEffect(isActive) {
        if (!isActive) drawerState.snapTo(DrawerValue.Closed)
    }

    // 相册读取权限（输入面板展开时按需检查与请求）。
    var hasPhotoPermission by remember { mutableStateOf(hasPhotoReadPermission(context)) }
    val photoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPhotoPermission = granted
    }
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                SessionHistoryDrawer(
                    // 仅展示「已使用」的会话（有消息）；空的新会话不进入历史列表。
                    sessions = sessions.filter { it.messages.isNotEmpty() },
                    currentSessionId = session.id,
                    onClose = { scope.launch { drawerState.close() } },
                    onSelect = { id ->
                        holder.selectSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onRename = holder::renameSession,
                    onDelete = holder::deleteSession,
                    onDeleteSessions = holder::deleteSessions,
                    onTogglePin = holder::togglePin,
                    onNew = {
                        holder.newSession()
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 轻量顶栏（无分隔线）：侧边栏 / 模型切换 / 新建对话
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "对话历史")
                }
                ModelChip(
                    holder = holder,
                    providers = providers,
                    catalog = catalog,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { holder.newSession() }) {
                    Icon(Icons.Default.Add, contentDescription = "新建对话")
                }
            }

            if (session.messages.isEmpty()) {
                // 空会话默认招呼词（参考通义千问欢迎语）：仅 UI 展示，不写入会话历史。
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
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(session.messages, key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            onRetry = holder::retry,
                            onCopy = {
                                copyToClipboardWithToast(
                                    context = context,
                                    label = "Hanako 回复",
                                    text = message.content,
                                    toastText = "已复制"
                                )
                            },
                            onRegenerate = holder::retry
                        )
                    }
                }
            }

            // 大圆角输入容器：「+」（点击旋转 45° 变 ×）/ 输入框 / 麦克风 / 发送或停止
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .imePadding()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                            placeholder = { Text("询问任何问题…") },
                            shape = RoundedCornerShape(28.dp),
                            maxLines = 4,
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
                            Icon(Icons.Default.Mic, contentDescription = "语音输入")
                        }
                        if (isStreaming) {
                            IconButton(onClick = { holder.stop() }) {
                                Icon(Icons.Default.Stop, contentDescription = "停止")
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val text = input
                                    input = ""
                                    holder.send(text)
                                },
                                enabled = input.isNotBlank()
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "发送")
                            }
                        }
                    }
                }

                // 「+」展开后的功能面板：拍照 / 相册 / 本地文件 + 手机照片网格（整改点 4/5）。
                AnimatedVisibility(
                    visible = panelExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    ChatInputActionPanel(
                        hasPermission = hasPhotoPermission,
                        onRequestPermission = {
                            photoPermissionLauncher.launch(photoReadPermission())
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/** 空会话默认招呼词（参考通义千问欢迎语）：头像 + 名称 + 圆角气泡，仅 UI 展示，不写入会话历史。 */
@Composable
internal fun GreetingBubble(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HanakoAvatar(size = 28.dp)
            Text(
                text = "Hanako",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 6.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = "嗨，我是 Hanako，你的备考助手。刷题、理思路、做规划，或者是找点创作灵感，交给我准没错。现在我们从哪里开始？",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** 对话历史抽屉（整改点 2）：标题 + 搜索 + 置顶/今天/更早分组 + 长按菜单 + 多选批量删除 + 新建。
 *
 * 主聊天页与迷你窗共用同一实现。@param overlaySafe 为 true 时（迷你窗 overlay Service 宿主）：
 * 删除确认 / 重命名 / 长按菜单全部改用窗口内覆盖渲染，因为 AlertDialog / Popup 会创建新窗口，
 * 而 overlay Service 无 Activity token，会触发 BadTokenException 闪退。
 */
@Composable
internal fun SessionHistoryDrawer(
    sessions: List<ChatSession>,
    currentSessionId: String,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteSessions: (List<String>) -> Unit,
    onTogglePin: (String) -> Unit,
    onNew: () -> Unit,
    overlaySafe: Boolean = false
) {
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // 待确认删除的会话（删除为危险操作，先弹确认框再真正删除）。
    var pendingDeleteIds by remember { mutableStateOf<Set<String>?>(null) }
    // overlaySafe：长按菜单状态提升到抽屉层，用窗口内覆盖渲染（Popup 会创建新窗口触发闪退）。
    var contextMenuTarget by remember { mutableStateOf<ChatSession?>(null) }

    val filtered = remember(sessions, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            sessions
        } else {
            sessions.filter { it.title.contains(q, ignoreCase = true) }
        }
    }
    // 置顶会话排前，其余按时间分组（今天 / 更早）。
    val (pinnedList, normalList) = remember(filtered) { filtered.partition { it.pinned } }
    val (todayList, earlierList) = remember(normalList) { splitSessionsByTime(normalList) }

    // 抽屉主体包在 Box 中：overlaySafe 模式下弹层在窗口内覆盖渲染（不创建新窗口）。
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectionMode) "多选会话" else "历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (selectionMode) {
                // 全选：选中当前列表全部会话；再次点击时全部取消选中（切换）。
                TextButton(
                    onClick = {
                        val allSelected = filtered.isNotEmpty() && selectedIds.size == filtered.size
                        selectedIds = if (allSelected) emptySet() else filtered.map { it.id }.toSet()
                    }
                ) {
                    Text("全选")
                }
            } else {
                IconButton(onClick = { searchVisible = !searchVisible }) {
                    Icon(Icons.Default.Search, contentDescription = "搜索会话")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "收起抽屉")
                }
            }
        }

        if (searchVisible && !selectionMode) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("按标题搜索") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
        }

        when {
            sessions.isEmpty() -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无会话，点击底部新建对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            filtered.isEmpty() -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "没有匹配的会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (pinnedList.isNotEmpty()) {
                        item(key = "__group_pinned__") {
                            HistorySectionLabel(text = "置顶")
                        }
                        items(pinnedList, key = { it.id }) { item ->
                            SessionHistoryDrawerRow(
                                session = item,
                                isCurrent = item.id == currentSessionId,
                                selectionMode = selectionMode,
                                selected = item.id in selectedIds,
                                overlaySafe = overlaySafe,
                                contextMenuOpen = contextMenuTarget?.id == item.id,
                                onContextMenu = { contextMenuTarget = item },
                                onSelect = onSelect,
                                onToggleSelect = { id ->
                                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                },
                                onRenameRequest = { session ->
                                    renameTarget = session
                                    renameText = session.title
                                },
                                onDelete = { id -> pendingDeleteIds = setOf(id) },
                                onTogglePin = onTogglePin,
                                onEnterMultiSelect = { id ->
                                    selectionMode = true
                                    selectedIds = setOf(id)
                                }
                            )
                        }
                    }
                    if (todayList.isNotEmpty()) {
                        item(key = "__group_today__") {
                            HistorySectionLabel(text = "今天")
                        }
                        items(todayList, key = { it.id }) { item ->
                            SessionHistoryDrawerRow(
                                session = item,
                                isCurrent = item.id == currentSessionId,
                                selectionMode = selectionMode,
                                selected = item.id in selectedIds,
                                overlaySafe = overlaySafe,
                                contextMenuOpen = contextMenuTarget?.id == item.id,
                                onContextMenu = { contextMenuTarget = item },
                                onSelect = onSelect,
                                onToggleSelect = { id ->
                                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                },
                                onRenameRequest = { session ->
                                    renameTarget = session
                                    renameText = session.title
                                },
                                onDelete = { id -> pendingDeleteIds = setOf(id) },
                                onTogglePin = onTogglePin,
                                onEnterMultiSelect = { id ->
                                    selectionMode = true
                                    selectedIds = setOf(id)
                                }
                            )
                        }
                    }
                    if (earlierList.isNotEmpty()) {
                        item(key = "__group_earlier__") {
                            HistorySectionLabel(text = "更早")
                        }
                        items(earlierList, key = { it.id }) { item ->
                            SessionHistoryDrawerRow(
                                session = item,
                                isCurrent = item.id == currentSessionId,
                                selectionMode = selectionMode,
                                selected = item.id in selectedIds,
                                overlaySafe = overlaySafe,
                                contextMenuOpen = contextMenuTarget?.id == item.id,
                                onContextMenu = { contextMenuTarget = item },
                                onSelect = onSelect,
                                onToggleSelect = { id ->
                                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                },
                                onRenameRequest = { session ->
                                    renameTarget = session
                                    renameText = session.title
                                },
                                onDelete = { id -> pendingDeleteIds = setOf(id) },
                                onTogglePin = onTogglePin,
                                onEnterMultiSelect = { id ->
                                    selectionMode = true
                                    selectedIds = setOf(id)
                                }
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "已选 ${selectedIds.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        selectedIds = emptySet()
                        selectionMode = false
                    }
                ) {
                    Text("取消")
                }
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = {
                        if (selectedIds.isNotEmpty()) pendingDeleteIds = selectedIds
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                    Text("新建对话")
                }
            }
        }
        }

        // overlaySafe（迷你窗 overlay Service 宿主）弹层：全部窗口内覆盖渲染，不创建新窗口。
        if (overlaySafe) {
            // 长按会话功能菜单
            contextMenuTarget?.let { target ->
                OverlayScrim(
                    onDismiss = { contextMenuTarget = null },
                    alignment = Alignment.BottomCenter
                ) {
                    Surface(
                        modifier = Modifier.padding(12.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        shadowElevation = 10.dp
                    ) {
                        SessionRowContextMenu(
                            pinned = target.pinned,
                            onRename = {
                                contextMenuTarget = null
                                renameTarget = target
                                renameText = target.title
                            },
                            onMultiSelect = {
                                contextMenuTarget = null
                                selectionMode = true
                                selectedIds = setOf(target.id)
                            },
                            onTogglePin = {
                                contextMenuTarget = null
                                onTogglePin(target.id)
                            },
                            onDelete = {
                                contextMenuTarget = null
                                pendingDeleteIds = setOf(target.id)
                            }
                        )
                    }
                }
            }

            // 删除确认（危险操作先确认再执行）
            pendingDeleteIds?.let { ids ->
                val singleId = ids.singleOrNull()
                val targetTitle = singleId?.let { id ->
                    sessions.firstOrNull { it.id == id }?.title
                }.orEmpty()
                OverlayScrim(onDismiss = { pendingDeleteIds = null }) {
                    DeleteConfirmCard(
                        message = if (singleId != null) {
                            "确定要删除会话「$targetTitle」吗？删除后不可恢复。"
                        } else {
                            "确定要删除选中的 ${ids.size} 个会话吗？删除后不可恢复。"
                        },
                        onConfirm = {
                            if (singleId != null) onDelete(singleId) else onDeleteSessions(ids.toList())
                            selectedIds = emptySet()
                            selectionMode = false
                            pendingDeleteIds = null
                        },
                        onDismiss = { pendingDeleteIds = null }
                    )
                }
            }

            // 重命名
            renameTarget?.let { target ->
                OverlayScrim(onDismiss = { renameTarget = null }) {
                    RenameCard(
                        initialTitle = renameText,
                        onConfirm = { title ->
                            onRename(target.id, title)
                            renameTarget = null
                        },
                        onDismiss = { renameTarget = null }
                    )
                }
            }
        }

        // 非 overlaySafe（Activity 宿主）：沿用 AlertDialog 弹层。
        if (!overlaySafe) {
            pendingDeleteIds?.let { ids ->
                val singleId = ids.singleOrNull()
                val targetTitle = singleId?.let { id ->
                    sessions.firstOrNull { it.id == id }?.title
                }.orEmpty()
                AlertDialog(
                    onDismissRequest = { pendingDeleteIds = null },
                    title = { Text("删除会话") },
                    text = {
                        Text(
                            text = if (singleId != null) {
                                "确定要删除会话「$targetTitle」吗？删除后不可恢复。"
                            } else {
                                "确定要删除选中的 ${ids.size} 个会话吗？删除后不可恢复。"
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (singleId != null) onDelete(singleId) else onDeleteSessions(ids.toList())
                                selectedIds = emptySet()
                                selectionMode = false
                                pendingDeleteIds = null
                            }
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteIds = null }) {
                            Text("取消")
                        }
                    }
                )
            }

            if (renameTarget != null) {
                RenameSessionDialog(
                    initialTitle = renameText,
                    onConfirm = { title ->
                        renameTarget?.let { onRename(it.id, title) }
                        renameTarget = null
                    },
                    onDismiss = { renameTarget = null }
                )
            }
        }
    }
}

/** 抽屉内单行包装：统一把会话级回调转发给 [SessionHistoryRow]。 */
@Composable
private fun SessionHistoryDrawerRow(
    session: ChatSession,
    isCurrent: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    overlaySafe: Boolean,
    contextMenuOpen: Boolean,
    onContextMenu: () -> Unit,
    onSelect: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onRenameRequest: (ChatSession) -> Unit,
    onDelete: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onEnterMultiSelect: (String) -> Unit
) {
    SessionHistoryRow(
        session = session,
        isCurrent = isCurrent,
        selectionMode = selectionMode,
        selected = selected,
        overlaySafe = overlaySafe,
        contextMenuOpen = contextMenuOpen,
        onContextMenu = onContextMenu,
        onSelect = { onSelect(session.id) },
        onToggleSelect = { onToggleSelect(session.id) },
        onRenameRequest = { onRenameRequest(session) },
        onDelete = { onDelete(session.id) },
        onTogglePin = { onTogglePin(session.id) },
        onEnterMultiSelect = { onEnterMultiSelect(session.id) }
    )
}

/** 历史时间分组标签。 */
@Composable
private fun HistorySectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
    )
}

/** 历史列表单行：淡紫选中态 + 标题 + 摘要；长按震动弹出功能菜单（重命名 / 多选 / 置顶 / 删除）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionHistoryRow(
    session: ChatSession,
    isCurrent: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    overlaySafe: Boolean,
    contextMenuOpen: Boolean,
    onContextMenu: () -> Unit,
    onSelect: () -> Unit,
    onToggleSelect: () -> Unit,
    onRenameRequest: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onEnterMultiSelect: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // 菜单打开期间该行保持选中高亮；行高用于把菜单定位到行上方并留出与左边一致的间距。
    var rowHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val firstUserContent = session.messages.firstOrNull { it.role == ChatRole.User }?.content.orEmpty()
    val summary = if (firstUserContent.isNotBlank()) {
        "${session.messages.size} 条消息 · ${firstUserContent.take(14)}"
    } else {
        "${session.messages.size} 条消息"
    }
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .onSizeChanged { rowHeightPx = it.height }
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() else onSelect() },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        // overlaySafe：菜单由抽屉层窗口内覆盖渲染；否则用 Popup 新窗口。
                        if (overlaySafe) onContextMenu() else menuExpanded = true
                    }
                ),
            shape = RoundedCornerShape(14.dp),
            color = when {
                // 长按弹出菜单期间，当前行保持选中高亮效果。
                (if (overlaySafe) contextMenuOpen else menuExpanded) ->
                    MaterialTheme.colorScheme.primaryContainer
                selected -> MaterialTheme.colorScheme.primaryContainer
                isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else -> Color.Transparent
            }
        ) {
            Row(
                modifier = Modifier.padding(
                    start = if (selectionMode) 8.dp else 14.dp,
                    end = 6.dp,
                    top = 8.dp,
                    bottom = 8.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (session.pinned) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = "已置顶",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // overlaySafe：菜单由抽屉层窗口内覆盖渲染，此处不再用 Popup（Popup 会创建新窗口触发闪退）。
        if (menuExpanded) {
            // 菜单定位：左缘距屏幕边界 12dp（与行内边距一致），
            // 且整体位于被长按行上方、与行顶保持同样的 12dp 间距。
            val edgePx = with(density) { 12.dp.toPx() }.roundToInt()
            Popup(
                onDismissRequest = { menuExpanded = false },
                alignment = Alignment.BottomStart,
                offset = IntOffset(edgePx, -(rowHeightPx + edgePx)),
                properties = PopupProperties(focusable = true)
            ) {
                SessionRowContextMenu(
                    pinned = session.pinned,
                    onRename = {
                        menuExpanded = false
                        onRenameRequest()
                    },
                    onMultiSelect = {
                        menuExpanded = false
                        onEnterMultiSelect()
                    },
                    onTogglePin = {
                        menuExpanded = false
                        onTogglePin()
                    },
                    onDelete = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

/** 长按历史会话弹出的功能菜单（图 2 形态）：圆角卡片 + 图标行，删除项红色。 */
@Composable
private fun SessionRowContextMenu(
    pinned: Boolean,
    onRename: () -> Unit,
    onMultiSelect: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.width(160.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column {
            ContextMenuItem(
                icon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = "重命名",
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onRename
            )
            ContextMenuItem(
                icon = { Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = "多选",
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onMultiSelect
            )
            ContextMenuItem(
                icon = { Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = if (pinned) "取消置顶" else "置顶",
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onTogglePin
            )
            ContextMenuItem(
                icon = { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = "删除",
                tint = MaterialTheme.colorScheme.error,
                danger = true,
                onClick = onDelete
            )
        }
    }
}

/** 菜单单项：图标 + 文字，整行可点击。 */
@Composable
private fun ContextMenuItem(
    icon: @Composable () -> Unit,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            fontWeight = if (danger) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/** 会话重命名弹窗：输入新标题并持久化。 */
@Composable
private fun RenameSessionDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("会话标题") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 迷你窗窗口内覆盖层：半透明遮罩 + 点击遮罩关闭，弹层内容在窗口内渲染。
 *
 * 仅用于 overlaySafe 模式（overlay Service 无 Activity token），
 * 因为 AlertDialog / Popup 都会创建新窗口触发 BadTokenException。
 */
@Composable
private fun OverlayScrim(
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = alignment
    ) {
        content()
    }
}

/** 迷你窗窗口内删除确认卡片（替代 AlertDialog）。 */
@Composable
private fun DeleteConfirmCard(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.widthIn(max = 280.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "删除会话",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onConfirm) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 迷你窗窗口内重命名卡片（替代 AlertDialog）。 */
@Composable
private fun RenameCard(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialTitle) }
    Surface(
        modifier = Modifier.widthIn(max = 280.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "重命名会话",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("会话标题") },
                singleLine = true
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                    Text("确定")
                }
            }
        }
    }
}
