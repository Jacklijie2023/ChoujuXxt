package `fun`.kirari.hanako.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import `fun`.kirari.hanako.chat.ChatModelCatalog
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.chat.splitSessionsByTime
import `fun`.kirari.hanako.copyToClipboardWithToast
import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.data.ChatSession
import `fun`.kirari.hanako.data.ModelProviderConfig
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onRevealNavBar: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val session by holder.session.collectAsState()
    val sessions by holder.sessions.collectAsState()
    val isStreaming = session.isStreaming
    var input by rememberSaveable { mutableStateOf("") }
    var panelExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 历史抽屉开关：用普通 remember（非 rememberSaveable），避免「已展开」状态跨 Tab 被恢复。
    var showDrawer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 用于「点击空白处收起键盘」以及「打开 + 面板时收起键盘」。
    val focusManager = LocalFocusManager.current

    // ---- 真实接入手机系统能力：拍照 / 相册 / 本地文件 / 语音输入 / 语音播放 ----
    // 已选内容（拍照、相册、本地文件）列表：图片显示缩略图，其它显示文件图标；支持多张。
    var attachments by remember { mutableStateOf<List<PickedAttachment>>(emptyList()) }
    // 拍照输出 Uri：先建好 FileProvider Uri 再交给系统相机写入。
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    // 全屏相册选择页开关（「+」面板的相册入口 / 照片区上拉 / 附件行「+」都打开同一个页面）。
    var albumPageOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 底部上滑唤出导航栏的触发阈值。
    val navRevealThresholdPx = with(LocalDensity.current) { 36.dp.toPx() }

    // 把一张图片 Uri 追加到附件列表（按 Uri 去重）。
    val addImageAttachment: (Uri, String) -> Unit = { uri, name ->
        val key = uri.toString()
        if (attachments.none { it.uri.toString() == key }) {
            attachments = attachments + PickedAttachment(uri = uri, name = name, isImage = true)
        }
    }

    // 「+」面板照片条（图 3）：已加入则再次点击取消选择，未加入则加入。
    val toggleImageAttachment: (Uri, String) -> Unit = { uri, name ->
        val key = uri.toString()
        attachments = if (attachments.any { it.uri.toString() == key }) {
            attachments.filterNot { it.uri.toString() == key }
        } else {
            attachments + PickedAttachment(uri = uri, name = name, isImage = true)
        }
    }

    // 拍照：拉起系统相机（ACTION_IMAGE_CAPTURE），结果写入 FileProvider 暴露的 Uri。
    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraUri
        if (success && uri != null) {
            addImageAttachment(uri, "拍摄照片.jpg")
            Toast.makeText(context, "已添加拍摄照片", Toast.LENGTH_SHORT).show()
        } else if (!success) {
            Toast.makeText(context, "已取消拍照", Toast.LENGTH_SHORT).show()
        }
    }

    // 相册：改由应用内全屏相册选择页承担（见 AlbumPickerOverlay），不再拉起系统选择器。

    // 本地文件：拉起系统文件选择器。
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri)
            attachments = attachments + PickedAttachment(uri = uri, name = name, isImage = false)
            Toast.makeText(context, "已选择文件：$name", Toast.LENGTH_SHORT).show()
        }
    }

    // 语音输入（图 3）：按住说话，调用系统 SpeechRecognizer 实时转文字（partial 结果边听边显示）。
    var isInputFocused by remember { mutableStateOf(false) }
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var speechPartial by remember { mutableStateOf("") }
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermission = granted
        if (granted) {
            voiceMode = true
        } else {
            Toast.makeText(context, "缺少录音权限，无法语音输入", Toast.LENGTH_SHORT).show()
        }
    }
    // 语音识别可用性：优先用系统 SpeechRecognizer 做内联「按住说话」（可实时出字）；
    // 若本机没有系统语音识别服务（部分国产 ROM），回退到系统语音识别界面（ACTION_RECOGNIZE_SPEECH）。
    val recognizerAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val speechIntentAvailable = remember {
        context.packageManager.resolveActivity(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
            PackageManager.MATCH_DEFAULT_ONLY
        ) != null
    }
    // 回退路径：拉起系统语音识别界面，取回识别文本。
    val speechIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                input = if (input.isBlank()) text else "$input $text"
            }
        }
    }
    val voiceSession = remember {
        VoiceInputSession(
            context = context,
            onPartial = { speechPartial = it },
            onResult = { text ->
                input = if (input.isBlank()) text else "$input $text"
                speechPartial = ""
                isListening = false
            },
            onError = { message ->
                speechPartial = ""
                isListening = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose { voiceSession.release() }
    }
    // 按住：开始录音识别；无权限时先申请。
    val startListening: () -> Unit = {
        if (hasRecordPermission) {
            speechPartial = ""
            isListening = true
            voiceSession.start()
        } else {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // 松开（commit = true 提交识别结果）/ 取消（commit = false 丢弃）。
    val stopListening: (Boolean) -> Unit = { commit ->
        if (isListening) {
            isListening = false
            if (commit) voiceSession.stop() else voiceSession.cancel()
        }
    }

    // 语音播放：系统 TTS 朗读 AI 回复正文。
    val ttsReady = remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { status -> ttsReady.value = status == TextToSpeech.SUCCESS }
    }
    DisposableEffect(Unit) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }
    val speak: (String) -> Unit = { raw ->
        val speech = stripMarkdownForSpeech(raw)
        when {
            speech.isBlank() ->
                Toast.makeText(context, "没有可朗读的内容", Toast.LENGTH_SHORT).show()

            ttsReady.value ->
                tts.speak(
                    speech,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "hanako_${System.currentTimeMillis()}"
                )

            else ->
                Toast.makeText(context, "语音播放尚未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }

    // 离开聊天页时立即收起抽屉（DisposableEffect 在组合提交后同步执行，不留残留帧）。
    DisposableEffect(isActive) {
        if (!isActive) showDrawer = false
        onDispose { }
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

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
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
                IconButton(onClick = { showDrawer = true }) {
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

            // 消息区：点击输入框以外的空白处自动收起键盘。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures { focusManager.clearFocus() }
                    }
            ) {
                if (session.messages.isEmpty()) {
                    // 空会话默认招呼词（参考通义千问欢迎语）：仅 UI 展示，不写入会话历史。
                    GreetingBubble(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
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
                                onRegenerate = holder::retry,
                                onSpeak = speak
                            )
                        }
                    }
                }
            }

            // 大圆角输入容器（图 1/2/3/4）：语音键 / 输入框 / 拍照 / 「+」（点击旋转 90° 变 ×）/ 发送。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .imePadding()
            ) {
                val plusRotation by animateFloatAsState(
                    targetValue = if (panelExpanded) 45f else 0f,
                    label = "plusRotation"
                )
                val hasContent = input.isNotBlank() || attachments.isNotEmpty()

                // 发送：把输入文字与已选图片（base64，支持多张）一起发出。
                val sendCurrent: () -> Unit = {
                    if (!isStreaming && hasContent) {
                        val text = input
                        val images = attachments
                        input = ""
                        attachments = emptyList()
                        scope.launch {
                            val encoded = withContext(Dispatchers.IO) {
                                images.filter { it.isImage }
                                    .mapNotNull { encodeImageBase64(context, it.uri) }
                            }
                            holder.send(text, encoded)
                        }
                    }
                }

                // 「+」功能键：点击旋转 90°（+ → ×）并展开输入功能面板；面板与键盘互斥。
                val plusButton: @Composable () -> Unit = {
                    IconButton(
                        onClick = {
                            if (panelExpanded) {
                                panelExpanded = false
                            } else {
                                focusManager.clearFocus()
                                panelExpanded = true
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = if (panelExpanded) "收起输入功能" else "展开输入功能",
                            modifier = Modifier.rotate(plusRotation)
                        )
                    }
                }

                // 拍照功能键：真实拉起系统相机，结果写入 FileProvider Uri。
                val cameraButton: @Composable () -> Unit = {
                    IconButton(
                        onClick = {
                            val uri = createCameraImageUri(context)
                            cameraUri = uri
                            runCatching { takePictureLauncher.launch(uri) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        "未找到可用的相机应用",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        }
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "拍照")
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column {
                    // 图 4：已选照片展示在输入框顶部区域（多张横向滚动，右上角可移除）。
                    if (attachments.isNotEmpty()) {
                        AttachmentStrip(
                            attachments = attachments,
                            onRemove = { uri ->
                                attachments = attachments.filterNot { it.uri == uri }
                            },
                            onAddMore = { albumPageOpen = true }
                        )
                    }
                    if (voiceMode) {
                        // 图 3：键盘切换键 + 「按住 说话」 + 拍照 + 「+」。
                        Row(
                            modifier = Modifier.padding(
                                start = 4.dp,
                                end = 8.dp,
                                top = 4.dp,
                                bottom = 4.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    stopListening(false)
                                    voiceMode = false
                                }
                            ) {
                                Icon(Icons.Default.Keyboard, contentDescription = "切换到键盘输入")
                            }
                            if (recognizerAvailable) {
                                HoldToTalkBar(
                                    listening = isListening,
                                    partialText = speechPartial,
                                    onStart = { startListening() },
                                    onStop = { stopListening(true) },
                                    onCancel = { stopListening(false) },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                // 无系统 SpeechRecognizer：回退到系统语音识别界面。
                                TapToTalkBar(
                                    onClick = {
                                        runCatching {
                                            speechIntentLauncher.launch(
                                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(
                                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                                    )
                                                    putExtra(
                                                        RecognizerIntent.EXTRA_LANGUAGE,
                                                        Locale.getDefault().toLanguageTag()
                                                    )
                                                    putExtra(
                                                        RecognizerIntent.EXTRA_PROMPT,
                                                        "请说出要输入的内容"
                                                    )
                                                }
                                            )
                                        }.onFailure {
                                            Toast.makeText(
                                                context,
                                                "未找到可用的语音识别界面",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            cameraButton()
                            plusButton()
                        }
                    } else {
                        // 图 1：未触发（未聚焦且无内容）→ 语音键 + 输入框 + 拍照 + 「+」水平单行。
                        // 图 2/3：点击输入框 / 输入文字 / 流式中 → 双层结构：
                        //   第一层全宽文本输入区；第二层 语音 / 拍照 / 「+」/ 发送，与第一层同左侧间距。
                        // TextField 恒位于第一层同一代码槽位（不被条件兄弟挤动），避免重建导致焦点丢失抖动。
                        val twoLayer = isInputFocused || hasContent || isStreaming
                        val voiceButton: @Composable () -> Unit = {
                            IconButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    when {
                                        // 有系统识别服务：内联「按住说话」（需录音权限）。
                                        recognizerAvailable -> {
                                            if (hasRecordPermission) {
                                                voiceMode = true
                                            } else {
                                                recordPermissionLauncher.launch(
                                                    Manifest.permission.RECORD_AUDIO
                                                )
                                            }
                                        }
                                        // 无系统识别服务：回退到系统语音识别界面。
                                        speechIntentAvailable -> voiceMode = true
                                        else -> Toast.makeText(
                                            context,
                                            "本机未安装语音识别服务，请在系统设置中开启语音输入",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                VoiceInputIcon()
                            }
                        }
                        Column {
                            // 第一层：文本输入区（恒定槽位，全宽）。
                            Row(
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                    end = 8.dp,
                                    top = 4.dp,
                                    bottom = if (twoLayer) 2.dp else 4.dp
                                ),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // 场景 1：语音键与输入框水平并排；场景 2/3 时语音下沉到第二层。
                                if (!twoLayer) voiceButton()
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .onFocusChanged { state ->
                                            isInputFocused = state.isFocused
                                            // 点输入框唤起键盘时，自动收起「+」面板（与键盘互斥）。
                                            if (state.isFocused && panelExpanded) panelExpanded = false
                                        },
                                    placeholder = {
                                        Text(if (isInputFocused) "发消息…" else "发消息或按住说话…")
                                    },
                                    shape = RoundedCornerShape(22.dp),
                                    singleLine = !isInputFocused,
                                    minLines = if (isInputFocused) 2 else 1,
                                    maxLines = 4,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent
                                    )
                                )
                                // 场景 1：拍照 + 「+」内联尾随；场景 2/3 时下沉到第二层。
                                if (!twoLayer) {
                                    cameraButton()
                                    plusButton()
                                }
                            }
                            // 第二层：功能键行（仅场景 2/3，与第一层同左侧间距）。
                            if (twoLayer) {
                                Row(
                                    modifier = Modifier.padding(
                                        start = 4.dp,
                                        end = 8.dp,
                                        top = 2.dp,
                                        bottom = 4.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    voiceButton()
                                    cameraButton()
                                    plusButton()
                                    Spacer(modifier = Modifier.weight(1f))
                                    if (isStreaming) {
                                        IconButton(onClick = { holder.stop() }) {
                                            Icon(Icons.Default.Stop, contentDescription = "停止")
                                        }
                                    } else if (hasContent) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                                .clickable { sendCurrent() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.ArrowUpward,
                                                contentDescription = "发送",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
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
                        // 拍照：先建 FileProvider Uri，再真实拉起系统相机写入。
                        onCamera = {
                            val uri = createCameraImageUri(context)
                            cameraUri = uri
                            runCatching { takePictureLauncher.launch(uri) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        "未找到可用的相机应用",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        },
                        // 相册：打开应用内全屏相册选择页（同一个页面，详见 AlbumPickerOverlay）。
                        onOpenAlbumPage = { albumPageOpen = true },
                        // 面板照片条点击（单选式）：已加入则取消，未加入则加入输入框顶部附件区。
                        onAddPhoto = { uri ->
                            toggleImageAttachment(uri, queryDisplayName(context, uri))
                        },
                        // 已加入输入框顶部的照片 Uri（用于照片条上的勾选态）。
                        attachedUris = attachments.map { it.uri.toString() }.toSet(),
                        // 本地文件：真实拉起系统文件选择器。
                        onFile = {
                            runCatching { fileLauncher.launch(arrayOf("*/*")) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        "未找到可用的文件选择器",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // 免责提示（图 2/3/4）：键盘未弹出（输入框未聚焦）时显示在输入框下方。
                // 字号与颜色刻意弱化（小一号、半透明），避免抢视觉焦点。
                if (!isInputFocused) {
                    Text(
                        text = "内容由 AI 生成，请注意核实",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            }
        }

        // 历史抽屉覆盖层：窗口内覆盖渲染，不创建新窗口、不参与横向滑动手势，
        // 从根上规避 ModalNavigationDrawer 与 HorizontalPager 的手势冲突（边缘拖拽抢占
        // 分页动画会导致分页停在半路、抽屉残留且只有重启才能清除）。isActive 为 false
        // 时不渲染，保证切到其它 Tab 后不会残留或闪现。
        AnimatedVisibility(
            visible = showDrawer && isActive,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 右侧遮罩：点击关闭抽屉。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable { showDrawer = false }
                )
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.86f)
                        .widthIn(max = 360.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    // 面板背景延伸到状态栏下，但内容整体下移一个状态栏高度，
                    // 避免「历史」标题 / 搜索 / 关闭 图标被状态栏遮挡。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) {
                        SessionHistoryDrawer(
                            // 仅展示「已使用」的会话（有消息）；空的新会话不进入历史列表。
                            sessions = sessions.filter { it.messages.isNotEmpty() },
                            currentSessionId = session.id,
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

        // 全屏相册选择页（图 1/图 2）：Activity 宿主窗口内覆盖渲染（不创建新窗口）。
        if (albumPageOpen) {
            AlbumPickerOverlay(
                hasPermission = hasPhotoPermission,
                onRequestPermission = { photoPermissionLauncher.launch(photoReadPermission()) },
                onDismiss = { albumPageOpen = false },
                onConfirm = { uris ->
                    uris.forEach { uri -> addImageAttachment(uri, queryDisplayName(context, uri)) }
                    albumPageOpen = false
                },
                onCamera = {
                    val uri = createCameraImageUri(context)
                    cameraUri = uri
                    runCatching { takePictureLauncher.launch(uri) }
                        .onFailure {
                            Toast.makeText(context, "未找到可用的相机应用", Toast.LENGTH_SHORT).show()
                        }
                },
                onFile = {
                    runCatching { fileLauncher.launch(arrayOf("*/*")) }
                        .onFailure {
                            Toast.makeText(context, "未找到可用的文件选择器", Toast.LENGTH_SHORT).show()
                        }
                }
            )
        }

        // 底部上滑薄条：聊天页隐藏底部导航栏后，在此向上滑动即可唤回导航栏。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(Unit) {
                    var accum = 0f
                    detectVerticalDragGestures(
                        onDragStart = { accum = 0f },
                        onDragCancel = { accum = 0f },
                        onDragEnd = { accum = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            accum += dragAmount
                            if (accum <= -navRevealThresholdPx) {
                                accum = 0f
                                onRevealNavBar()
                            }
                        }
                    )
                }
        )
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

/** 输入面板已选内容（拍照 / 相册 / 本地文件 的真实结果）。 */
private data class PickedAttachment(
    val uri: Uri,
    val name: String,
    val isImage: Boolean
)

/**
 * 生成拍照输出 Uri：写入应用 cacheDir/shared_images，
 * 经 FileProvider（authorities = `${applicationId}.fileprovider`）暴露给系统相机写入。
 */
private fun createCameraImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "shared_images").apply { if (!exists()) mkdirs() }
    val file = File.createTempFile("camera_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** 查询 content Uri 的显示名（用于本地文件 / 相册选择结果的预览标签）。 */
private fun queryDisplayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: "已选内容"

/** 朗读前对 Markdown 标记做轻量清理，避免把 # * ` > 等符号读出来。 */
private fun stripMarkdownForSpeech(text: String): String =
    text.replace(Regex("[#*`>_~\\[\\]()]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/** 把图片 Uri 编码为 base64（用于多模态发送，支持多张）。 */
private fun encodeImageBase64(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
    }
}.getOrNull()

/** 千问式语音输入图标：圆形描边 + 内部声波。 */
@Composable
private fun VoiceInputIcon(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .size(26.dp)
            .border(2.dp, tint, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.GraphicEq,
            contentDescription = "语音输入",
            tint = tint,
            modifier = Modifier.size(15.dp)
        )
    }
}

/** 点击说话条：本机无系统 SpeechRecognizer 时，点击拉起系统语音识别界面。 */
@Composable
private fun TapToTalkBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "点击 说话",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 输入框顶部附件区（图 4）：已选内容横向滚动展示，每张右上角可移除；末尾「+」继续添加。
 * 仅展示已选图片 / 文件缩略图，不包含「帮我分析图片内容 / 根据图片创作文案」等建议按钮。
 */
@Composable
private fun AttachmentStrip(
    attachments: List<PickedAttachment>,
    onRemove: (Uri) -> Unit,
    onAddMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        attachments.forEach { item ->
            Box(modifier = Modifier.size(64.dp)) {
                if (item.isImage) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 右上角移除按钮。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { onRemove(item.uri) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "移除该图片",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
        // 末尾「+」：继续打开全屏相册页添加更多。
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onAddMore),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "添加图片",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 「按住 说话」条（图 3）：按下开始识别、松开提交、手势取消则丢弃。
 *
 * @param listening 是否正在识别；@param partialText 实时识别到的半截文字（边听边显示）。
 */
@Composable
private fun HoldToTalkBar(
    listening: Boolean,
    partialText: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when {
        listening && partialText.isNotBlank() -> partialText
        listening -> "正在听…"
        else -> "按住 说话"
    }
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(
                if (listening) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onStart()
                        // tryAwaitRelease() 返回 true 表示正常松开（提交），false 表示手势被打断（丢弃）。
                        if (tryAwaitRelease()) onStop() else onCancel()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (listening) FontWeight.Normal else FontWeight.Medium,
            color = if (listening && partialText.isNotBlank()) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

/**
 * 语音输入会话（按住说话）：封装系统 [SpeechRecognizer] 的创建 / 识别 / 释放。
 *
 * 懒创建识别器（首次 [start] 时创建，必须发生在主线程）；[SpeechRecognizer] 需要
 * 系统语音识别服务可用，否则通过 [onError] 回报中文提示。
 */
private class VoiceInputSession(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var released = false

    // 用户主动松开 / 取消后，忽略「没听清」这类由停止触发的噪声错误。
    private var stopping = false

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (released) return null
        recognizer?.let { return it }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("未找到可用的语音识别服务")
            return null
        }
        val created = SpeechRecognizer.createSpeechRecognizer(context)
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                stopping = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) onError("没有听清，请再说一次") else onResult(text)
            }

            override fun onError(error: Int) {
                val ignore = stopping &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                stopping = false
                if (!ignore) onError(speechErrorMessage(error))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = created
        return created
    }

    fun start() {
        val target = ensureRecognizer() ?: return
        stopping = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // 实时返回半截识别结果，实现「边听边显示」。
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { target.startListening(intent) }
            .onFailure { onError("语音识别启动失败，请稍后重试") }
    }

    /** 松开：停止录音并提交已识别到的结果。 */
    fun stop() {
        stopping = true
        runCatching { recognizer?.stopListening() }
    }

    /** 取消：丢弃本次识别。 */
    fun cancel() {
        stopping = true
        recognizer?.let { runCatching { it.cancel() } }
    }

    /** 释放识别器（页面销毁时调用）。 */
    fun release() {
        released = true
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }
}

/** 系统语音识别错误码 -> 中文提示。 */
private fun speechErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音，请重试"
    SpeechRecognizer.ERROR_AUDIO -> "录音出错，请检查麦克风"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络异常，语音识别失败"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限，无法语音输入"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请稍后重试"
    SpeechRecognizer.ERROR_SERVER -> "语音识别服务异常，请稍后重试"
    else -> "语音识别失败，请重试"
}
