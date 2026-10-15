package `fun`.kirari.hanako.overlay

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `fun`.kirari.hanako.R
import `fun`.kirari.hanako.copyToClipboardWithToast
import `fun`.kirari.hanako.data.AssistantPreset
import `fun`.kirari.hanako.data.previewPrompt
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ModelSelection
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.displayName
import `fun`.kirari.hanako.data.resolveModelName
import `fun`.kirari.hanako.data.resolveModelProvider
import `fun`.kirari.hanako.ui.components.ModelPickerListContent
import `fun`.kirari.hanako.ui.components.ModelPickerSurfaceItem
import `fun`.kirari.hanako.ui.components.rememberModelPickerState
import `fun`.kirari.hanako.ui.cropBitmap
import `fun`.kirari.hanako.util.HapticHelper
import kotlinx.coroutines.delay
import kotlin.math.abs

/** 内容区顶部向下甩动关闭面板的最小速度（px/s）。 */
private const val PanelFlingDismissVelocityPx = 700f

@Composable
internal fun CropOverlaySheet(
    uiState: OverlayUiState,
    onClose: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
    panelHeightPx: Int,
    modelPickerTarget: ModelPurpose?,
    onModelPickerTargetChange: (ModelPurpose?) -> Unit,
    onSelectAssistant: (String) -> Unit,
    onSelectPreviousAssistant: () -> Unit,
    onSelectNextAssistant: () -> Unit,
    onUpdateModelSelection: (ModelPurpose, ModelSelection, Boolean) -> Unit,
    onToggleFavoriteModel: (String, String) -> Unit,
    onToggleProcessingRoute: () -> Unit
) {
    val bitmap = uiState.screenshot
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var cropStart by remember { mutableStateOf(Offset.Zero) }
    var cropEnd by remember { mutableStateOf(Offset.Zero) }
    var cropPromptVisible by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val selectedAssistant = uiState.settings.assistants.firstOrNull { it.id == uiState.settings.selectedAssistantId }
    val routeText = when (uiState.settings.processingRoute) {
        ProcessingRoute.OCR_THEN_LLM -> "OCR模式"
        ProcessingRoute.MULTIMODAL_DIRECT -> "多模态模式"
        ProcessingRoute.ACCESSIBILITY_TEXT -> "无障碍模式"
    }
    val ocrProvider = uiState.settings.resolveModelProvider(ModelPurpose.OCR)
    val ocrModel = uiState.settings.resolveModelName(ModelPurpose.OCR)
    val llmPurpose = when (uiState.settings.processingRoute) {
        ProcessingRoute.OCR_THEN_LLM, ProcessingRoute.ACCESSIBILITY_TEXT -> ModelPurpose.TEXT
        ProcessingRoute.MULTIMODAL_DIRECT -> ModelPurpose.VISION
    }
    val llmProvider = uiState.settings.resolveModelProvider(llmPurpose)
    val llmModel = uiState.settings.resolveModelName(llmPurpose)
    val selectionColor = MaterialTheme.colorScheme.primary
    val hasSelection = cropStart != cropEnd
    val panelMaxHeight = with(density) { panelHeightPx.toDp() }
    var showAssistantDialog by remember { mutableStateOf(false) }
    var assistantPickerClosing by remember { mutableStateOf(false) }
    var switchDirection by remember { mutableStateOf(AssistantSwitchDirection.PICKER) }
    var providerPickerTarget by remember { mutableStateOf<ModelPurpose?>(null) }
    var providerPickerClosing by remember { mutableStateOf(false) }
    var customModelTarget by remember { mutableStateOf<ModelPurpose?>(null) }
    var customModelDialogTitle by remember { mutableStateOf<String?>(null) }
    var customModelDialogClosing by remember { mutableStateOf(false) }
    var modelPickerClosing by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        var closeRequested by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelMaxHeight)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 20.dp, top = 52.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CropHeaderRow(
                        assistantName = selectedAssistant?.name.orEmpty(),
                        hasMultipleAssistants = uiState.settings.assistants.size > 1,
                        switchDirection = switchDirection,
                        onSelectPrevious = {
                            switchDirection = AssistantSwitchDirection.PREVIOUS
                            onSelectPreviousAssistant()
                        },
                        onSelectNext = {
                            switchDirection = AssistantSwitchDirection.NEXT
                            onSelectNextAssistant()
                        },
                        onOpenPicker = {
                            assistantPickerClosing = false
                            showAssistantDialog = true
                        },
                        onClose = {
                            if (closeRequested) return@CropHeaderRow
                            closeRequested = true
                            onClose()
                        }
                    )
                    ModeModelRow(
                        route = uiState.settings.processingRoute,
                        ocrModel = ocrModel,
                        ocrProviderName = ocrProvider?.name,
                        llmModel = llmModel,
                        llmProviderName = llmProvider?.name,
                        onToggleProcessingRoute = onToggleProcessingRoute,
                        onPickModel = { purpose ->
                            providerPickerClosing = false
                            providerPickerTarget = purpose
                        }
                    )
                    uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black)
                            .onSizeChanged { size -> canvasSize = size }
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                            if (cropPromptVisible) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.36f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_bubble_crop),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(52.dp)
                                        )
                                        Text(
                                            text = "滑动截取屏幕区域",
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                            }
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(canvasSize) {
                                        detectDragGestures(
                                            onDragStart = {
                                                cropPromptVisible = false
                                                cropStart = it
                                                cropEnd = it
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                cropEnd += dragAmount
                                            }
                                        )
                                    }
                            ) {
                                if (hasSelection) {
                                    val topLeft = Offset(minOf(cropStart.x, cropEnd.x), minOf(cropStart.y, cropEnd.y))
                                    val size = Size(
                                        width = abs(cropEnd.x - cropStart.x),
                                        height = abs(cropEnd.y - cropStart.y)
                                    )
                                    val right = topLeft.x + size.width
                                    val bottom = topLeft.y + size.height
                                    val overlayColor = Color.Black.copy(alpha = 0.42f)
                                    drawRect(overlayColor, topLeft = Offset.Zero, size = Size(this.size.width, topLeft.y.coerceAtLeast(0f)))
                                    drawRect(overlayColor, topLeft = Offset.Zero, size = Size(topLeft.x.coerceAtLeast(0f), this.size.height))
                                    drawRect(
                                        overlayColor,
                                        topLeft = Offset(right.coerceAtMost(this.size.width), 0f),
                                        size = Size((this.size.width - right).coerceAtLeast(0f), this.size.height)
                                    )
                                    drawRect(
                                        overlayColor,
                                        topLeft = Offset(0f, bottom.coerceAtMost(this.size.height)),
                                        size = Size(this.size.width, (this.size.height - bottom).coerceAtLeast(0f))
                                    )
                                    drawRect(
                                        color = selectionColor,
                                        topLeft = topLeft,
                                        size = size,
                                        style = Stroke(width = 4f)
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                HapticHelper.mediumTap()
                                bitmap?.let {
                                    onConfirm(cropBitmap(it, cropStart, cropEnd, canvasSize))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = bitmap != null && hasSelection
                        ) {
                            Text("确认处理")
                        }
                    }
                }
            }
        }

        SideEffect { closeRequested }
    }

    if (showAssistantDialog) {
        AssistantPickerOverlay(
            assistants = uiState.settings.assistants,
            selectedAssistantId = uiState.settings.selectedAssistantId,
            closing = assistantPickerClosing,
            onDismiss = { assistantPickerClosing = true },
            onDismissFinished = {
                showAssistantDialog = false
                assistantPickerClosing = false
            },
            onSelect = { assistantId ->
                switchDirection = AssistantSwitchDirection.PICKER
                onSelectAssistant(assistantId)
                assistantPickerClosing = true
            }
        )
    }

    val providerPickerTitle = when (providerPickerTarget) {
        ModelPurpose.OCR -> "选择OCR提供方"
        ModelPurpose.TEXT -> "选择LLM提供方"
        ModelPurpose.VISION -> "选择多模态提供方"
        null -> null
    }
    providerPickerTitle?.let { title ->
        ProviderPickerOverlay(
            providers = uiState.settings.providers,
            closing = providerPickerClosing,
            title = title,
            onDismiss = { providerPickerClosing = true },
            onDismissFinished = {
                providerPickerTarget = null
                providerPickerClosing = false
            },
            onPick = { provider ->
                onUpdateModelSelection(
                    providerPickerTarget ?: return@ProviderPickerOverlay,
                    ModelSelection(providerId = provider.id, model = ""),
                    false
                )
                onModelPickerTargetChange(providerPickerTarget)
                providerPickerClosing = true
            }
        )
    }

    val pickerTarget = modelPickerTarget
    val pickerProvider = pickerTarget?.let { uiState.settings.resolveModelProvider(it) }
    if (pickerTarget != null && pickerProvider != null) {
        val title = when (pickerTarget) {
            ModelPurpose.TEXT -> "选择LLM模型"
            ModelPurpose.VISION -> "选择多模态模型"
            ModelPurpose.OCR -> "选择OCR模型"
        }
        ModelPickerOverlay(
            provider = pickerProvider,
            closing = modelPickerClosing,
            title = title,
            onDismiss = {
                modelPickerClosing = true
            },
            onDismissFinished = {
                onModelPickerTargetChange(null)
                modelPickerClosing = false
            },
            onPick = { model, isFavorite ->
                onUpdateModelSelection(
                    pickerTarget,
                    ModelSelection(providerId = pickerProvider.id, model = model),
                    isFavorite
                )
                modelPickerClosing = true
            },
            onToggleFavorite = { model, _ ->
                onToggleFavoriteModel(pickerProvider.id, model)
            },
            onCustomModelRequest = { dialogTitle ->
                customModelTarget = pickerTarget
                customModelDialogTitle = dialogTitle
                customModelDialogClosing = false
            }
        )
    }

    customModelDialogTitle?.let { title ->
        CustomModelOverlay(
            title = title,
            closing = customModelDialogClosing,
            initialValue = customModelTarget?.let(uiState.settings::resolveModelName).orEmpty(),
            onDismiss = {
                customModelDialogClosing = true
            },
            onDismissFinished = {
                customModelDialogTitle = null
                customModelTarget = null
                customModelDialogClosing = false
            },
            onConfirm = { model ->
                val purpose = customModelTarget
                val providerId = purpose?.let { uiState.settings.resolveModelProvider(it)?.id }
                if (purpose == null || providerId == null) {
                    Toast.makeText(context, "无法保存自定义模型：缺少提供方或用途信息", Toast.LENGTH_SHORT).show()
                } else {
                    onUpdateModelSelection(
                        purpose,
                        ModelSelection(providerId = providerId, model = model),
                        true
                    )
                    customModelDialogClosing = true
                }
            }
        )
    }
}

