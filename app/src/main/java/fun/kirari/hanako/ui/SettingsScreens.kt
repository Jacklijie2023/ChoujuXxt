@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package `fun`.kirari.hanako.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.AssistantPreset
import `fun`.kirari.hanako.data.LOCAL_OCR_PROVIDER_ID
import `fun`.kirari.hanako.data.previewPrompt
import `fun`.kirari.hanako.data.AutomationSettings
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProviderPreset
import `fun`.kirari.hanako.data.ScreenCaptureMethod
import `fun`.kirari.hanako.data.description
import `fun`.kirari.hanako.data.displayName
import `fun`.kirari.hanako.accessibility.HanakoAccessibilityService
import `fun`.kirari.hanako.data.resolveModelName
import `fun`.kirari.hanako.data.resolveModelProvider
import `fun`.kirari.hanako.screencontext.TargetAppMode
import `fun`.kirari.hanako.ui.components.AssistantSelector
import `fun`.kirari.hanako.ui.components.ModelButtonField
import `fun`.kirari.hanako.ui.components.ProviderEditor
import `fun`.kirari.hanako.ui.components.SectionCard
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Slider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import `fun`.kirari.hanako.data.FloatingBallSettings
import `fun`.kirari.hanako.data.MiniChatSettings
import `fun`.kirari.hanako.data.ThemeColorStyle
import `fun`.kirari.hanako.data.ThemeMode
import `fun`.kirari.hanako.overlay.TtsHelper
import `fun`.kirari.hanako.ui.theme.ForestLightPrimary
import `fun`.kirari.hanako.ui.theme.HanakoLightPrimary
import `fun`.kirari.hanako.ui.theme.MidnightLightPrimary
import `fun`.kirari.hanako.ui.theme.PinkLightPrimary
import `fun`.kirari.hanako.util.FloatingBallIconStore
import kotlin.math.roundToInt

@Composable
fun SettingsMenuScreen(
    onNavigateProvider: () -> Unit,
    onNavigateModel: () -> Unit,
    onNavigateAssistant: () -> Unit,
    onNavigateMore: () -> Unit,
    onNavigateAppearance: () -> Unit,
    onNavigateDebugLogs: () -> Unit,
    onNavigateRealtimeTest: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsEntryCard(
                title = "模型提供方",
                subtitle = "配置 API 地址与模型",
                icon = Icons.Default.Build,
                onClick = onNavigateProvider
            )
        }
        item {
            SettingsEntryCard(
                title = "模型设置",
                subtitle = "为 OCR、文本、多模态分别指定提供方和模型",
                icon = Icons.Default.Memory,
                onClick = onNavigateModel
            )
        }
        item {
            SettingsEntryCard(
                title = "助手配置",
                subtitle = "管理助手名称与提示词",
                icon = Icons.Default.Person,
                onClick = onNavigateAssistant
            )
        }
        item {
            SettingsEntryCard(
                title = "外观与交互",
                subtitle = "悬浮球图片、迷你聊天窗透明度、朗读语音",
                icon = Icons.Default.Palette,
                onClick = onNavigateAppearance
            )
        }
        item {
            SettingsEntryCard(
                title = "更多",
                subtitle = "自动模式、屏幕录制方式、网络兼容",
                icon = Icons.Default.SmartToy,
                onClick = onNavigateMore
            )
        }

        item {
            val isA11yRunning = remember { mutableStateOf(HanakoAccessibilityService.isRunning()) }
            // 每次进入页面刷新状态
            androidx.compose.runtime.DisposableEffect(Unit) {
                isA11yRunning.value = HanakoAccessibilityService.isRunning()
                onDispose {}
            }
            val context = LocalContext.current
            SettingsEntryCard(
                title = "无障碍服务",
                subtitle = if (isA11yRunning.value) "✓ 已启用 — 可使用无障碍文本提取模式" else "未启用 — 点击前往系统设置开启",
                icon = Icons.Default.Security,
                onClick = {
                    try {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {}
                }
            )
        }

        item {
            // 实时互动测试中心：使用真实运行时与证据库数据，验证采集 / 过滤 / 图文请求链路。
            SettingsEntryCard(
                title = "实时互动测试中心",
                subtitle = "运行状态 · 原始证据 · 过滤对照 · 图像与请求追踪（全部真实数据）",
                icon = Icons.Default.Timer,
                onClick = onNavigateRealtimeTest
            )
        }

        item {
            // 入口补全：`onNavigateDebugLogs` 此前已由 HanakoApp 传入并注册了
            // ROUTE_SETTINGS_DEBUG_LOGS 路由，但菜单里没有对应卡片，导致调试日志页不可达。
            SettingsEntryCard(
                title = "调试日志",
                subtitle = "查看并导出应用内诊断日志（Logcat 看不到日志时用这里导出）",
                icon = Icons.Default.BugReport,
                onClick = onNavigateDebugLogs
            )
        }
    }
}

