package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.resolvedRequestUrl
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType

internal class AnthropicAdapter(
    private val sseClient: SseStreamClient,
    private val json: Json
) : ProviderAdapter {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun stream(request: StreamRequest): Flow<LlmEvent> = callbackFlow {
        val payload = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("max_tokens", 4096)
            put("system", request.systemPrompt)
            request.tools?.let {
                put("tool_choice", buildJsonObject { put("type", "any") })
                put("tools", ToolRegistry.formatForProvider(it, request.provider.kind))
            }
            // 联网搜索：Anthropic 官方 server tool（web_search_20250305），仅在无自定义工具时注入。
            // 第三方中转站的兼容性未验证，开启后若无效果或报错需向用户如实说明。
            if (request.webSearch && request.tools == null) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "web_search_20250305")
                        put("name", "web_search")
                        put("max_uses", 25)
                    })
                })
            }
            // 思考模式：Anthropic 官方字段 thinking（budget_tokens 为推理预算，仅对支持思考的模型生效）。
            // 第三方中转站的兼容性未验证，开启后若无效果或报错需向用户如实说明。
            if (request.thinking) {
                put("thinking", buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", 4096)
                })
            }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", request.userPrompt)
                        })
                        request.imagesBase64.forEach { imageBase64 ->
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", imageBase64)
                                })
                            })
                        }
                    })
                })
            })
        }

        val toolCallsByIndex = linkedMapOf<Int, PendingToolCall>()

        sseClient.stream(
            request = baseRequest(
                request.provider,
                request.provider.resolvedRequestUrl(),
                payload,
                json,
                mediaType,
                headers = mapOf("anthropic-version" to ANTHROPIC_VERSION)
            ),
            firstDeltaTimeoutMillis = request.firstDeltaTimeoutMillis,
            onEvent = { _, type, _, data ->
                if (data == "[DONE]") return@stream SseStreamClient.StreamEventResult(done = true)
                val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                // 兼容两类错误上报：SSE 事件名 `event: error`（type == "error"），
                // 以及部分服务端仅通过载荷中的 `type: "error"` 表示错误、却缺少 event 字段的情况。
                // 二者在此统一处理，既避免静默忽略，也避免同一错误被重复上报。
                if (type == "error" || root?.get("type").stringOrNull() == "error") {
                    throw IllegalStateException(anthropicErrorMessage(root))
                }
                val payload = root ?: return@stream null
                when (type) {
                    "content_block_start" -> {
                        val index = payload["index"]?.jsonPrimitive?.intOrNull ?: return@stream null
                        val block = payload["content_block"]?.jsonObject ?: return@stream null
                        if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                            val tc = toolCallsByIndex.getOrPut(index) { PendingToolCall() }
                            tc.id = block["id"]?.jsonPrimitive?.contentOrNull ?: tc.id
                            tc.name = block["name"]?.jsonPrimitive?.contentOrNull ?: tc.name
                            block["input"]?.jsonObject?.takeIf { it.isNotEmpty() }?.let {
                                tc.arguments.clear()
                                tc.arguments.append(json.encodeToString(JsonObject.serializer(), it))
                            }
                        }
                        null
                    }

                    "content_block_delta" -> {
                        val index = payload["index"]?.jsonPrimitive?.intOrNull ?: return@stream null
                        val delta = payload["delta"]?.jsonObject ?: return@stream null
                        when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                            "text_delta" -> {
                                val text = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                if (text.isNotBlank()) {
                                    trySend(LlmEvent.TextDelta(text))
                                }
                                SseStreamClient.StreamEventResult(delta = text)
                            }

                            "input_json_delta" -> {
                                val tc = toolCallsByIndex.getOrPut(index) { PendingToolCall() }
                                tc.arguments.append(delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty())
                                null
                            }

                            else -> null
                        }
                    }

                    "message_stop" -> SseStreamClient.StreamEventResult(done = true)

                    else -> null
                }
            },
            onDelta = {}
        )

        for (tc in toolCallsByIndex.values) {
            val name = tc.name ?: continue
            val args = runCatching { json.parseToJsonElement(tc.arguments.toString()).jsonObject }.getOrNull()
                ?: continue
            trySend(LlmEvent.ToolCall(name, args, tc.id))
        }
        trySend(LlmEvent.Done)
        close()

        awaitClose()
    }

    /** 安全读取 JsonElement 的字符串值；非字符串或 JSON null 返回 null，不抛异常。 */
    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    /**
     * 从错误事件载荷中安全提取可诊断信息。
     * 依次尝试 `error.message`、`error.type`、`error`（字符串）、`message`；
     * 字段缺失、类型异常或内容为空时返回兜底文案，确保错误不会被静默吞掉。
     */
    private fun anthropicErrorMessage(root: JsonObject?): String {
        if (root == null) {
            return "Anthropic Messages 流式响应返回错误事件（错误数据无法解析为 JSON）"
        }
        val errorElement = root["error"]
        val errorObject = errorElement as? JsonObject
        val message = errorObject?.get("message").stringOrNull()
            ?: errorObject?.get("type").stringOrNull()
            ?: errorElement.stringOrNull()
            ?: root["message"].stringOrNull()
        return message?.takeIf { it.isNotBlank() }
            ?: "Anthropic Messages 流式响应返回错误事件（未提供错误详情）"
    }
}
