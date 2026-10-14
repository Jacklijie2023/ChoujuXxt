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
            // 思考模式：OpenAI o 系列官方字段 reasoning_effort（仅对支持该字段的模型生效）。
            // 第三方中转站的兼容性未验证，开启后若无效果或报错需向用户如实说明。
            if (request.thinking) {
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
