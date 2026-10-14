package `fun`.kirari.hanako.chat

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.network.ProviderModelsApi
import `fun`.kirari.hanako.network.RemoteModelOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 单个提供方的模型列表拉取状态（内存缓存条目）。 */
data class ProviderModelListState(
    val models: List<RemoteModelOption> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val loaded: Boolean = false
)

/**
 * 聊天模型全量列表的内存缓存（按 providerId 键控）。
 *
 * 聊天页与迷你窗共用同一实例（由 [AppContainer] 注入）。每个「已添加 API」首次访问时
 * 经 [ProviderModelsApi] 拉取全部可用模型：成功进入缓存，失败标记 error（UI 回退展示该 API
 * 已配置的 chatModel 并可提示前往设置查看）。
 */
class ChatModelCatalog(
    private val api: ProviderModelsApi,
    private val scope: CoroutineScope,
    private val trustAllHttpsCertificatesProvider: () -> Boolean
) {
    private val _states = MutableStateFlow<Map<String, ProviderModelListState>>(emptyMap())
    val states: StateFlow<Map<String, ProviderModelListState>> = _states.asStateFlow()

    private val inflight = mutableSetOf<String>()

    /** 确保指定提供方的模型列表已加载（幂等：已加载或加载中直接返回）。 */
    fun ensureLoaded(provider: ModelProviderConfig) {
        val key = provider.id
        val current = _states.value[key]
        if (current?.loaded == true || current?.loading == true) return
        if (!inflight.add(key)) return
        _states.update { it + (key to ProviderModelListState(loading = true)) }
        scope.launch {
            val result = try {
                Result.success(api.listModels(provider, trustAllHttpsCertificatesProvider()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            inflight.remove(key)
            _states.update { states ->
                states + (key to if (result.isSuccess) {
                    ProviderModelListState(
                        models = result.getOrDefault(emptyList()),
                        loaded = true
                    )
                } else {
                    ProviderModelListState(models = emptyList(), error = true, loaded = true)
                })
            }
        }
    }

    /** 指定提供方的当前缓存状态；未访问过返回默认（未加载）。 */
    fun stateFor(providerId: String): ProviderModelListState =
        _states.value[providerId] ?: ProviderModelListState()

    /** 清空全部缓存（供测试/设置变更后重新拉取）。 */
    fun clear() {
        _states.value = emptyMap()
        inflight.clear()
    }
}
