package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType

internal class OpenAiResponsesAdapter(
    private val sseClient: SseStreamClient,
    private val json: Json
) : ProviderAdapter {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun stream(request: StreamRequest): Flow<LlmEvent> = callbackFlow {
        val payload = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("instructions", request.systemPrompt)
            request.tools?.let {
                put("tool_choice", "required")
                put("tools", ToolRegistry.formatForProvider(it, request.provider.kind))
            }
            // 联网搜索：OpenAI Responses 官方内建工具 web_search_preview，仅在无自定义工具时注入。
            // 第三方中转站的兼容性未验证，开启后若无效果或报错需向用户如实说明。
            if (request.webSearch && request.tools == null) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "web_search_preview")
                    })
                })
            }
            // 思考模式：OpenAI Responses 官方字段 reasoning.effort（仅对支持推理的模型生效）。
            // 第三方中转站的兼容性未验证，开启后若无效果或报错需向用户如实说明。
            if (request.thinking) {
                put("reasoning", buildJsonObject {
                    put("effort", "high")
                })
            }
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", request.userPrompt)
                        })
                        request.imagesBase64.forEach { imageBase64 ->
                            add(buildJsonObject {
                                put("type", "input_image")
                                put("image_url", "data:image/jpeg;base64,$imageBase64")
                            })
                        }
                    })
                })
            })
        }

        val toolCalls = linkedMapOf<String, PendingToolCall>()

        sseClient.stream(
            request = baseRequest(request.provider, "${request.provider.baseUrl.trimEnd('/')}/responses", payload, json, mediaType),
            traceId = request.traceId,
            firstDeltaTimeoutMillis = request.firstDeltaTimeoutMillis,
            onEvent = { _, type, _, data ->
                if (data == "[DONE]") return@stream SseStreamClient.StreamEventResult(done = true)
                val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                    ?: return@stream null
                when (type) {
                    "response.output_text.delta" -> {
                        val delta = root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (delta.isNotBlank()) {
                            trySend(LlmEvent.TextDelta(delta))
                        }
                        SseStreamClient.StreamEventResult(delta = delta)
                    }

                    "response.function_call_arguments.delta" -> {
                        val itemId = root["item_id"]?.jsonPrimitive?.contentOrNull ?: return@stream null
                        val tc = toolCalls.getOrPut(itemId) { PendingToolCall() }
                        tc.arguments.append(root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        null
                    }

                    "response.output_item.added" -> {
                        val item = root["item"]?.jsonObject ?: return@stream null
                        if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                            val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: return@stream null
                            val tc = toolCalls.getOrPut(itemId) { PendingToolCall() }
                            tc.name = item["name"]?.jsonPrimitive?.contentOrNull
                            item["arguments"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                                tc.arguments.clear()
                                tc.arguments.append(it)
                            }
                        }
                        null
                    }

                    "response.function_call_arguments.done" -> {
                        val itemId = root["item_id"]?.jsonPrimitive?.contentOrNull ?: return@stream null
                        val tc = toolCalls.getOrPut(itemId) { PendingToolCall() }
                        root["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                            tc.name = it
                        }
                        root["arguments"]?.jsonPrimitive?.contentOrNull?.let {
                            tc.arguments.clear()
                            tc.arguments.append(it)
                        }
                        null
                    }

                    else -> null
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