@Composable
internal fun ResultOverlaySheet(
    uiState: OverlayUiState,
    onClose: () -> Unit,
    panelHeightPx: Int,
    /** 内容滚动到顶部后继续下拉时，跟随手指收缩面板。 */
    onPanelDragBy: (Float) -> Unit = {},
    /** 松手吸附：低于阈值关闭，否则回停靠/全展开。 */
    onPanelSettle: () -> Unit = {},
    /** 顶部向下快速甩动（fling）：直接关闭面板。 */
    onPanelFlingDown: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val panelMaxHeight = with(density) { panelHeightPx.toDp() }
    val answerText = uiState.liveAnswerText

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        var closeRequested by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelMaxHeight)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    // 内容顶部下拉 → 跟随手指收缩面板；上滑 → 展开恢复；松手 → 吸附（超过面板高度约 1/3 关闭，否则回弹全展开）；快速下滑 → 关闭。
                    // 采用与相册页一致的 Initial 阶段旁路观察：普通滚动事件不消费，内容可正常上下滚动；
                    // 仅当拖动开始时就位于顶部且首帧向下移动，才识别为「下拉跟随」手势并消费位移，保证面板 1:1 跟随手指。
                    .pointerInput(scrollState) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                val downY = down.position.y
                                var lastY = down.position.y
                                var lastTime = down.uptimeMillis
                                var pulling = false
                                var maxDownVelocity = 0f
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes
                                        .firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val atTop = scrollState.value <= 0
                                    val dy = change.position.y - lastY
                                    val now = change.uptimeMillis
                                    val dt = now - lastTime
                                    lastY = change.position.y
                                    lastTime = now
                                    if (dt > 0) {
                                        val v = dy / dt * 1000f
                                        if (v > maxDownVelocity) maxDownVelocity = v
                                    }
                                    if (!pulling) {
                                        // 仅当拖动开始时就位于顶部、且首帧向下移动，才识别为「下拉跟随」手势，
                                        // 避免内容滚动过程中（到达顶部边界的瞬间）误触发面板位移。
                                        pulling = atTop && dy > 0
                                    }
                                    if (pulling) {
                                        // 下拉收缩、上滑展开：位移按「手指相对按下点的累计位移」计算，
                                        // 抵消面板高度变化带来的坐标漂移，消除抖动，保证面板 1:1 跟随手指；
                                        // 消费本次位移，阻止子级内容在面板手势期间同步滚动。
                                        onPanelDragBy(change.position.y - downY)
                                        change.consume()
                                    }
                                }
                                when {
                                    pulling && maxDownVelocity > PanelFlingDismissVelocityPx -> onPanelFlingDown()
                                    pulling -> onPanelSettle()
                                }
                            }
                        }
                    }
            ) {
                SheetTitleRow(
                    title = {
                        Text("Hanako")
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 8.dp),
                    onClose = {
                        if (closeRequested) return@SheetTitleRow
                        closeRequested = true
                        onClose()
                    }
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ResultCard(title = "原图") {
                        uiState.selectedBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp)),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                    if (uiState.settings.processingRoute == ProcessingRoute.OCR_THEN_LLM || uiState.settings.processingRoute == ProcessingRoute.ACCESSIBILITY_TEXT) {
                        ResultCard(
                            title = if (uiState.settings.processingRoute == ProcessingRoute.ACCESSIBILITY_TEXT) "无障碍提取结果" else "OCR 结果",
                            actions = {
                                if (!uiState.working && uiState.liveOcrText.isNotBlank()) {
                                    SmallHeaderAction(
                                        label = "复制原文",
                                        onClick = {
                                            copyToClipboardWithToast(context, "Hanako OCR 原文", uiState.liveOcrText, "已复制 OCR 原文")
                                        }
                                    )
                                }
                            }
                        ) {
                            if (uiState.liveOcrText.isBlank() && uiState.working) {
                                LoadingLine("正在识别文字…")
                            } else {
                                val ocrText = uiState.liveOcrText
                                if (ocrText.isNotBlank()) {
                                    MarkdownLatexText(
                                        content = ocrText,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    Text("暂无内容")
                                }
                            }
                        }
                    }
                    ResultCard(
                        title = "答案",
                        actions = {
                            if (!uiState.working && answerText.isNotBlank()) {
                                SmallHeaderAction(
                                    label = "复制",
                                onClick = {
                                    copyToClipboardWithToast(context, "Hanako 原始答案", answerText, "已复制全文")
                                }
                                )
                            }
                        }
                    ) {
                        when {
                            answerText.isNotBlank() -> MarkdownLatexText(
                                content = answerText,
                                modifier = Modifier.fillMaxWidth()
                            )
                            uiState.working -> LoadingLine("正在生成答案…")
                            else -> Text("暂无内容")
                        }
                    }
                    uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        SideEffect { closeRequested }
    }
}

