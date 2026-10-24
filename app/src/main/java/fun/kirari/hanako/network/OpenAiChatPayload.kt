package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ChatRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/**
 * 组装 OpenAI 兼容 Chat Completions 请求体。
 *
 * messages 顺序契约（由单元测试严格规定）：
 * - 默认（新题 / 普通聊天）请求体**只有 system + 当前用户消息**两条，绝不携带任何历史 assistant 消息；
 * - 仅当 [StreamRequest.recentHistory] 被显式填充（同题追问）时，
 *   历史消息才插入 system 与当前用户消息**之间**（system → history → 当前 user）。
 */
internal fun buildOpenAiChatPayload(request: StreamRequest): JsonObject = buildJsonObject {
    put("model", request.model)
    put("stream", true)
    put("messages", buildJsonArray {
        add(openAiMessage("system", request.systemPrompt))
        request.recentHistory.forEach { history ->
            add(openAiMessage(history.role.toOpenAiRole(), history.content))
        }
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
    // DeepSeek 官方 Chat Completions 的思考模式默认开启：非思考模式必须显式发送
    // thinking={"type":"disabled"}，否则会因缺省而继续走思考模式、拖慢最终正文。
    // 该参数为 DeepSeek 官方专属，绝不能无条件注入到其它 OpenAI 兼容中转站。
    if (isOfficialDeepSeekBaseUrl(request.provider.baseUrl) && !request.thinking) {
        put("thinking", buildJsonObject { put("type", "disabled") })
    }
}

/** 会话历史角色 → OpenAI 兼容协议 role 字段取值。 */
private fun ChatRole.toOpenAiRole(): String = when (this) {
    ChatRole.User -> "user"
    ChatRole.Assistant -> "assistant"
}

/**
 * 判断模型是否为 OpenAI o 系列推理模型（官方唯一支持 reasoning_effort 的模型线）。
 * 匹配 o1/o3/o4/o5 前缀；gpt-4o 等非推理模型不在其列。
 */
private fun isOpenAiReasoningModel(model: String): Boolean {
    val m = model.trim().lowercase()
    return m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") || m.startsWith("o5") || m == "o"
}

/**
 * 判定是否为 DeepSeek 官方端点。
 *
 * 按主机名精确匹配 `api.deepseek.com`，避免「同前缀伪装域名」（如 api.deepseek.com.evil.com）
 * 与任意 OpenAI 兼容中转站被误判而错误注入 DeepSeek 专属参数。
 */
internal fun isOfficialDeepSeekBaseUrl(rawBaseUrl: String?): Boolean {
    val base = rawBaseUrl?.trim().orEmpty()
    if (base.isEmpty()) return false
    val host = runCatching {
        val normalized = if ("://" in base) base else "https://$base"
        URI(normalized).host?.lowercase()
    }.getOrNull()
    return host == "api.deepseek.com"
}