@Composable
fun MoreSettingsScreen(
    automationSettings: AutomationSettings,
    selectedMethod: ScreenCaptureMethod,
    trustAllHttpsCertificates: Boolean,
    onToggleCompletionNotification: (Boolean) -> Unit,
    onUpdateTimeoutSeconds: (Int) -> Unit,
    onSelectMethod: (ScreenCaptureMethod) -> Unit,
    onToggleTrustAllHttpsCertificates: (Boolean) -> Unit
) {
    var timeoutInput by remember(automationSettings.autoModeTimeoutSeconds) {
        mutableStateOf(automationSettings.autoModeTimeoutSeconds.toString())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            MoreSettingCard(
                icon = Icons.Default.SmartToy,
                title = "自动模式",
                subtitle = "主页长按启动进入自动模式。"
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "完成后发送通知",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "处理完成后发送系统通知。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = automationSettings.completionNotificationEnabled,
                            onCheckedChange = onToggleCompletionNotification
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "自动模式超时时间",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "首字延迟超时，当前用于流式请求在收到第一段输出前的等待时间。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = timeoutInput,
                        onValueChange = { value ->
                            val digits = value.filter(Char::isDigit)
                            timeoutInput = digits
                            digits.toIntOrNull()?.takeIf { it > 0 }?.let(onUpdateTimeoutSeconds)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("秒") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text(
                        "默认 30 秒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            MoreSettingCard(
                icon = Icons.Default.Security,
                title = "网络兼容",
                subtitle = "HTTP 与自签 HTTPS 测试接口。"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "信任所有 HTTPS 证书",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "允许连接自签名、过期或域名不匹配的 HTTPS 服务。存在安全风险，仅建议用于个人测试接口。HTTP 明文访问已允许。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = trustAllHttpsCertificates,
                        onCheckedChange = onToggleTrustAllHttpsCertificates
                    )
                }
            }
        }

        item {
            MoreSettingCard(
                icon = Icons.Default.PhoneAndroid,
                title = "屏幕录制方式",
                subtitle = "管理当前激活的截图实现。"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScreenCaptureMethod.entries.forEach { method ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectMethod(method) },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selectedMethod == method) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = method.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = method.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                RadioButton(
                                    selected = selectedMethod == method,
                                    onClick = { onSelectMethod(method) }
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
fun ProviderSettingsScreen(
    settings: AppSettings,
    onAddProvider: (ProviderPreset) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onOpenProvider: (String) -> Unit
) {
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "模型提供方",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { showAddDialog = true }) {
                    Text("新增")
                }
            }
        }
        items(settings.providers, key = { it.id }) { provider ->
            Surface(
                modifier = Modifier.combinedClickable(
                    onClick = { onOpenProvider(provider.id) },
                    onLongClick = { deleteTargetId = provider.id }
                ),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            provider.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            provider.kind.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    val deleteTarget = settings.providers.firstOrNull { it.id == deleteTargetId }
    if (deleteTarget != null) {
        DeleteConfirmDialog(
            title = "删除提供方",
            message = "确认删除 ${deleteTarget.name}？",
            onDismiss = { deleteTargetId = null },
            onConfirm = {
                onDeleteProvider(deleteTarget.id)
                deleteTargetId = null
            }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增提供方") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderPreset.entries.forEach { preset ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAddDialog = false
                                    onAddProvider(preset)
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    preset.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    preset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun ProviderDetailScreen(
    provider: ModelProviderConfig,
    connectionTestState: ConnectionTestState,
    onUpdateProvider: (ModelProviderConfig) -> Unit,
    onViewModels: () -> Unit,
    onTestConnection: (ModelProviderConfig) -> Unit
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionCard(title = "") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProviderEditor(
                        provider = provider,
                        onChange = onUpdateProvider,
                        onImportResult = { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    )
                    OutlinedButton(
                        onClick = onViewModels,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("查看可用模型")
                    }
                    OutlinedButton(
                        onClick = { onTestConnection(provider) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = connectionTestState.status != ConnectionTestStatus.TESTING
                    ) {
                        if (connectionTestState.status == ConnectionTestStatus.TESTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("测试中...")
                        } else {
                            Text("测试连接")
                        }
                    }
                    ConnectionTestResultBanner(connectionTestState)
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
fun ModelSettingsScreen(
    settings: AppSettings,
    onPickModel: (ModelPurpose) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionCard(title = "模型设置") {
                ModelPurpose.entries.forEach { purpose ->
                    val provider = settings.resolveModelProvider(purpose)
                    val model = settings.resolveModelName(purpose)
                    ModelButtonField(
                        label = "${purpose.displayName} 模型",
                        value = buildString {
                            append(
                                when {
                                    purpose == ModelPurpose.OCR && settings.ocrModelSelection.providerId == LOCAL_OCR_PROVIDER_ID ->
                                        "本地ML Kit"
                                    provider != null -> provider.name
                                    else -> "未选择提供方"
                                }
                            )
                            if (model.isNotBlank()) {
                                append(" / ")
                                append(model)
                            }
                        },
                        onPick = { onPickModel(purpose) }
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
fun AssistantSettingsScreen(
    settings: AppSettings,
    onAddAssistant: () -> Unit,
    onDeleteAssistant: (String) -> Unit,
    onOpenAssistant: (String) -> Unit
) {
    var deleteTargetId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "助手配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = onAddAssistant) {
                    Text("新增")
                }
            }
        }
        items(settings.assistants, key = { it.id }) { assistant ->
            Surface(
                modifier = Modifier.combinedClickable(
                    onClick = { onOpenAssistant(assistant.id) },
                    onLongClick = { deleteTargetId = assistant.id }
                ),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            assistant.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            assistant.previewPrompt().replace('\n', ' '),
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    val deleteTarget = settings.assistants.firstOrNull { it.id == deleteTargetId }
    if (deleteTarget != null) {
        DeleteConfirmDialog(
            title = "删除助手",
            message = "确认删除 ${deleteTarget.name}？",
            onDismiss = { deleteTargetId = null },
            onConfirm = {
                onDeleteAssistant(deleteTarget.id)
                deleteTargetId = null
            }
        )
    }
}

@Composable
fun AssistantDetailScreen(
    assistant: AssistantPreset,
    onUpdateAssistant: (AssistantPreset) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionCard(title = "助手配置") {
                AssistantSelector(
                    assistant = assistant,
                    onChange = onUpdateAssistant
                )
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun SettingsEntryCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MoreSettingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun ConnectionTestResultBanner(state: ConnectionTestState) {
    when (state.status) {
        ConnectionTestStatus.IDLE, ConnectionTestStatus.TESTING -> {}
        ConnectionTestStatus.SUCCESS -> {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "连接成功 (${state.latencyMs}ms)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        ConnectionTestStatus.FAILED -> {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "连接失败",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        icon = {
            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
    )
}

/**
 * 外观与交互设置页：主题、悬浮球图片、迷你聊天窗透明度、朗读语音。
 *
 * 从「设置 → 外观与交互」入口进入。
 */
@Composable
fun AppearanceSettingsScreen(
    floatingBall: FloatingBallSettings,
    miniChat: MiniChatSettings,
    themeMode: ThemeMode,
    colorStyle: ThemeColorStyle,
    ttsVoiceName: String?,
    realTimeMode: Boolean,
    targetAppPackage: String?,
    targetAppMode: TargetAppMode,
    boundTargetPackage: String?,
    onSetFloatingBallCustomIcon: (String?) -> Unit,
    onSetMiniChatAlpha: (Float) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetColorStyle: (ThemeColorStyle) -> Unit,
    onSetTtsVoiceName: (String?) -> Unit,
    onSetRealTimeMode: (Boolean) -> Unit,
    onSetTargetAppPackage: (String?) -> Unit,
    onSetTargetAppMode: (TargetAppMode) -> Unit,
    onRebindToForeground: () -> Unit,
    onRebindToManual: () -> Unit
) {
    val context = LocalContext.current
    var showTargetAppPicker by remember { mutableStateOf(false) }
    val voices by TtsHelper.availableVoices.collectAsState()
    var customIconBitmap by remember(floatingBall.customIconPath) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(floatingBall.customIconPath) {
        customIconBitmap = floatingBall.customIconPath?.let {
            FloatingBallIconStore.loadDrawable(context, it)?.bitmap
        }
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val saved = FloatingBallIconStore.save(context, uri)
            if (saved != null) {
                onSetFloatingBallCustomIcon(saved.absolutePath)
                Toast.makeText(context, "悬浮球图片已更新", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "图片读取失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // 引擎未就绪时初始化，并将已保存的朗读语音应用到 TTS。
    LaunchedEffect(Unit) {
        if (!TtsHelper.isReady) TtsHelper.init(context)
        TtsHelper.setVoice(ttsVoiceName)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MoreSettingCard(
                icon = Icons.Default.Visibility,
                title = "实时屏幕感知",
                subtitle = "在其他 App 刷题时持续更新当前屏幕文字，发送指令自动带入当前题目"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "启用实时感知",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "仅本地提取（无障碍 + 本地 OCR），后台不会持续调用云端视觉或 OCR 模型；只有在你发送分析请求时，才把当前屏幕文字交给文本大模型。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = realTimeMode,
                        onCheckedChange = onSetRealTimeMode
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "目标刷题应用",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "只有该应用的屏幕内容会被采集为题目上下文。切换到桌面、通知栏或其它应用时，" +
                            "不会建立新题，也不会覆盖该应用已采集的题目内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (targetAppPackage == null) {
                            "未选择：当前不会采集任何题目内容"
                        } else {
                            "${installedAppLabel(context, targetAppPackage)} · $targetAppPackage"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (targetAppPackage == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showTargetAppPicker = true }) {
                            Text(if (targetAppPackage == null) "选择应用" else "更换应用")
                        }
                        if (targetAppPackage != null) {
                            TextButton(onClick = { onSetTargetAppPackage(null) }) { Text("清除选择") }
                        }
                    }
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        "绑定模式",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    ThemeModeOptionRow(
                        label = "自动（首次唤醒时按实时前台应用绑定）",
                        selected = targetAppMode == TargetAppMode.AUTO,
                        onClick = { onSetTargetAppMode(TargetAppMode.AUTO) }
                    )
                    ThemeModeOptionRow(
                        label = "限制（仅手动选择的应用在实时前台时绑定）",
                        selected = targetAppMode == TargetAppMode.RESTRICTED,
                        onClick = { onSetTargetAppMode(TargetAppMode.RESTRICTED) }
                    )
                    Text(
                        "当前已绑定：" + (
                            boundTargetPackage?.let { "${installedAppLabel(context, it)} · $it" } ?: "未绑定"
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (boundTargetPackage == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRebindToForeground) {
                            Text("重新绑定到当前前台应用")
                        }
                        if (targetAppPackage != null) {
                            TextButton(onClick = onRebindToManual) { Text("按手动选择重绑") }
                        }
                    }
                }
            }
        }

        item {
            MoreSettingCard(
                icon = Icons.Default.Palette,
                title = "主题",
                subtitle = "切换界面亮暗模式与配色风格"
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "亮暗模式",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    ThemeModeOptionRow("跟随系统", themeMode == ThemeMode.SYSTEM) { onSetThemeMode(ThemeMode.SYSTEM) }
                    ThemeModeOptionRow("白天", themeMode == ThemeMode.LIGHT) { onSetThemeMode(ThemeMode.LIGHT) }
                    ThemeModeOptionRow("黑夜", themeMode == ThemeMode.DARK) { onSetThemeMode(ThemeMode.DARK) }
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        "配色风格",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ColorStyleDot("默认", HanakoLightPrimary, colorStyle == ThemeColorStyle.DEFAULT) {
                            onSetColorStyle(ThemeColorStyle.DEFAULT)
                        }
                        ColorStyleDot("粉黛", PinkLightPrimary, colorStyle == ThemeColorStyle.PINK) {
                            onSetColorStyle(ThemeColorStyle.PINK)
                        }
                        ColorStyleDot("森林", ForestLightPrimary, colorStyle == ThemeColorStyle.FOREST) {
                            onSetColorStyle(ThemeColorStyle.FOREST)
                        }
                        ColorStyleDot("星空", MidnightLightPrimary, colorStyle == ThemeColorStyle.MIDNIGHT) {
                            onSetColorStyle(ThemeColorStyle.MIDNIGHT)
                        }
                    }
                }
            }
        }

        item {
            MoreSettingCard(
                icon = Icons.Default.Adjust,
                title = "悬浮球",
                subtitle = "自定义悬浮球展示图片；上传图片会自动高清缩小，保证完整展示整张图"
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (customIconBitmap != null) {
                                Image(
                                    bitmap = customIconBitmap!!.asImageBitmap(),
                                    contentDescription = "当前悬浮球图标",
                                    modifier = Modifier.size(44.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Icon(
                                    Icons.Default.Adjust,
                                    contentDescription = "默认悬浮球图标",
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "当前图标",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (floatingBall.customIconPath != null) "自定义图片" else "默认图标",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        ) {
                            Text(if (floatingBall.customIconPath != null) "更换悬浮球图片" else "上传悬浮球图片")
                        }
                        if (floatingBall.customIconPath != null) {
                            TextButton(onClick = {
                                FloatingBallIconStore.delete(context)
                                onSetFloatingBallCustomIcon(null)
                            }) {
                                Text("恢复默认")
                            }
                        }
                    }
                }
            }
        }

        item {
            MoreSettingCard(
                icon = Icons.Default.CropSquare,
                title = "迷你聊天窗",
                subtitle = "调整迷你聊天窗口的整体透明度"
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "透明度：${(miniChat.backgroundAlpha * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = miniChat.backgroundAlpha,
                        onValueChange = onSetMiniChatAlpha,
                        valueRange = 0.3f..1f,
                        steps = 13
                    )
                    Text(
                        "数值越小窗口越透明，下方应用内容越清晰可见。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            MoreSettingCard(
                icon = Icons.Default.RecordVoiceOver,
                title = "朗读语音",
                subtitle = "选择朗读内容时的发音人物（由系统 TTS 引擎提供）"
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (voices.isEmpty()) {
                        Text(
                            "暂未发现可用的朗读语音，请检查系统「文字转语音」引擎设置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        VoiceOptionRow(
                            label = "跟随系统默认",
                            selected = ttsVoiceName == null,
                            onClick = {
                                TtsHelper.setVoice(null)
                                onSetTtsVoiceName(null)
                            }
                        )
                        voices.forEach { voice ->
                            VoiceOptionRow(
                                label = voice.displayName,
                                selected = ttsVoiceName == voice.name,
                                onClick = {
                                    TtsHelper.setVoice(voice.name)
                                    onSetTtsVoiceName(voice.name)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 目标应用选择对话框：只有被选中的应用才允许建立题目上下文。
    if (showTargetAppPicker) {
        TargetAppPickerDialog(
            currentPackage = targetAppPackage,
            onDismiss = { showTargetAppPicker = false },
            onPick = { packageName ->
                onSetTargetAppPackage(packageName)
                showTargetAppPicker = false
            }
        )
    }
}

/**
 * 目标刷题应用选择对话框：列出设备上可启动的应用（排除本应用自身），单选一项作为目标应用。
 *
 * 应用列表在 IO 线程加载，避免在主线程查询 PackageManager 造成卡顿。
 */
@Composable
private fun TargetAppPickerDialog(
    currentPackage: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<InstalledAppOption>()) {
        value = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标刷题应用") },
        text = {
            if (apps.isEmpty()) {
                Text("未找到可选择的已安装应用")
            } else {
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app.packageName) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = app.packageName == currentPackage,
                                onClick = { onPick(app.packageName) }
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 可选择的已安装应用（仅标签与包名，非敏感）。 */
private data class InstalledAppOption(val packageName: String, val label: String)

/** 读取设备上带启动入口的应用列表（排除本应用自身），按标签排序。 */
@Suppress("DEPRECATION")
private fun loadInstalledApps(context: Context): List<InstalledAppOption> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        packageManager.queryIntentActivities(launcherIntent, 0).mapNotNull { info ->
            val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            InstalledAppOption(
                packageName = appInfo.packageName,
                label = packageManager.getApplicationLabel(appInfo).toString()
            )
        }
    }.getOrElse { emptyList() }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label }
}

/** 取已安装应用的显示名；取不到时回退为包名（不因查询失败而崩溃）。 */
@Suppress("DEPRECATION")
private fun installedAppLabel(context: Context, packageName: String): String = runCatching {
    val packageManager = context.packageManager
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

@Composable
private fun VoiceOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ThemeModeOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ColorStyleDot(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color, CircleShape)
                .then(
                    if (selected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
