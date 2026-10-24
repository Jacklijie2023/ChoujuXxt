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
    /** 本次请求的追踪 ID（助手消息 id），用于把聊天链路与 SSE 阶段诊断日志关联起来。 */
    val traceId: String? = null,
    /** 同题追问时携带的最近必要历史消息；为空表示新题请求（绝不携带任何历史）。 */
    val recentHistory: List<ChatHistoryMessage> = emptyList(),
    /**
     * 流式诊断采集器：由适配器回填 [LlmEvent] 无法表达的信号（推理增量），
     * 供 [UnifiedLLMClient] 汇总耗时日志与「仅推理、无正文」判定。仅用于诊断。
     */
    val diagnostics: StreamDiagnostics = StreamDiagnostics(),
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
