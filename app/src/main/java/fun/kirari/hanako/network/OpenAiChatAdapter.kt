package `fun`.kirari.hanako.network

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType

internal class OpenAiChatAdapter(
    private val sseClient: SseStreamClient,
    private val json: Json
) : ProviderAdapter {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun stream(request: StreamRequest): Flow<LlmEvent> = callbackFlow {
        val payload = buildOpenAiChatPayload(request)

        val toolCalls = mutableMapOf<Int, PendingToolCall>()

        sseClient.stream(
            request = baseRequest(request.provider, "${request.provider.baseUrl.trimEnd('/')}/chat/completions", payload, json, mediaType),
            traceId = request.traceId,
            firstDeltaTimeoutMillis = request.firstDeltaTimeoutMillis,
            onEvent = { _, _, _, data ->
                request.diagnostics.markEvent(System.nanoTime())
                if (data == "[DONE]") {
                    SseStreamClient.StreamEventResult(done = true)
                } else {
                    val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                        ?: return@stream null
                    val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
                        ?: return@stream null
                    val delta = choice["delta"]?.jsonObject ?: return@stream null

                    val textDelta = extractOpenAiContent(delta["content"])
                    // 推理增量（reasoning_content）：不得伪装成最终正文，既不产生 TextDelta，
                    // 也回填到诊断采集器（供耗时摘要与「仅推理、无正文」判定）；
                    // 同时计入「服务端已开始输出」，避免仅推理的流被首字定时器误判超时。
                    val reasoningDelta = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    if (textDelta.isNotBlank()) {
                        trySend(LlmEvent.TextDelta(textDelta))
                    }
                    if (reasoningDelta.isNotBlank()) {
                        request.diagnostics.markReasoning(System.nanoTime())
                    }

                    (delta["tool_calls"] as? JsonArray)?.forEach { item ->
                        val toolCall = item.jsonObject
                        val index = toolCall["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val function = toolCall["function"]?.jsonObject ?: return@forEach
                        val tc = toolCalls.getOrPut(index) { PendingToolCall() }
                        function["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                            tc.name = it
                        }
                        tc.arguments.append(function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }

                    SseStreamClient.StreamEventResult(
                        delta = textDelta.ifBlank { null },
                        received = textDelta.isNotBlank() || reasoningDelta.isNotBlank()
                    )
                }
            },
            onDelta = {}
        )

        for (tc in toolCalls.values) {
            val name = tc.name ?: continue
            val args = runCatching { json.parseToJsonElement(tc.arguments.toString()).jsonObject }.getOrNull()
                ?: continue
            trySend(LlmEvent.ToolCall(name, args))
        }
        trySend(LlmEvent.Done)
        close()

        awaitClose()
    }
}
