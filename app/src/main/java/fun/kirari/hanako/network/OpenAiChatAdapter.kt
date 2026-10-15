package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType

internal class OpenAiChatAdapter(
    private val sseClient: SseStreamClient,
    private val json: Json
) : ProviderAdapter {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun stream(request: StreamRequest): Flow<LlmEvent> = callbackFlow {
        val payload = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("messages", buildJsonArray {
                add(openAiMessage("system", request.systemPrompt))
                add(buildJsonObject {
                    put("role", "user")
                    if (!request.hasImages) {
                        put("content", request.userPrompt)
                    } else {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", request.userPrompt)
                            })
                            request.imagesBase64.forEach { imageBase64 ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", "data:image/jpeg;base64,$imageBase64")
                                        put("detail", "high")
                                    })
                                })
                            }
                        })
                    }
                })
            })
            request.tools?.let { put("tools", ToolRegistry.formatForProvider(it, request.provider.kind)) }
            // 思考模式：OpenAI 官方仅 o 系列推理模型支持 reasoning_effort。
            // 对非 o 系列模型（如 gpt-4o）注入该字段可能被部分中转站以 403 拒绝，
            // 故仅在模型名为 o 系列（o1/o3/o4/o5）时注入；其余情形跳过该字段。
            if (request.thinking && isOpenAiReasoningModel(request.model)) {
                put("reasoning_effort", "high")
            }
        }

        val toolCalls = mutableMapOf<Int, PendingToolCall>()

        sseClient.stream(
            request = baseRequest(request.provider, "${request.provider.baseUrl.trimEnd('/')}/chat/completions", payload, json, mediaType),
            firstDeltaTimeoutMillis = request.firstDeltaTimeoutMillis,
            onEvent = { _, _, _, data ->
                if (data == "[DONE]") {
                    SseStreamClient.StreamEventResult(done = true)
                } else {
                    val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                        ?: return@stream null
                    val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
                        ?: return@stream null
                    val delta = choice["delta"]?.jsonObject ?: return@stream null

                    val textDelta = extractOpenAiContent(delta["content"])
                    if (textDelta.isNotBlank()) {
                        trySend(LlmEvent.TextDelta(textDelta))
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

                    SseStreamClient.StreamEventResult(delta = textDelta.ifBlank { null })
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

/**
 * 判断模型是否为 OpenAI o 系列推理模型（官方唯一支持 reasoning_effort 的模型线）。
 * 匹配 o1/o3/o4/o5 前缀；gpt-4o 等非推理模型不在其列。
 */
private fun isOpenAiReasoningModel(model: String): Boolean {
    val m = model.trim().lowercase()
    return m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") || m.startsWith("o5") || m == "o"
}
