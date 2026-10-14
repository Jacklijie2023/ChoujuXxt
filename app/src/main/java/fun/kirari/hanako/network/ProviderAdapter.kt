package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import kotlinx.coroutines.flow.Flow

internal data class StreamRequest(
    val provider: ModelProviderConfig,
    val model: String,
    val systemPrompt: String,
    val userPrompt: String,
    val imagesBase64: List<String> = emptyList(),
    val tools: List<ToolDef>? = null,
    /** 是否开启「联网搜索」（会话级开关）。真实能力是否生效取决于服务商支持，第三方中转站兼容性待验证。 */
    val webSearch: Boolean = false,
    /** 是否开启「思考模式」（会话级开关）。真实能力是否生效取决于服务商支持，第三方中转站兼容性待验证。 */
    val thinking: Boolean = false,
    val firstDeltaTimeoutMillis: Long
) {
    val hasImages: Boolean get() = imagesBase64.isNotEmpty()
    
    @Deprecated("Use imagesBase64 instead", replaceWith = ReplaceWith("imagesBase64"))
    val imageBase64: String? get() = imagesBase64.firstOrNull()
}

internal interface ProviderAdapter {
    suspend fun stream(request: StreamRequest): Flow<LlmEvent>
}

internal class PendingToolCall(
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder(),
    var id: String? = null
)
