package `fun`.kirari.hanako

import android.content.Context
import `fun`.kirari.hanako.chat.ChatConfig
import `fun`.kirari.hanako.chat.ChatModelCatalog
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.chat.UnifiedChatStreamSource
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.ChatSessionStore
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.data.SettingsStore
import `fun`.kirari.hanako.data.resolveModelName
import `fun`.kirari.hanako.data.resolveModelProvider
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.network.NetworkClientProvider
import `fun`.kirari.hanako.network.ProviderModelsApi
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.overlay.TtsHelper
import `fun`.kirari.hanako.util.HapticHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

internal class AppContainer(appContext: Context) {
    val networkClientProvider = NetworkClientProvider()
    val settingsStore = SettingsStore(appContext)
    val unifiedLLMClient = UnifiedLLMClient(clientProvider = networkClientProvider)
    val localOcrManager = LocalOcrManager(appContext)
    val settingsRepository = SettingsRepository(settingsStore)

    /** 应用级协程作用域：承载聊天会话流式任务与设置快照订阅。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前设置的只读快照，供无法直接订阅 Compose 状态的组件（如聊天会话）读取。 */
    private val appSettings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    /** 模型列表拉取（复用既有 OpenAI/Anthropic/Google 适配与认证头规则）。 */
    val providerModelsApi = ProviderModelsApi(networkClientProvider)

    /**
     * 聊天模型全量列表内存缓存（聊天页与迷你窗共用）。
     *
     * 按 providerId 键控，含 loading / error 态；拉取失败由 UI 回退展示该 API 已配置的 chatModel。
     */
    val chatModelCatalog = ChatModelCatalog(
        api = providerModelsApi,
        scope = appScope,
        trustAllHttpsCertificatesProvider = { appSettings.value.trustAllHttpsCertificates }
    )

    /**
     * 全局单例聊天会话持有者。
     *
     * 通过 `AppContainer` 注入，聊天页面与（P3）迷你聊天共享同一实例，
     * 会话状态不局限于任何单个 Composable 的局部 `remember`。
     */
    val chatSessionHolder = ChatSessionHolder(
        streamSource = UnifiedChatStreamSource(unifiedLLMClient),
        configProvider = {
            val settings = appSettings.value
            ChatConfig(
                provider = settings.resolveModelProvider(ModelPurpose.TEXT),
                model = settings.resolveModelName(ModelPurpose.TEXT),
                systemPrompt = CHAT_SYSTEM_PROMPT,
                trustAllHttpsCertificates = settings.trustAllHttpsCertificates,
                providers = settings.providers
            )
        },
        scope = appScope,
        persistence = ChatSessionStore(appContext)
    )

    init {
        // 初始化 TTS
        TtsHelper.init(appContext)
        // 初始化触觉反馈
        HapticHelper.init(appContext)
    }

    private companion object {
        /** 聊天默认系统提示词（无产品化要求时的中性缺省值）。 */
        const val CHAT_SYSTEM_PROMPT = "你是 Hanako 内置助手，请用简洁、准确的中文回答用户的问题。"
    }
}