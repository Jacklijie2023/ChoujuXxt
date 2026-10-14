package `fun`.kirari.hanako.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProviderKind {
    OPENAI_COMPATIBLE,
    OPENAI_RESPONSES,
    ANTHROPIC,

    /**
     * Anthropic Messages 兼容协议（POST /v1/messages）。
     * 与 [ANTHROPIC] 复用同一个 Messages 适配器，但使用独立配置入口，
     * 以适配支持 Anthropic Messages 协议的中转站（可自定义 Base URL、API Key 与模型名称）。
     */
    ANTHROPIC_MESSAGES,
    GOOGLE
}

@Serializable
data class ModelProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "OpenAI Compatible",
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    val baseUrl: String = kind.defaultBaseUrl,
    val apiKey: String = "",
    val chatModel: String = "gpt-4o-mini",
    val visionModel: String = "gpt-4o",
    val ocrModel: String = "gpt-4.1-mini",
    val favoriteModels: List<String> = emptyList(),
    val enabled: Boolean = true
)

@Serializable
enum class ModelPurpose {
    OCR,
    TEXT,
    VISION
}

@Serializable
data class ModelSelection(
    val providerId: String? = null,
    val model: String = ""
)

const val LOCAL_OCR_PROVIDER_ID = "__local_mlkit__"
const val LOCAL_OCR_MODEL_ID = "mlkit_chinese_ocr"

@Serializable
data class LocalOcrSettings(
    val installed: Boolean = false,
    val lastMessage: String? = null,
    val providerId: String = LOCAL_OCR_PROVIDER_ID,
    val modelId: String = LOCAL_OCR_MODEL_ID,
    val displayName: String = "ML Kit 中文 OCR"
)

val ProviderKind.displayName: String
    get() = when (this) {
        ProviderKind.OPENAI_COMPATIBLE -> "OpenAI Compatible"
        ProviderKind.OPENAI_RESPONSES -> "OpenAI Responses"
        ProviderKind.ANTHROPIC -> "Anthropic"
        ProviderKind.ANTHROPIC_MESSAGES -> "Anthropic Messages"
        ProviderKind.GOOGLE -> "Google Gemini"
    }

val ProviderKind.defaultBaseUrl: String
    get() = when (this) {
        ProviderKind.OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
        ProviderKind.OPENAI_RESPONSES -> "https://api.openai.com/v1"
        ProviderKind.ANTHROPIC -> "https://api.anthropic.com/v1"
        ProviderKind.ANTHROPIC_MESSAGES -> "https://api.anthropic.com/v1"
        ProviderKind.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta"
    }

val ProviderKind.modelsRequestSuffix: String
    get() = when (this) {
        ProviderKind.OPENAI_COMPATIBLE -> "/models"
        ProviderKind.OPENAI_RESPONSES -> "/models"
        ProviderKind.ANTHROPIC -> "/models"
        ProviderKind.ANTHROPIC_MESSAGES -> "/models"
        ProviderKind.GOOGLE -> "/models?pageSize=100"
    }

val ProviderKind.requestPathSuffix: String
    get() = when (this) {
        ProviderKind.OPENAI_COMPATIBLE -> "/chat/completions"
        ProviderKind.OPENAI_RESPONSES -> "/responses"
        ProviderKind.ANTHROPIC -> "/messages"
        ProviderKind.ANTHROPIC_MESSAGES -> "/messages"
        ProviderKind.GOOGLE -> "/models"
    }

/** 形如 `v1`、`v1beta`、`v2` 的版本路径段。 */
private val HOST_VERSION_SEGMENT = Regex("^v\\d+(?:(?:alpha|beta)\\d*)?$", RegexOption.IGNORE_CASE)

/** Anthropic Messages 的聊天端点名，同时用于识别「已包含完整端点」的 Base URL。 */
private const val ANTHROPIC_MESSAGES_CHAT_ENDPOINT = "messages"

