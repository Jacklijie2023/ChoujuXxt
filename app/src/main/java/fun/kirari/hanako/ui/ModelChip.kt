package `fun`.kirari.hanako.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.chat.ChatModelCatalog
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.chat.ProviderModelListState
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.network.RemoteModelOption

/** 模型胶囊固定宽度：不随模型名称长度伸缩，避免挤掉顶栏标题与其它功能按钮。 */
private val CHIP_FIXED_WIDTH = 120.dp

/** 模型选择器内部扁平条目：模型 + 提供商小字。 */
private data class FlatModelEntry(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val displayName: String
)

/**
 * 聊天模型选择器（聊天页与迷你窗共用同一实现）。
 *
 * - 轻量胶囊：当前模型名 + 提供商小字 + 下拉箭头，点击弹出「聊天设置」。
 * - 数据源 `settings.providers` + [ChatModelCatalog] 内存缓存（按 providerId 拉全量模型），
 *   含 loading / error（回退展示该 API 已配置的 chatModel）状态。
 * - 选中写回当前会话 `selectedProviderId/selectedModel`；未选回退默认文本模型。
 * - 功能开关（联网搜索 / 思考模式 / 图片附件）仅本地占位状态，不接真实能力。
 *
 * @param useBottomSheet 为 true 时用 [ModalBottomSheet]（图 3 形态，仅限 Activity 宿主）。
 * @param onOpenSettings 迷你窗等无 Activity token 的宿主必须传入：Dialog / AlertDialog /
 *   ModalBottomSheet 都会创建新窗口，而 overlay Service 宿主无有效 token，会触发
 *   `WindowManager$BadTokenException: token null is not valid` 闪退。传入后点击胶囊
 *   仅回调宿主（由宿主在悬浮窗内覆盖渲染 [ChatSettingsContent]），组件自身不再弹窗。
 */