@Composable
private fun CropHeaderRow(
    assistantName: String,
    hasMultipleAssistants: Boolean,
    switchDirection: AssistantSwitchDirection,
    onSelectPrevious: () -> Unit,
    onSelectNext: () -> Unit,
    onOpenPicker: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            AssistantSwitcher(
                assistantName = assistantName,
                hasMultipleAssistants = hasMultipleAssistants,
                switchDirection = switchDirection,
                onSelectPrevious = onSelectPrevious,
                onSelectNext = onSelectNext,
                onOpenPicker = onOpenPicker
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(36.dp)
        ) {
            Text("×", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SheetTitleRow(
    title: @Composable () -> Unit,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides style
            ) {
                title()
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(36.dp)
        ) {
            Text("×", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun AssistantSwitcher(
    assistantName: String,
    hasMultipleAssistants: Boolean,
    switchDirection: AssistantSwitchDirection,
    onSelectPrevious: () -> Unit,
    onSelectNext: () -> Unit,
    onOpenPicker: () -> Unit
) {
    Row(
        modifier = Modifier.wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (hasMultipleAssistants) {
            SwitchArrowButton(symbol = "〈", onClick = onSelectPrevious)
        }
        Box(
            modifier = Modifier
                .width(168.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onOpenPicker)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = assistantName,
                transitionSpec = {
                    assistantNameTransform(switchDirection)
                },
                label = "assistantName"
            ) { currentAssistantName ->
                Text(
                    text = "助手：$currentAssistantName",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
        if (hasMultipleAssistants) {
            SwitchArrowButton(symbol = "〉", onClick = onSelectNext)
        }
    }
}

@Composable
private fun SwitchArrowButton(
    symbol: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 36.dp, height = 36.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AssistantPickerOverlay(
    assistants: List<AssistantPreset>,
    selectedAssistantId: String?,
    closing: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onSelect: (String) -> Unit
) {
    val density = LocalDensity.current.density
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    LaunchedEffect(closing) {
        if (closing) {
            visible = false
            delay(220)
            onDismissFinished()
        }
    }
    val overlayAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "assistantPickerOverlayAlpha"
    )
    val cardAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "assistantPickerCardAlpha"
    )
    val cardTranslationY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 0f else if (closing) -20f else 20f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "assistantPickerCardTranslationY"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f * overlayAlpha))
            .clickable(onClick = {
                if (!closing) onDismiss()
            }),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .width(300.dp)
                .heightIn(max = 460.dp)
                .graphicsLayer {
                    alpha = cardAlpha
                    translationY = cardTranslationY * density
                }
                .clickable(enabled = false, onClick = { })
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("选择助手", style = MaterialTheme.typography.titleMedium)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(assistants, key = { it.id }) { assistant ->
                        Surface(
                            onClick = { onSelect(assistant.id) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (assistant.id == selectedAssistantId) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(assistant.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    assistant.previewPrompt().replace('\n', ' '),
                                    maxLines = 2,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (!closing) onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun ModeModelRow(
    route: ProcessingRoute,
    ocrModel: String,
    ocrProviderName: String?,
    llmModel: String,
    llmProviderName: String?,
    onToggleProcessingRoute: () -> Unit,
    onPickModel: (ModelPurpose) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = route,
            transitionSpec = {
                (
                    androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 240,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing
                        )
                    ) { it / 2 } + androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(180)
                    )
                ).togetherWith(
                    androidx.compose.animation.slideOutVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 240,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing
                        )
                    ) { -it / 2 } + androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(180)
                    )
                )
            },
            label = "modeModelRow"
        ) { currentRoute ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (currentRoute) {
                        ProcessingRoute.OCR_THEN_LLM -> "OCR模式"
                        ProcessingRoute.MULTIMODAL_DIRECT -> "多模态模式"
                        ProcessingRoute.ACCESSIBILITY_TEXT -> "无障碍模式"
                    },
                    modifier = Modifier.clickable(onClick = onToggleProcessingRoute),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                if (currentRoute == ProcessingRoute.OCR_THEN_LLM || currentRoute == ProcessingRoute.ACCESSIBILITY_TEXT) {
                    ModelConfigCarousel(
                        items = listOf(
                            ModelConfigItem(
                                label = "OCR",
                                model = ocrModel,
                                providerName = ocrProviderName,
                                purpose = ModelPurpose.OCR
                            ),
                            ModelConfigItem(
                                label = "LLM",
                                model = llmModel,
                                providerName = llmProviderName,
                                purpose = ModelPurpose.TEXT
                            )
                        ),
                        onClick = onPickModel
                    )
                } else {
                    ModelConfigCarousel(
                        items = listOf(
                            ModelConfigItem(
                                label = "LLM",
                                model = llmModel,
                                providerName = llmProviderName,
                                purpose = ModelPurpose.VISION
                            )
                        ),
                        onClick = onPickModel
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelConfigCarousel(
    items: List<ModelConfigItem>,
    onClick: (ModelPurpose) -> Unit
) {
    var index by remember(items) { mutableStateOf(0) }
    LaunchedEffect(items) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(3_000)
            index = (index + 1) % items.size
        }
    }
    Box(
        modifier = Modifier
            .width(220.dp)
            .height(20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = items[index % items.size],
            transitionSpec = {
                (
                    androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 240,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing
                        )
                    ) { it / 2 } + androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(180)
                    )
                ).togetherWith(
                    androidx.compose.animation.slideOutVertically(
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 240,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing
                        )
                    ) { -it / 2 } + androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(180)
                    )
                )
            },
            label = "modelConfigCarousel"
        ) { item ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                ModelConfigText(
                    label = item.label,
                    model = item.model,
                    providerName = item.providerName,
                    onClick = { onClick(item.purpose) }
                )
            }
        }
    }
}