/**
 * 计算 Anthropic Messages 的最终请求 URL（聊天与模型列表共用同一套规则）。
 *
 * Base URL 语义：API 根地址。按以下规则补齐路径，避免出现 `/v1/v1/...` 或 `/messages/messages`：
 * 1. 去掉首尾空白与末尾 `/`；
 * 2. 若已以 `/messages` 结尾（完整聊天端点），取其前缀作为根，仅替换端点为 [endpoint]；
 * 3. 否则若最后一个路径段是版本段（如 `v1`、`v1beta`），追加 `/[endpoint]`；
 * 4. 否则（纯主机名或其它前缀）追加 `/v1/[endpoint]`。
 *
 * @param endpoint 端点名（聊天为 `messages`，模型列表为 `models`），保证二者路径前缀始终一致。
 */
private fun anthropicMessagesEndpointUrl(rawBaseUrl: String, endpoint: String): String {
    val base = rawBaseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return ""
    val chatSuffix = "/$ANTHROPIC_MESSAGES_CHAT_ENDPOINT"
    if (base.endsWith(chatSuffix, ignoreCase = true)) {
        return base.dropLast(chatSuffix.length) + "/$endpoint"
    }
    val lastSegment = base.substringAfterLast('/')
    return if (HOST_VERSION_SEGMENT.matches(lastSegment)) {
        "$base/$endpoint"
    } else {
        "$base/v1/$endpoint"
    }
}

/** Anthropic Messages 聊天请求的最终 URL。 */
internal fun anthropicMessagesRequestUrl(rawBaseUrl: String): String =
    anthropicMessagesEndpointUrl(rawBaseUrl, ANTHROPIC_MESSAGES_CHAT_ENDPOINT)

/** Anthropic Messages 模型列表请求的最终 URL（与聊天请求遵循同一套路径规则）。 */
internal fun anthropicMessagesModelsUrl(rawBaseUrl: String): String =
    anthropicMessagesEndpointUrl(rawBaseUrl, "models")

/** 该提供方实际会请求的完整 URL（供预览与真实请求共用，确保二者一致）。 */
fun ModelProviderConfig.resolvedRequestUrl(): String = when (kind) {
    ProviderKind.ANTHROPIC_MESSAGES -> anthropicMessagesRequestUrl(baseUrl)
    else -> "${baseUrl.trimEnd('/')}${kind.requestPathSuffix}"
}

fun ModelProviderConfig.requestPreviewUrl(): String = resolvedRequestUrl()

fun ModelProviderConfig.modelsRequestUrl(): String = when (kind) {
    // Anthropic Messages 的模型列表必须与聊天请求使用同一套路径归一化规则，
    // 否则根地址（如 https://seekai.cc）会出现聊天 /v1/messages 而模型列表 /models 的错位。
    ProviderKind.ANTHROPIC_MESSAGES -> anthropicMessagesModelsUrl(baseUrl)
    else -> "${baseUrl.trimEnd('/')}${kind.modelsRequestSuffix}"
}

@Serializable
data class AssistantPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ocrPrompt: String,
    val textPrompt: String,
    val visionPrompt: String
)

@Serializable
enum class ProcessingRoute {
    OCR_THEN_LLM,
    MULTIMODAL_DIRECT,
    ACCESSIBILITY_TEXT
}

@Serializable
enum class ScreenCaptureMethod {
    MEDIA_PROJECTION,
    SHIZUKU_ADB
}

@Serializable
data class AutomationSettings(
    val completionNotificationEnabled: Boolean = true,
    val autoModeTimeoutSeconds: Int = 30
)

@Serializable
data class AppSettings(
    val providers: List<ModelProviderConfig> = listOf(defaultProvider()),
    val selectedProviderId: String? = providers.firstOrNull()?.id,
    val assistants: List<AssistantPreset> = defaultAssistants(),
    val selectedAssistantId: String? = assistants.firstOrNull()?.id,
    val processingRoute: ProcessingRoute = ProcessingRoute.OCR_THEN_LLM,
    val screenCaptureMethod: ScreenCaptureMethod = ScreenCaptureMethod.MEDIA_PROJECTION,
    val automation: AutomationSettings = AutomationSettings(),
    val trustAllHttpsCertificates: Boolean = false,
    val textModelSelection: ModelSelection = ModelSelection(),
    val visionModelSelection: ModelSelection = ModelSelection(),
    val ocrModelSelection: ModelSelection = ModelSelection(),
    val localOcr: LocalOcrSettings = LocalOcrSettings(),
    val lastResult: ProcessingResult? = null,
    val history: List<ProcessingResult> = emptyList()
)

