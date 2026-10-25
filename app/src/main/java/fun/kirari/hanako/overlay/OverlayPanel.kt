package `fun`.kirari.hanako.overlay

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import `fun`.kirari.hanako.data.ModelPurpose

internal enum class AssistantSwitchDirection {
    PREVIOUS,
    NEXT,
    PICKER
}

@Composable
internal fun OverlayPanel(
    viewModel: OverlayViewModel,
    onDismiss: () -> Unit,
    panelHeightPx: Int = 0,
    /** 内容区是否已滚动到顶部：由结果页上报，供面板宿主决定是否拦截下拉手势。 */
    contentAtTopChange: (Boolean) -> Unit = {},
    /** 结果页「继续追问」展开状态：宿主据此让面板窗口可获取输入焦点（弹出软键盘）。 */
    onChatExpandedChange: (Boolean) -> Unit = {},
    /** 「继续追问已展开且键盘可见」：宿主据此把面板窗口向上扩展，给消息区更多高度。 */
    onChatImeChange: (Boolean) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val chatProviders by viewModel.providers.collectAsState()
    var modelPickerTarget by remember { mutableStateOf<ModelPurpose?>(null) }
    when (uiState.sheetMode) {
        OverlaySheetMode.CROP -> {
            CropOverlaySheet(
                uiState = uiState,
                onClose = onDismiss,
                onConfirm = viewModel::process,
                onStartLong = viewModel::startLongCaptureSession,
                onContinueLong = viewModel::continueLongCapture,
                onConfirmLong = viewModel::confirmLongCapture,
                panelHeightPx = panelHeightPx,
                modelPickerTarget = modelPickerTarget,
                onModelPickerTargetChange = { modelPickerTarget = it },
                onSelectAssistant = viewModel::selectAssistant,
                onSelectPreviousAssistant = viewModel::selectPreviousAssistant,
                onSelectNextAssistant = viewModel::selectNextAssistant,
                onUpdateModelSelection = viewModel::updateModelSelectionWithFavorite,
                onToggleFavoriteModel = viewModel::toggleFavoriteModel,
                onToggleProcessingRoute = viewModel::toggleProcessingRoute
            )
        }

        OverlaySheetMode.RESULT -> {
            ResultOverlaySheet(
                uiState = uiState,
                onClose = onDismiss,
                panelHeightPx = panelHeightPx,
                contentAtTopChange = contentAtTopChange,
                // 「更多」入口：在保留当前分析页的前提下唤出迷你聊天窗（同一会话，
                // 发送时携带当前分析页内容）。
                onOpenMiniChat = viewModel::showMiniChatOverSheet,
                chatHolder = viewModel.chatSessionHolder,
                chatProviders = chatProviders,
                chatCatalog = viewModel.chatModelCatalog,
                onChatExpandedChange = onChatExpandedChange,
                onChatImeChange = onChatImeChange,
                pageContext = { overlayResultPageContext(uiState) }
            )
        }

        OverlaySheetMode.WEBVIEW -> {
            WebViewSheet(
                initialUrl = uiState.webViewUrl,
                onClose = onDismiss,
                onSearch = viewModel::searchInWebView
            )
        }
    }
}

internal fun assistantNameTransform(
    direction: AssistantSwitchDirection
): ContentTransform {
    val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(
        durationMillis = 240,
        easing = FastOutSlowInEasing
    )
    val fadeSpec = tween<Float>(
        durationMillis = 180,
        easing = FastOutSlowInEasing
    )
    return when (direction) {
        AssistantSwitchDirection.PREVIOUS ->
            (slideInHorizontally(animationSpec = slideSpec) { -it / 2 } + fadeIn(fadeSpec))
                .togetherWith(slideOutHorizontally(animationSpec = slideSpec) { it / 2 } + fadeOut(fadeSpec))

        AssistantSwitchDirection.NEXT ->
            (slideInHorizontally(animationSpec = slideSpec) { it / 2 } + fadeIn(fadeSpec))
                .togetherWith(slideOutHorizontally(animationSpec = slideSpec) { -it / 2 } + fadeOut(fadeSpec))

        AssistantSwitchDirection.PICKER ->
            fadeIn(fadeSpec).togetherWith(fadeOut(fadeSpec))
    }
}