@Composable
internal fun ModelChip(
    holder: ChatSessionHolder,
    providers: List<ModelProviderConfig>,
    catalog: ChatModelCatalog,
    modifier: Modifier = Modifier,
    useBottomSheet: Boolean = true,
    onOpenSettings: (() -> Unit)? = null
) {
    var showPicker by remember { mutableStateOf(false) }
    val session by holder.session.collectAsState()
    val selectedProvider = providers.firstOrNull { it.id == session.selectedProviderId }

    val label = when {
        session.selectedProviderId == null || selectedProvider == null -> "默认文本模型"
        session.selectedModel.isNotBlank() -> session.selectedModel
        else -> selectedProvider.chatModel.ifBlank { "默认文本模型" }
    }

    Surface(
        onClick = {
            if (onOpenSettings != null) {
                onOpenSettings()
            } else {
                showPicker = true
            }
        },
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        // 固定胶囊宽度（不随模型名称长度伸缩，避免挤掉顶栏标题与其它功能按钮）；
        // 名称过长时 label 截断为省略号，提供商名同样受限。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .width(CHIP_FIXED_WIDTH)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (selectedProvider != null) {
                Text(
                    text = selectedProvider.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 44.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 无 Activity token 的宿主（迷你窗）通过 onOpenSettings 由窗口内渲染，
    // 此处仅在 Activity 宿主下使用内置弹层，避免 BadTokenException。
    if (onOpenSettings == null && showPicker) {
        if (useBottomSheet) {
            ChatSettingsSheet(
                holder = holder,
                providers = providers,
                catalog = catalog,
                onDismiss = { showPicker = false }
            )
        }
    }
}

/** 聊天页「聊天设置」底部弹窗（图 3 形态）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSettingsSheet(
    holder: ChatSessionHolder,
    providers: List<ModelProviderConfig>,
    catalog: ChatModelCatalog,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ChatSettingsContent(
            holder = holder,
            providers = providers,
            catalog = catalog,
            onDismiss = onDismiss
        )
    }
}

/** 「聊天设置」共享内容：横向模型卡片 + 功能开关列表。迷你窗宿主在窗口内覆盖渲染此内容。 */
@Composable
internal fun ChatSettingsContent(
    holder: ChatSessionHolder,
    providers: List<ModelProviderConfig>,
    catalog: ChatModelCatalog,
    onDismiss: () -> Unit,
    showTitle: Boolean = true
) {
    val session by holder.session.collectAsState()
    val modelStates by catalog.states.collectAsState()
    // 仅本地占位状态，不接真实能力。
    var webSearch by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    // 整改点 3：在「聊天设置」弹层内翻书式切换到「全部模型」页（弹层不关闭，页面状态不变）。
    var showAllModels by remember { mutableStateOf(false) }

    LaunchedEffect(providers) {
        providers.forEach { catalog.ensureLoaded(it) }
    }

    val entries = remember(providers, modelStates) {
        providers.flatMap { provider ->
            val state = modelStates[provider.id] ?: ProviderModelListState()
            when {
                state.loading -> emptyList()
                state.error -> listOf(
                    FlatModelEntry(provider.id, provider.name, provider.chatModel, provider.chatModel)
                )
                else -> state.models.ifEmpty {
                    listOf(RemoteModelOption(provider.chatModel, provider.chatModel))
                }.map { model ->
                    FlatModelEntry(provider.id, provider.name, model.id, model.displayName)
                }
            }
        }
    }

    val anyLoading = providers.any { (modelStates[it.id] ?: ProviderModelListState()).loading }
    val anyError = providers.any { (modelStates[it.id] ?: ProviderModelListState()).error }

    if (showAllModels) {
        AllModelsPage(
            entries = entries,
            selectedProviderId = session.selectedProviderId,
            selectedModel = session.selectedModel,
            onBack = { showAllModels = false },
            onSelect = { providerId, modelId ->
                holder.selectModel(providerId, modelId)
                showAllModels = false
            }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            if (showTitle) {
                Text(
                    text = "聊天设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "模型",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showAllModels = true }) {
                    Text("全部模型")
                }
            }

            if (anyLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("正在拉取模型列表…", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (anyError) {
                Text(
                    text = "部分模型列表拉取失败，已显示该 API 配置的默认模型，可前往「设置 → 模型设置」查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (session.selectedProviderId != null) {
                    item(key = "__default__") {
                        ModelCard(
                            name = "默认",
                            provider = "文本模型",
                            selected = session.selectedProviderId == null,
                            onClick = {
                                holder.selectModel(null, "")
                                onDismiss()
                            }
                        )
                    }
                }
                items(entries, key = { "${it.providerId}:${it.modelId}" }) { entry ->
                    val isSelected = entry.providerId == session.selectedProviderId &&
                        entry.modelId.equals(session.selectedModel, ignoreCase = true)
                    ModelCard(
                        name = entry.displayName,
                        provider = entry.providerName,
                        selected = isSelected,
                        onClick = {
                            holder.selectModel(entry.providerId, entry.modelId)
                            onDismiss()
                        }
                    )
                }
                if (entries.isEmpty() && !anyLoading) {
                    item(key = "__empty__") {
                        Text(
                            text = "暂无可用模型",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )

            Text(
                text = "高级功能",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
            )
            FeatureToggleRow(
                icon = Icons.Default.Public,
                label = "联网搜索",
                subtitle = "根据需要访问互联网（即将接入）",
                checked = webSearch,
                onCheckedChange = { webSearch = it }
            )
            FeatureToggleRow(
                icon = Icons.Default.Psychology,
                label = "思考模式",
                subtitle = "推理与思考（即将接入）",
                checked = thinking,
                onCheckedChange = { thinking = it }
            )
        }
    }
}

/** 「全部模型」翻书页（整改点 3）：返回 + 搜索 + 全部模型列表，选择后自动返回「聊天设置」。 */
@Composable
private fun AllModelsPage(
    entries: List<FlatModelEntry>,
    selectedProviderId: String?,
    selectedModel: String,
    onBack: () -> Unit,
    onSelect: (String, String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(entries, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            entries
        } else {
            entries.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                    it.modelId.contains(q, ignoreCase = true) ||
                    it.providerName.contains(q, ignoreCase = true)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回聊天设置")
            }
            Text(
                text = "全部模型",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("搜索模型") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Text(
                text = "未找到匹配的模型",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
        } else {
            filtered.forEach { entry ->
                val isSelected = entry.providerId == selectedProviderId &&
                    entry.modelId.equals(selectedModel, ignoreCase = true)
                AllModelsRow(
                    entry = entry,
                    selected = isSelected,
                    onClick = { onSelect(entry.providerId, entry.modelId) }
                )
            }
        }
    }
}

/** 全部模型列表行：模型名 + 提供商小字，选中态淡紫底 + 勾选图标。 */
@Composable
private fun AllModelsRow(
    entry: FlatModelEntry,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.providerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 横向模型卡片：选中态紫色边框 + 淡紫底。 */
@Composable
private fun ModelCard(
    name: String,
    provider: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier
                .width(120.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = provider,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 高级功能开关行（仅本地占位状态）。 */
@Composable
private fun FeatureToggleRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
