package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

internal class UnifiedLLMClient(
    private val clientProvider: NetworkClientProvider = NetworkClientProvider(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val tag = "HanakoUnifiedLLM"

    suspend fun stream(
        provider: ModelProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imagesBase64: List<String> = emptyList(),
        tools: List<ToolDef>? = null,
        webSearch: Boolean = false,
        thinking: Boolean = false,
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean = false,
        traceId: String? = null,
        recentHistory: List<ChatHistoryMessage> = emptyList()
    ): Flow<LlmEvent> {
        AppDebugLogStore.i(tag, "stream provider=${provider.kind} model=$model imageCount=${imagesBase64.size} hasTools=${tools != null} webSearch=$webSearch thinking=$thinking trustAllHttps=$trustAllHttpsCertificates trace=${traceId ?: "-"} history=${recentHistory.size}")
        // 每个请求独立一份诊断采集器：适配器回填 LlmEvent 无法表达的信号（推理增量），此处汇总。
        val diagnostics = StreamDiagnostics()
        val sseClient = SseStreamClient(clientProvider.client(trustAllHttpsCertificates))
        val adapter = when (provider.kind) {
            ProviderKind.OPENAI_COMPATIBLE -> OpenAiChatAdapter(sseClient, json)
            ProviderKind.OPENAI_RESPONSES -> OpenAiResponsesAdapter(sseClient, json)
            ProviderKind.ANTHROPIC -> AnthropicAdapter(sseClient, json)
            ProviderKind.ANTHROPIC_MESSAGES -> AnthropicAdapter(sseClient, json)
            ProviderKind.GOOGLE -> GoogleAdapter(sseClient, json)
        }
        val source = adapter.stream(
            StreamRequest(
                provider = provider,
                model = model,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                imagesBase64 = imagesBase64,
                tools = tools,
                webSearch = webSearch,
                thinking = thinking,
                traceId = traceId,
                recentHistory = recentHistory,
                diagnostics = diagnostics,
                firstDeltaTimeoutMillis = firstDeltaTimeoutMillis
            )
        )
        return streamWithDiagnostics(source, traceId, diagnostics)
    }

    /**
     * 给适配器返回的事件流包一层耗时诊断（不改变任何 [LlmEvent] 语义、不吞掉底层异常）：
     * 记录首个事件、首个推理(reasoning)增量、首个正式正文增量、总耗时、reasoning 增量个数与正文字符数，
     * 并在流结束时以单条日志输出。
     *
     * 同时把「仅有推理内容、始终没有最终正文」判定为可诊断的专用错误
     * （[ReasoningOnlyStreamException]），既不让推理内容伪装成答案，也不统一降级成首字延迟超时。
     *
     * 说明：诊断状态必须「每次收集各一份」。这里用 [flow] 构建器（状态是构建器内的局部变量），
     * 并在进入上游 `collect` 前显式捕获下游 [FlowCollector]，
     * 避免 lambda 接收者遮蔽导致把事件误发回上游。
     */
    private fun streamWithDiagnostics(
        source: Flow<LlmEvent>,
        traceId: String?,
        diagnostics: StreamDiagnostics
    ): Flow<LlmEvent> = flow {
        val downstream: FlowCollector<LlmEvent> = this
        val startNanos = System.nanoTime()
        var firstLlmEventNanos: Long? = null
        var firstContentNanos: Long? = null
        var contentChars = 0
        var toolCallCount = 0
        try {
            source.collect { event ->
                val now = System.nanoTime()
                if (firstLlmEventNanos == null) firstLlmEventNanos = now
                when (event) {
                    is LlmEvent.TextDelta -> {
                        if (firstContentNanos == null) firstContentNanos = now
                        contentChars += event.text.length
                    }

                    is LlmEvent.ToolCall -> toolCallCount += 1
                    is LlmEvent.Done -> Unit
                }
                downstream.emit(event)
            }
            if (contentChars == 0 && diagnostics.reasoningChunks > 0 && toolCallCount == 0) {
                throw ReasoningOnlyStreamException(
                    "模型仅返回推理内容、未返回最终正文（trace=${traceId ?: "-"}）"
                )
            }
        } finally {
            val endNanos = System.nanoTime()
            AppDebugLogStore.i(
                tag,
                streamTimingSummary(
                    traceId = traceId,
                    startNanos = startNanos,
                    endNanos = endNanos,
                    // 「首个 SSE 事件」优先取服务端真实事件时刻，其次退回首个 LlmEvent 时刻。
                    firstEventNanos = diagnostics.firstEventNanos ?: firstLlmEventNanos,
                    firstReasoningNanos = diagnostics.firstReasoningNanos,
                    firstContentNanos = firstContentNanos,
                    reasoningChunks = diagnostics.reasoningChunks,
                    contentChars = contentChars
                )
            )
        }
    }
}
