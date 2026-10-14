package `fun`.kirari.hanako.overlay

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.chat.ChatModelCatalog
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.ui.ChatInputActionPanel
import `fun`.kirari.hanako.ui.ChatMessageItem
import `fun`.kirari.hanako.ui.ChatSettingsContent
import `fun`.kirari.hanako.ui.GreetingBubble
import `fun`.kirari.hanako.ui.ModelChip
import `fun`.kirari.hanako.ui.SessionHistoryDrawer
import `fun`.kirari.hanako.ui.hasPhotoReadPermission

/**
 * 迷你聊天小窗（悬浮窗形态，圆角卡片）。
 *
 * 与主 App「聊天」页共享同一个 [ChatSessionHolder] 单例，会话与历史完全一致，
 * 不做局部状态持有。气泡与输入框样式与聊天页统一（[ChatMessageItem] 精简模式）。
 *
 * 功能与主聊天页同步：
 * - 顶栏：侧边栏（历史抽屉）/ 模型切换 / 新建对话 / 打开主 App / 关闭。
 * - 空会话内置默认招呼词（[GreetingBubble]，仅 UI 展示不写入历史）。
 * - 输入区：「+」展开功能面板（拍照 / 相册 / 本地文件 + 照片网格）。
 * - 历史抽屉 / 聊天设置 / 功能面板内弹层全部窗口内覆盖渲染：
 *   overlay Service 宿主无 Activity token，Dialog / DropdownMenu / Popup 会触发 BadTokenException。
 *
 * @param providers `settings.providers` 快照，作为模型选择弹窗的数据源。
 * @param catalog 聊天模型全量列表内存缓存（与主聊天页共用同一实例）。
 * @param onHeaderDrag 顶部标题栏拖拽回调，参数为本次增量像素位移（更新窗口位置）。
 * @param onOpenMainApp 点击「打开」跳转主 App 聊天页。
 * @param onClose 关闭迷你小窗。
 */
@Composable
internal fun MiniChatPanel(
    holder: ChatSessionHolder,
    providers: List<ModelProviderConfig>,
    catalog: ChatModelCatalog,
    onHeaderDrag: (dx: Float, dy: Float) -> Unit,
    onOpenMainApp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
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
    val listState = rememberLazyListState()
    val context = LocalContext.current
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
        modifier = modifier.fillMaxSize(),
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
                                showQuickActions = false
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
        }
    }
}
