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
    /** 内容滚动到顶部后继续下拉时，跟随手指收缩面板（deltaY>0 为下拉）。 */
    onPanelDragBy: (Float) -> Unit = {},
    /** 松手吸附：低于阈值关闭，否则回停靠/全展开。 */
    onPanelSettle: () -> Unit = {},
    /** 顶部向下快速甩动（fling）：直接关闭面板。 */
    onPanelFlingDown: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var modelPickerTarget by remember { mutableStateOf<ModelPurpose?>(null) }
    when (uiState.sheetMode) {
        OverlaySheetMode.CROP -> {
            CropOverlaySheet(
                uiState = uiState,
                onClose = onDismiss,
                onConfirm = viewModel::process,
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
                onPanelDragBy = onPanelDragBy,
                onPanelSettle = onPanelSettle,
                onPanelFlingDown = onPanelFlingDown
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