private data class ModelConfigItem(
    val label: String,
    val model: String,
    val providerName: String?,
    val purpose: ModelPurpose
)

@Composable
private fun ResultCard(
    title: String,
    actions: @Composable (RowScope.() -> Unit)? = null,
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
                if (actions != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SmallHeaderAction(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            )
            .clickable {
                HapticHelper.lightTap()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ModelConfigText(
    label: String,
    model: String,
    providerName: String?,
    onClick: () -> Unit
) {
    val text = buildString {
        append(label)
        append("：")
        append(model.ifBlank { "未配置模型" })
        providerName?.takeIf { it.isNotBlank() }?.let {
            append("（")
            append(it)
            append("）")
        }
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        textAlign = TextAlign.End,
        overflow = TextOverflow.Ellipsis,
        lineHeight = 20.sp
    )
}

@Composable
private fun ProviderPickerOverlay(
    providers: List<ModelProviderConfig>,
    closing: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onPick: (ModelProviderConfig) -> Unit
) {
    val density = LocalDensity.current.density
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    LaunchedEffect(closing) {
        if (closing) {
            visible = false
            delay(220)
            onDismissFinished()
        }
    }
    val overlayAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "providerPickerOverlayAlpha"
    )
    val cardAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "providerPickerCardAlpha"
    )
    val cardTranslationY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 0f else if (closing) -20f else 20f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "providerPickerCardTranslationY"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f * overlayAlpha))
            .clickable(onClick = {
                if (!closing) onDismiss()
            }),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .width(300.dp)
                .heightIn(max = 520.dp)
                .graphicsLayer {
                    alpha = cardAlpha
                    translationY = cardTranslationY * density
                }
                .clickable(enabled = false, onClick = { })
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(providers, key = { it.id }) { provider ->
                        Surface(
                            onClick = { onPick(provider) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(provider.name)
                                Text(
                                    provider.kind.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (!closing) onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun ModelPickerOverlay(
    provider: ModelProviderConfig,
    closing: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onPick: (String, Boolean) -> Unit,
    onToggleFavorite: (String, Boolean) -> Unit,
    onCustomModelRequest: (String) -> Unit
) {
    val pickerState = rememberModelPickerState(provider = provider)
    OverlaySelectionShell(
        title = title,
        closing = closing,
        onDismiss = onDismiss,
        onDismissFinished = onDismissFinished,
        footer = {
            OutlinedButton(
                onClick = { onCustomModelRequest(title) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("使用自定义模型")
            }
        }
    ) {
        ModelPickerListContent(
            models = pickerState.entries,
            hasNetworkResponse = pickerState.hasNetworkResponse,
            onPick = { modelId, isFavorite ->
                if (isFavorite && !pickerState.hasNetworkResponse) {
                    pickerState.cancelIfWaitingForNetwork()
                }
                onPick(modelId, isFavorite)
            },
            buttonStyle = { item, onClick, _ ->
                ModelPickerSurfaceItem(
                    model = item,
                    onPick = onClick,
                    onLongPress = {
                        if (item.isFavorite) {
                            pickerState.removeFavoriteFromCurrentSession(item.id)
                        }
                        onToggleFavorite(item.id, item.isFavorite)
                    }
                )
            }
        )
    }
}

@Composable
private fun CustomModelOverlay(
    title: String,
    closing: Boolean,
    initialValue: String,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val trimmedValue = value.trim()
    OverlaySelectionShell(
        title = title,
        closing = closing,
        onDismiss = onDismiss,
        onDismissFinished = onDismissFinished,
        footer = {
            Button(
                onClick = { onConfirm(trimmedValue) },
                enabled = trimmedValue.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("确认")
            }
        }
    ) {
        Text(
            "输入要使用的模型名称，保存后将直接用于当前提供方。",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自定义模型") },
            placeholder = { Text("例如：gpt-4.1-mini") },
            singleLine = true
        )
    }
}

@Composable
private fun OverlaySelectionShell(
    title: String,
    closing: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    footer: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current.density
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    LaunchedEffect(closing) {
        if (closing) {
            visible = false
            delay(220)
            onDismissFinished()
        }
    }
    val overlayAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "$title-overlayAlpha"
    )
    val cardAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "$title-cardAlpha"
    )
    val cardTranslationY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 0f else if (closing) -20f else 20f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "$title-cardTranslationY"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f * overlayAlpha))
            .clickable(onClick = {
                if (!closing) onDismiss()
            }),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .width(300.dp)
                .heightIn(max = 460.dp)
                .graphicsLayer {
                    alpha = cardAlpha
                    translationY = cardTranslationY * density
                }
                .clickable(enabled = false, onClick = { })
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
                footer?.invoke()
                OutlinedButton(
                    onClick = {
                        if (!closing) onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun LoadingLine(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(text)
    }
}