@Serializable
data class ProcessingResult(
    val id: String = UUID.randomUUID().toString(),
    val assistantName: String,
    val route: ProcessingRoute,
    val status: ProcessingStatus = ProcessingStatus.SUCCESS,
    val modelSummary: String = "",
    val detail: String = "",
    val extractedText: String = "",
    val answer: String = "",
    val automationThought: String = "",
    val automationAction: AutomationActionRecord? = null,
    val screenshotBase64: String? = null,
    val screenshotPath: String? = null,
    val screenshotPaths: List<String> = emptyList(),
    val events: List<ProcessingEvent> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    val allScreenshotPaths: List<String>
        get() = screenshotPaths.ifEmpty {
            screenshotPath?.let { listOf(it) } ?: emptyList()
        }
}

@Serializable
data class ProcessingEvent(
    val title: String,
    val detail: String = "",
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Serializable
enum class ProcessingStatus {
    RUNNING,
    SUCCESS,
    ERROR,
    TIMEOUT
}

@Serializable
data class AutomationActionRecord(
    val type: AutomationActionType,
    val text: String,
    val extra: String = ""
)

@Serializable
enum class AutomationActionType {
    SET_CLIPBOARD,
    SHOW_BUBBLE_LETTERS,
    AUTO_FILL_INPUT,
    AUTO_CLICK_BUTTON,
    VOICE_READ
}

fun defaultProvider(): ModelProviderConfig = ModelProviderConfig()

/** 新增提供方时的预设入口。 */
enum class ProviderPreset(val displayName: String, val description: String) {
    CUSTOM("自定义提供方", "手动填写 Base URL、API Key 与模型名称"),
    DEEPSEEK("DeepSeek", "复用 OpenAI 兼容协议，默认 https://api.deepseek.com/v1；点击添加后即可在模型选择中使用"),
    ANTHROPIC_MESSAGES("Anthropic Messages", "Anthropic Messages 兼容协议，适用于中转站")
}

/** DeepSeek 官方 OpenAI 兼容 Base URL（官方文档：https://api.deepseek.com，/v1 亦可）。 */
const val DEEPSEEK_DEFAULT_BASE_URL = "https://api.deepseek.com/v1"

/** DeepSeek 官方模型名称（官方文档当前主推模型）。 */
const val DEEPSEEK_DEFAULT_MODEL = "deepseek-flash"

fun ProviderPreset.createProvider(index: Int): ModelProviderConfig = when (this) {
    ProviderPreset.CUSTOM -> ModelProviderConfig(name = "自定义提供方 $index")
    ProviderPreset.DEEPSEEK -> ModelProviderConfig(
        name = "DeepSeek",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = DEEPSEEK_DEFAULT_BASE_URL,
        chatModel = DEEPSEEK_DEFAULT_MODEL,
        visionModel = DEEPSEEK_DEFAULT_MODEL,
        ocrModel = DEEPSEEK_DEFAULT_MODEL
    )
    ProviderPreset.ANTHROPIC_MESSAGES -> ModelProviderConfig(
        name = "Anthropic Messages",
        kind = ProviderKind.ANTHROPIC_MESSAGES,
        baseUrl = "https://api.anthropic.com/v1",
        chatModel = "claude-opus-4-6",
        visionModel = "claude-opus-4-6",
        ocrModel = "claude-opus-4-6"
    )
}

fun defaultAssistants(): List<AssistantPreset> = listOf(
    defaultAssistant(),
    AssistantPreset(
        name = "题目解答助手",
        ocrPrompt = "请准确提取图片中的题目、选项、公式和注释，尽量保持原有结构，不要解释。",
        textPrompt = "你是题目解答助手。请先识别题目内容，再给出解题思路、关键知识点和答案。",
        visionPrompt = "你是题目解答助手。请直接阅读图片中的题目内容，给出解题思路、关键知识点和答案。"
    )
)

fun defaultAssistant(): AssistantPreset = AssistantPreset(
    name = "聊天记录总结助手",
    ocrPrompt = "请准确提取图片中的全部文字，按原有结构输出，不要解释。",
    textPrompt = "你是聊天记录总结助手。请提炼重点、待办、情绪倾向，并用简洁中文输出。",
    visionPrompt = "你是聊天记录总结助手。请直接阅读图片内容，提炼重点、待办、情绪倾向，并用简洁中文输出。"
)

fun AssistantPreset.previewPrompt(): String {
    return textPrompt.ifBlank {
        visionPrompt.ifBlank {
            ocrPrompt
        }
    }
}

val ModelPurpose.displayName: String
    get() = when (this) {
        ModelPurpose.OCR -> "OCR"
        ModelPurpose.TEXT -> "文本"
        ModelPurpose.VISION -> "多模态"
    }

val ScreenCaptureMethod.displayName: String
    get() = when (this) {
        ScreenCaptureMethod.MEDIA_PROJECTION -> "系统屏幕录制"
        ScreenCaptureMethod.SHIZUKU_ADB -> "Shizuku + adb 截屏"
    }

val ScreenCaptureMethod.description: String
    get() = when (this) {
        ScreenCaptureMethod.MEDIA_PROJECTION -> "通过系统屏幕录制权限建立截图会话，兼容当前悬浮球流程。"
        ScreenCaptureMethod.SHIZUKU_ADB -> "通过 Shizuku 授权后调用 adb 截屏，避免每次启动都请求屏幕录制。"
    }

fun AppSettings.modelSelectionFor(purpose: ModelPurpose): ModelSelection = when (purpose) {
    ModelPurpose.OCR -> ocrModelSelection
    ModelPurpose.TEXT -> textModelSelection
    ModelPurpose.VISION -> visionModelSelection
}

fun AppSettings.resolveModelProvider(purpose: ModelPurpose): ModelProviderConfig? {
    val selection = modelSelectionFor(purpose)
    if (purpose == ModelPurpose.OCR && selection.isLocalOcrSelection()) return null
    return providers.firstOrNull { it.id == selection.providerId }
}

fun AppSettings.resolveModelName(purpose: ModelPurpose): String {
    val selection = modelSelectionFor(purpose)
    return if (purpose == ModelPurpose.OCR && selection.isLocalOcrSelection()) {
        localOcr.displayName
    } else {
        selection.model
    }
}

fun AppSettings.normalize(): AppSettings {
    val fallbackProvider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.firstOrNull()
    return copy(
        textModelSelection = textModelSelection.normalize(
            providers = providers,
            fallbackProvider = fallbackProvider,
            fallbackModel = fallbackProvider?.chatModel.orEmpty()
        ),
        visionModelSelection = visionModelSelection.normalize(
            providers = providers,
            fallbackProvider = fallbackProvider,
            fallbackModel = fallbackProvider?.visionModel.orEmpty()
        ),
        ocrModelSelection = ocrModelSelection.normalize(
            providers = providers,
            fallbackProvider = fallbackProvider,
            fallbackModel = fallbackProvider?.ocrModel?.ifBlank {
                fallbackProvider.visionModel
            } ?: fallbackProvider?.visionModel.orEmpty()
        )
    )
}

private fun ModelSelection.normalize(
    providers: List<ModelProviderConfig>,
    fallbackProvider: ModelProviderConfig?,
    fallbackModel: String
): ModelSelection {
    if (isLocalOcrSelection()) {
        return copy(
            providerId = LOCAL_OCR_PROVIDER_ID,
            model = model.ifBlank { LOCAL_OCR_MODEL_ID }
        )
    }
    val currentProvider = providers.firstOrNull { it.id == providerId }
    return when {
        currentProvider != null && model.isNotBlank() -> this
        currentProvider != null -> copy(model = fallbackModelFrom(currentProvider, fallbackModel))
        fallbackProvider != null -> ModelSelection(
            providerId = fallbackProvider.id,
            model = model.ifBlank { fallbackModel }
        )
        else -> this
    }
}

private fun fallbackModelFrom(provider: ModelProviderConfig, fallbackModel: String): String {
    return fallbackModel.ifBlank { provider.chatModel.ifBlank { provider.visionModel.ifBlank { provider.ocrModel } } }
}

fun ModelSelection.isLocalOcrSelection(): Boolean = providerId == LOCAL_OCR_PROVIDER_ID
