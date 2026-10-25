package `fun`.kirari.hanako.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.widget.Toast
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityOptionsCompat
import `fun`.kirari.hanako.R
import `fun`.kirari.hanako.chat.ChatModelCatalog
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.ui.ChatScreen
import `fun`.kirari.hanako.copyToClipboard
import `fun`.kirari.hanako.copyToClipboardWithToast
import `fun`.kirari.hanako.debug.AppDebugLogStore
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
import `fun`.kirari.hanako.ui.ReadingSpeakerIcon
import `fun`.kirari.hanako.ui.cropBitmap
import `fun`.kirari.hanako.ui.cropRectOf
import `fun`.kirari.hanako.util.HapticHelper
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun CropOverlaySheet(
    uiState: OverlayUiState,
    onClose: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
    /** 「长截图」：把当前这一屏（按用户框选区域裁剪）记为第 1 屏，收起面板让用户手动滚动。 */
    onStartLong: (Rect?) -> Unit,
    /** 「继续截图」：把这一屏按本次框选拼接后收起面板，等用户再次点击悬浮球截下一屏。 */
    onContinueLong: (Rect?) -> Unit,
    /** 长截图的「确认处理」：先按本次框选拼接这一屏，再分析累计长图。 */
    onConfirmLong: (Rect?) -> Unit,
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
    // 「原图」累计长图（长截图会话中才有）。
    val longBitmap = uiState.longScreenshotBitmap.takeIf { uiState.longCaptureActive }
    // 每一屏在累计长图中的区段；老状态没有区段时退化成「整张长图算一屏」。
    val longPageSegments = remember(uiState.longPageSegments, longBitmap) {
        when {
            longBitmap == null -> emptyList()
            uiState.longPageSegments.isEmpty() -> listOf(LongPageSegment(0, longBitmap.height))
            else -> uiState.longPageSegments
        }
    }
    // 点击原图中的某一屏后，用它的下标打开放大查看层。
    var viewingPageIndex by remember { mutableStateOf<Int?>(null) }
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
                val panelScroll = rememberScrollState()
                // 默认滚到最底部：面板看起来和第一次截图时一模一样。
                // 只有用户向上滚动，才会露出位于 OCR 模式行上方的「原图」区域。
                LaunchedEffect(panelScroll.maxValue, longBitmap) {
                    if (longBitmap != null) panelScroll.scrollTo(panelScroll.maxValue)
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(panelScroll)
                ) {
                    if (longBitmap != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, top = 16.dp, end = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "原图 · 已拼接 ${uiState.longCapturePages} 屏（点任意一屏放大查看）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            // 每屏单独画成一张可点的图片：不依赖在长图上做坐标命中的手势，
                            // 放在滚动容器里也稳定可点；点击直接打开该屏的放大查看层。
                            val longImage = longBitmap.asImageBitmap()
                            longPageSegments.forEachIndexed { index, segment ->
                                val top = segment.offsetY.coerceIn(0, longBitmap.height - 1)
                                val pageHeight = segment.height.coerceIn(1, longBitmap.height - top)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable {
                                            AppDebugLogStore.i(
                                                "HanakoLongPage",
                                                "click page=${index + 1} seg=$top/$pageHeight total=${longPageSegments.size}"
                                            )
                                            viewingPageIndex = index
                                        },
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "第 ${index + 1} 屏 · 点击查看",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(longBitmap.width.toFloat() / pageHeight.toFloat())
                                    ) {
                                        drawImage(
                                            image = longImage,
                                            srcOffset = IntOffset(0, top),
                                            srcSize = IntSize(longBitmap.width, pageHeight),
                                            dstSize = IntSize(
                                                size.width.roundToInt(),
                                                size.height.roundToInt()
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(panelMaxHeight)
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
                        // 长截图改为「用户自己滚动、每点一次悬浮球截一屏」的手动拼接：
                        // 会话中显示「继续截图」，否则显示「长截图」开始会话。
                        OutlinedButton(
                            onClick = {
                                HapticHelper.mediumTap()
                                if (uiState.longCaptureActive) {
                                    // 把这一屏在预览里的框选换算成原图坐标；未框选则沿用上一次区域。
                                    onContinueLong(
                                        bitmap?.let { cropRectOf(it.width, it.height, cropStart, cropEnd, canvasSize) }
                                    )
                                } else {
                                    // 把用户在预览里的框选换算成原图坐标，长截图只拼这条区域。
                                    val rect = bitmap?.let {
                                        cropRectOf(it.width, it.height, cropStart, cropEnd, canvasSize)
                                    }
                                    onStartLong(rect)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = bitmap != null && uiState.working.not()
                        ) {
                            Text(if (uiState.longCaptureActive) "继续截图 · ${uiState.longCapturePages}屏" else "长截图")
                        }
                        Button(
                            onClick = {
                                HapticHelper.mediumTap()
                                // 长截图：分析的是「原图」区域里的累计长图（不做逐像素框选）；
                                // 普通截图：按用户框选区域裁剪后发送。
                                if (uiState.longCaptureActive) {
                                    // 先按这一屏自己的框选拼接，再分析累计长图。
                                    onConfirmLong(
                                        bitmap?.let { cropRectOf(it.width, it.height, cropStart, cropEnd, canvasSize) }
                                    )
                                } else {
                                    bitmap?.let {
                                        onConfirm(cropBitmap(it, cropStart, cropEnd, canvasSize))
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = (if (uiState.longCaptureActive) uiState.longScreenshotBitmap else bitmap) != null &&
                                uiState.working.not()
                        ) {
                            Text("确认处理")
                        }
                    }
                    }
                }
            }
        }

        // 「原图」单屏查看：与聊天里的图片预览一致——双指缩放、拖动、双击放大，单击关闭。
        val viewingSegment = viewingPageIndex?.let { longPageSegments.getOrNull(it) }
        viewingSegment?.let { segment ->
            val pageIndex = viewingPageIndex ?: 0
            val pageBitmap = remember(segment, longBitmap) {
                longBitmap?.let { cropLongPage(it, segment) }
            }
            var viewerScale by remember(pageIndex) { mutableStateOf(1f) }
            var viewerOffsetX by remember(pageIndex) { mutableStateOf(0f) }
            var viewerOffsetY by remember(pageIndex) { mutableStateOf(0f) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelMaxHeight)
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.97f))
                    .pointerInput(pageIndex) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (viewerScale * zoom).coerceIn(0.5f, 6f)
                            viewerScale = newScale
                            if (newScale > 1f) {
                                val maxX = (newScale - 1f) * size.width / 2f
                                val maxY = (newScale - 1f) * size.height / 2f
                                viewerOffsetX = (viewerOffsetX + pan.x).coerceIn(-maxX, maxX)
                                viewerOffsetY = (viewerOffsetY + pan.y).coerceIn(-maxY, maxY)
                            } else {
                                viewerOffsetX = 0f
                                viewerOffsetY = 0f
                            }
                        }
                    }
            ) {
                if (pageBitmap != null) {
                    Image(
                        bitmap = pageBitmap.asImageBitmap(),
                        contentDescription = "第 ${pageIndex + 1} 屏",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = viewerScale
                                scaleY = viewerScale
                                translationX = viewerOffsetX
                                translationY = viewerOffsetY
                            }
                            .pointerInput(segment) {
                                detectTapGestures(
                                    onTap = { viewingPageIndex = null },
                                    onDoubleTap = {
                                        if (viewerScale > 1.05f) {
                                            viewerScale = 1f
                                            viewerOffsetX = 0f
                                            viewerOffsetY = 0f
                                        } else {
                                            viewerScale = 2.5f
                                        }
                                    }
                                )
                            }
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "第 ${pageIndex + 1} 屏 · 双指缩放 / 双击放大 / 单击关闭",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        text = "关闭",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier
                            .clickable { viewingPageIndex = null }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
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

/** 分析页「答案」模块朗读标记：与聊天页共用 TtsHelper 朗读状态，用于识别当前是否在朗读本面板内容。 */
private const val ANSWER_SPEECH_ID = "overlay_answer_speech"

/** 分析页「完整材料和题目」模块的朗读标记（与答案朗读互斥，共用 TtsHelper 引擎）。 */
private const val QUESTION_SPEECH_ID = "overlay_question_speech"

@Composable
internal fun ResultOverlaySheet(
    uiState: OverlayUiState,
    onClose: () -> Unit,
    panelHeightPx: Int,
    /** 内容区是否已滚动到顶部：上报给面板宿主，用于决定是否拦截下拉手势。 */
    contentAtTopChange: (Boolean) -> Unit = {},
    /** 标题栏「更多」入口：唤出迷你聊天窗（与双击悬浮球一致）。 */
    onOpenMiniChat: () -> Unit = {},
    /** 与主聊天页/迷你窗共享的会话，供「继续追问」内嵌聊天复用。 */
    chatHolder: ChatSessionHolder? = null,
    chatProviders: List<ModelProviderConfig> = emptyList(),
    chatCatalog: ChatModelCatalog? = null,
    /** 「继续追问」展开状态变化：宿主据此切换面板窗口是否可获取输入焦点（弹出软键盘）。 */
    onChatExpandedChange: (Boolean) -> Unit = {},
    /**
     * 「继续追问已展开且键盘可见」状态变化：宿主据此把面板窗口向上扩展，
     * 把顶部空余（面板未覆盖的那部分屏幕）也让给聊天内容区。
     */
    onChatImeChange: (Boolean) -> Unit = {},
    /** 发送消息时附带的当前分析页内容（原图/OCR/题目/答案），null 表示无可用内容。 */
    pageContext: (() -> String?)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    // 「继续追问」聊天区展开状态：点标题展开/收起，收起后面板恢复不可聚焦。
    var chatExpanded by remember { mutableStateOf(false) }
    // 软键盘高度：展开聊天时键盘把可视区压缩，需要把页面滚到最底让输入框停在键盘上方。
    val imeBottom = WindowInsets.ime.getBottom(density)
    DisposableEffect(chatExpanded) {
        onDispose { if (chatExpanded) onChatExpandedChange(false) }
    }
    LaunchedEffect(chatExpanded) { onChatExpandedChange(chatExpanded) }
    // 展开聊天且键盘弹出时，通知宿主把面板窗口向上扩展（抢占面板上方的空余屏幕），
    // 中间消息区可再获得约一个状态栏以上的高度；键盘收起或聊天收起后恢复。
    LaunchedEffect(chatExpanded, imeBottom) {
        onChatImeChange(chatExpanded && imeBottom > 0)
    }
    DisposableEffect(Unit) {
        onDispose { onChatImeChange(false) }
    }
    // 展开后页面必须停在最底部：见下方 BoxWithConstraints 内的滚动锚定逻辑。

    // 「原图」逐屏数据：长截图时按各屏区段展示/切换，普通截图只有一屏。
    val previewSource = uiState.selectedBitmap
    val previewSegments = remember(previewSource, uiState.longPageSegments) {
        val source = previewSource
        when {
            source == null -> emptyList()
            uiState.longPageSegments.size > 1 -> uiState.longPageSegments
            else -> listOf(LongPageSegment(0, source.height))
        }
    }
    val multiPage = previewSegments.size > 1
    // 点击「原图」里的某一屏后打开查看层：非 null 表示正在查看，值是起始屏下标。
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    val answerText = uiState.liveAnswerText
    // 朗读共享状态：分析页「答案」模块与聊天页共用同一 TTS 引擎（TtsHelper）与朗读状态。
    val readingMessageId by TtsHelper.readingMessageId.collectAsState()
    // 幂等补充初始化（AppContainer 启动时已初始化）；面板退出组合时若在朗读本面板内容则停止。
    DisposableEffect(Unit) {
        TtsHelper.init(context)
        onDispose {
            if (TtsHelper.readingMessageId.value == ANSWER_SPEECH_ID ||
                TtsHelper.readingMessageId.value == QUESTION_SPEECH_ID
            ) {
                TtsHelper.stop()
            }
        }
    }
    // 新一轮分析开始时停止朗读旧答案，避免朗读已过期的内容。
    LaunchedEffect(uiState.working) {
        if (uiState.working &&
            (TtsHelper.readingMessageId.value == ANSWER_SPEECH_ID ||
                TtsHelper.readingMessageId.value == QUESTION_SPEECH_ID)
        ) {
            TtsHelper.stop()
        }
    }
    // 滚动位置变化会触发重组，从而同步面板宿主的拦截开关。
    val contentAtTop = scrollState.value <= 0
    SideEffect { contentAtTopChange(contentAtTop) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        var closeRequested by remember { mutableStateOf(false) }

        // 页面内容始终填满整个窗口：无论窗口当前高度如何（静止/动画/拖拽），
        // 都不留透明底部条，避免下层应用（如哔哩哔哩导航栏）透过空隙可见。
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                // 键盘弹出且聊天区已展开时，把面板标题行（Hanako / 更多 / ×）整行让给聊天内容区，
                // 收起键盘即恢复；对话页自身顶部的导航栏保持不动。
                if (!(chatExpanded && imeBottom > 0)) {
                    SheetTitleRow(
                        title = {
                            Text("Hanako")
                        },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 8.dp),
                        actions = {
                            // 「更多」入口（与设置页「更多」同一图标）：唤出迷你聊天窗，
                            // 与双击悬浮球一致；聊天内容实时读取当前分析页。
                            IconButton(
                                onClick = onOpenMiniChat,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.SmartToy,
                                    contentDescription = "更多",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        onClose = {
                            if (closeRequested) return@SheetTitleRow
                            closeRequested = true
                            onClose()
                        }
                    )
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        // 悬浮窗用 adjustNothing，键盘内边距由这里自行处理：键盘弹出时压缩可视区，
                        // 页面滚动到底即可让输入框停在键盘上方（等价于 Activity 的 adjustResize）。
                        .imePadding()
                ) {
                    // 分析页可视区高度：展开「继续追问」时让聊天卡片正好占满它。
                    val viewportHeight = maxHeight
                    // 可视区高度变化（展开/收起、键盘弹出、面板窗口上扩）后都要重新锚到底部：
                    // maxValue 要等布局完成才更新，所以逐帧重试几次直到滚到最新的最大值，
                    // 否则会停在旧的最大值上，卡片上下各留出一条空隙。
                    LaunchedEffect(chatExpanded, viewportHeight) {
                        if (!chatExpanded) return@LaunchedEffect
                        withFrameNanos { }
                        repeat(3) {
                            scrollState.scrollTo(scrollState.maxValue)
                            withFrameNanos { }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            // 展开时不再留上下内边距：卡片高度 = 可视区高度 + 标题行高度，
                            // 滚到底时标题行被顶到可视区上方、对话页则从可视区顶边一直铺到键盘上沿。
                            .padding(
                                start = 20.dp,
                                top = if (chatExpanded) 0.dp else 8.dp,
                                end = 20.dp,
                                bottom = if (chatExpanded) 0.dp else 16.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                    ResultCard(
                        title = "原图",
                        actions = {
                            if (previewSource != null) {
                                Text(
                                    text = if (multiPage) {
                                        "共 ${previewSegments.size} 屏 · 点击查看，可左右滑动切换"
                                    } else {
                                        "点击图片查看"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    ) {
                        val source = previewSource
                        if (source != null) {
                            val image = remember(source) { source.asImageBitmap() }
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                previewSegments.forEachIndexed { index, segment ->
                                    val top = segment.offsetY.coerceIn(
                                        0,
                                        (source.height - 1).coerceAtLeast(0)
                                    )
                                    val pageHeight = segment.height
                                        .coerceAtMost(source.height - top)
                                        .coerceAtLeast(1)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // 与聊天里的图片一样：点击后打开可缩放的全屏查看。
                                            .clickable { previewIndex = index },
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (multiPage) {
                                            Text(
                                                text = "第 ${index + 1} 屏 · 点击查看",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                // 标题在圆角之外：裁剪只作用于图片，避免「第」字被圆角吃掉。
                                                modifier = Modifier.padding(start = 10.dp)
                                            )
                                        }
                                        Canvas(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(20.dp))
                                                .aspectRatio(
                                                    source.width.toFloat() / pageHeight.toFloat()
                                                )
                                        ) {
                                            drawImage(
                                                image = image,
                                                srcOffset = IntOffset(0, top),
                                                srcSize = IntSize(source.width, pageHeight),
                                                dstSize = IntSize(
                                                    size.width.roundToInt(),
                                                    size.height.roundToInt()
                                                )
                                            )
                                        }
                                    }
                                }
                            }
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
                    // 「完整材料和题目」：把 OCR 原文过滤成 材料 → 题干 → 选项，
                    // 按刷题 App 的题面布局展示，方便核对模型答案是否对应这道原题。
                    //
                    // 该区域的文字来源是 OCR：只有「OCR模式 / 无障碍模式」会产出 OCR 原文，
                    // 「多模态模式」不做文字识别（liveOcrText 为空）。这里固定渲染这个区域，
                    // 没有文字时给出明确说明，避免不同处理模式（不同机型设置）上区域"凭空消失"。
                    val extractedQuestion = remember(uiState.liveOcrText) {
                        QuestionExtractor.extract(uiState.liveOcrText)
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
                    ResultCard(
                        title = "完整材料和题目",
                        actions = {
                            if (extractedQuestion.hasContent && questionText.isNotBlank()) {
                                // 朗读按钮（复制左边）：与「答案」区域的朗读交互、图标完全一致。
                                val isReading = readingMessageId == QUESTION_SPEECH_ID
                                SmallHeaderSpeak(
                                    isReading = isReading,
                                    contentDescription = "朗读题目",
                                    onClick = {
                                        if (isReading) {
                                            TtsHelper.stop()
                                        } else {
                                            TtsHelper.speak(questionText, QUESTION_SPEECH_ID)
                                        }
                                    }
                                )
                                SmallHeaderAction(
                                    label = "复制",
                                    onClick = {
                                        copyToClipboardWithToast(context, "Hanako 题目", questionText, "已复制题目与选项")
                                    }
                                )
                            }
                        }
                    ) {
                        when {
                            extractedQuestion.hasContent && questionText.isNotBlank() ->
                                MarkdownLatexText(
                                    content = questionText,
                                    modifier = Modifier.fillMaxWidth()
                                )

                            uiState.liveOcrText.isBlank() && uiState.working ->
                                LoadingLine("正在识别题目…")

                            uiState.liveOcrText.isBlank() ->
                                Text(
                                    "当前处理模式没有产生文字内容（「多模态模式」不做 OCR）。" +
                                        "切换到「OCR模式」后，这里会还原出材料、题干与选项。"
                                )

                            else -> Text("未能从 OCR 原文中识别出题干或选项")
                        }
                    }
                    ResultCard(
                        title = "答案",
                        actions = {
                            if (!uiState.working && answerText.isNotBlank()) {
                                // 朗读按钮（复制左边）：与聊天页朗读交互一致（点击朗读、再次点击停止、朗读中显示声波动画）。
                                val isReading = readingMessageId == ANSWER_SPEECH_ID
                                SmallHeaderSpeak(
                                    isReading = isReading,
                                    onClick = {
                                        if (isReading) TtsHelper.stop() else TtsHelper.speak(answerText, ANSWER_SPEECH_ID)
                                    }
                                )
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
                    // 「继续追问」：与底部导航「聊天」页同一个 [ChatScreen]（同会话、同功能），
                    // 点标题展开/收起。[chatHeaderPx] 实测标题行高度：展开时卡片高度 =
                    // 可视区高度 + 标题行高度，因此滚到底时标题行正好被顶到可视区上方（仍存在，
                    // 向上滚动即可看到并收起），中间的对话页则精确铺满整个可视区。
                    var chatHeaderPx by remember { mutableStateOf(0) }
                    val chatHeaderHeight = with(density) { chatHeaderPx.toDp() }
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
                                    .padding(
                                        start = 16.dp,
                                        end = 16.dp,
                                        top = 14.dp,
                                        bottom = 14.dp
                                    ),
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
                                    // 与「聊天」页完全相同的对话页面；宿主无 Activity，
                                    // 用空实现的 ActivityResultRegistryOwner 兜住相机/文件选择入口。
                                    CompositionLocalProvider(
                                        LocalActivityResultRegistryOwner provides
                                            noOpActivityResultRegistryOwner
                                    ) {
                                        ChatScreen(
                                            holder = holder,
                                            providers = chatProviders,
                                            catalog = catalog,
                                            applyStatusBarPadding = false,
                                            applyImePadding = false,
                                            // 输入区上下内边距压到 2dp：给中间的消息区让出更多高度，
                                            // 同时输入框那排功能键也更贴近键盘。
                                            inputBottomPadding = 2.dp,
                                            inputTopPadding = 2.dp,
                                            contextProvider = pageContext,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                        )
                                    }
                                } else {
                                    Text("聊天窗口暂不可用")
                                }
                            }
                        }
                    }
                    uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        // 查看「原图」中的截图：双指缩放 / 拖动 / 双击放大，左右滑动切换第 1/2/3… 屏。
        previewIndex?.let { startIndex ->
            val source = previewSource
            if (source != null && previewSegments.isNotEmpty()) {
                ZoomablePageViewer(
                    source = source,
                    segments = previewSegments,
                    initialIndex = startIndex,
                    onDismiss = { previewIndex = null }
                )
            }
        }

        SideEffect { closeRequested }
    }
}

/**
 * 结果页「原图」的查看层：与聊天页图片预览一致的手势体验（双指缩放 / 拖动 / 双击放大 / 单击关闭），
 * 并支持左右滑动切换不同的截图（多屏长截图时）。
 *
 * - 向左滑 = 上一张，向右滑 = 下一张（与用户约定一致）。
 * - 切换图片时**不重置**缩放/平移状态：`viewerScale` / `viewerOffset*` 只以 [source] 为 key。
 *
 * 悬浮窗内的 ComposeView 没有 Activity 的 OnBackPressedDispatcherOwner，不能使用 BackHandler，
 * 因此退出只依赖单击图片或点右上角「关闭」。
 */
@Composable
private fun ZoomablePageViewer(
    source: Bitmap,
    segments: List<LongPageSegment>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    var pageIndex by remember { mutableStateOf(initialIndex.coerceIn(0, segments.lastIndex)) }
    // 查看状态：切换截图时保持不变，只按 source 记忆一次。
    var viewerScale by remember(source) { mutableStateOf(1f) }
    var viewerOffsetX by remember(source) { mutableStateOf(0f) }
    var viewerOffsetY by remember(source) { mutableStateOf(0f) }
    val segment = segments.getOrNull(pageIndex)
    val pageBitmap = remember(source, segment) {
        when {
            segment == null -> null
            // 整张图就是这一屏时直接用原图，避免多余的内存拷贝。
            segment.offsetY == 0 && segment.height >= source.height -> source
            else -> cropLongPage(source, segment)
        }
    }
    val multiPage = segments.size > 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.97f))
            .pointerInput(source, segment) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (viewerScale * zoom).coerceIn(0.5f, 6f)
                    viewerScale = newScale
                    if (newScale > 1f) {
                        val maxX = (newScale - 1f) * size.width / 2f
                        val maxY = (newScale - 1f) * size.height / 2f
                        viewerOffsetX = (viewerOffsetX + pan.x).coerceIn(-maxX, maxX)
                        viewerOffsetY = (viewerOffsetY + pan.y).coerceIn(-maxY, maxY)
                    } else {
                        viewerOffsetX = 0f
                        viewerOffsetY = 0f
                    }
                }
            }
    ) {
        pageBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = if (multiPage) "第 ${pageIndex + 1} 屏" else "原图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = viewerScale
                        scaleY = viewerScale
                        translationX = viewerOffsetX
                        translationY = viewerOffsetY
                    }
                    .pointerInput(pageIndex) {
                        detectTapGestures(
                            onTap = { onDismiss() },
                            onDoubleTap = {
                                if (viewerScale > 1.05f) {
                                    viewerScale = 1f
                                    viewerOffsetX = 0f
                                    viewerOffsetY = 0f
                                } else {
                                    viewerScale = 2.5f
                                }
                            }
                        )
                    }
                    .swipeToSwitchPage(
                        // 实测方向：手指向左滑 → 下一张，向右滑 → 上一张。
                        onSwipeLeft = { if (pageIndex < segments.lastIndex) pageIndex++ },
                        onSwipeRight = { if (pageIndex > 0) pageIndex-- }
                    )
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (multiPage) {
                    "第 ${pageIndex + 1} / ${segments.size} 屏 · 左右滑动切换 · 双击放大 / 单击关闭"
                } else {
                    "原图 · 双指缩放 / 双击放大 / 单击关闭"
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
            Text(
                text = "关闭",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * 横向滑动回调：手指向左滑 → [onSwipeLeft]，向右滑 → [onSwipeRight]（切到上一张/下一张由调用方决定）。
 *
 * 只在**初始阶段**旁路观察、不消费事件，因此不影响图片自身的双指缩放 / 拖动 / 点击手势；
 * 要求横向位移超过宽度 12% 且明显大于纵向位移，避免放大后纵向拖动时误切换。
 */
private fun Modifier.swipeToSwitchPage(
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            val start = down.position
            var end = start
            var multiPointer = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) multiPointer = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                end = change.position
            }
            if (multiPointer) continue
            val deltaX = end.x - start.x
            val deltaY = end.y - start.y
            if (abs(deltaX) > size.width * 0.12f && abs(deltaX) > abs(deltaY) * 1.2f) {
                if (deltaX < 0f) onSwipeLeft() else onSwipeRight()
            }
        }
    }
}

/**
 * 从累计长图里裁出某一屏（供「原图」点击查看单屏使用）；区段无效时返回 null。
 */
private fun cropLongPage(source: Bitmap, segment: LongPageSegment): Bitmap? {
    val top = segment.offsetY.coerceIn(0, (source.height - 1).coerceAtLeast(0))
    val height = segment.height.coerceAtMost(source.height - top)
    if (height <= 0) return null
    return runCatching { Bitmap.createBitmap(source, 0, top, source.width, height) }.getOrNull()
}

/**
 * 从当前分析页状态组装聊天上下文：原图/OCR/答案等「当前页面内容」。
 *
 * 供「继续追问」内嵌聊天与迷你聊天窗在发送时注入模型，使助手能就**当前这页**的
 * 截图、题目与答案继续追问；没有可用内容时返回 null（退回普通聊天）。
 */
internal fun overlayResultPageContext(uiState: OverlayUiState): String? {
    val parts = mutableListOf<String>()
    val screenshot = uiState.selectedBitmap
    if (screenshot != null) {
        val pages = uiState.longPageSegments.size
        parts += if (pages > 1) "原图：共 $pages 屏长截图" else "原图：当前截图 1 屏"
    }
    if (uiState.liveOcrText.isNotBlank()) {
        parts += "OCR / 无障碍提取原文：\n${uiState.liveOcrText.trim()}"
    }
    if (uiState.liveAnswerText.isNotBlank()) {
        parts += "Hanako 当前页的答案：\n${uiState.liveAnswerText.trim()}"
    }
    if (parts.isEmpty()) return null
    return buildString {
        appendLine("以下是 Hanako 当前分析页面上的内容（用户正在就此追问）：")
        parts.forEach {
            appendLine(it)
            appendLine()
        }
    }.trim()
}

/**
 * 悬浮窗宿主（Service）没有 Activity，而「聊天」页 [ChatScreen] 里的
 * `rememberLauncherForActivityResult` 必须拿到一个 registry 才能注册，否则直接崩溃。
 * 这里提供「只注册、不启动」的空实现：相机 / 系统文件选择等需要跳转页面的入口在悬浮窗内
 * 不可用，但聊天页面本身（文字、语音、模型、历史、相册等）照常渲染与使用。
 */
private object NoOpActivityResultRegistry : ActivityResultRegistry() {
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?
    ) = Unit
}

private val noOpActivityResultRegistryOwner: ActivityResultRegistryOwner =
    object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = NoOpActivityResultRegistry
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
    /** 关闭按钮左侧的附加操作（如分析页的「更多」入口）。 */
    actions: @Composable RowScope.() -> Unit = {},
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
        actions()
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

/** 答案模块的朗读按钮：样式与「复制」一致（圆角胶囊），朗读中显示与聊天页相同的声波动画。 */
@Composable
private fun SmallHeaderSpeak(
    isReading: Boolean,
    onClick: () -> Unit,
    contentDescription: String = "朗读答案"
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (isReading) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            )
            .clickable {
                HapticHelper.lightTap()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isReading) {
            ReadingSpeakerIcon(
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                Icons.Default.VolumeUp,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
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
