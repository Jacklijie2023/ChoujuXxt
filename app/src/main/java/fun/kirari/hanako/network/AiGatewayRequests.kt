package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Anthropic Messages 协议版本请求头取值。 */
internal const val ANTHROPIC_VERSION = "2023-06-01"

internal fun baseRequest(
    provider: ModelProviderConfig,
    url: String,
    payload: JsonObject,
    json: Json,
    mediaType: MediaType,
    headers: Map<String, String> = emptyMap(),
    googleApiKeyHeader: Boolean = false
): Request {
    val base = provider.baseUrl.trimEnd('/')
    require(base.isNotBlank()) { "请先填写 Base URL" }
    require(provider.apiKey.isNotBlank()) { "请先填写 API Key" }
    val builder = Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaType))

    when (provider.kind) {
        ProviderKind.GOOGLE -> {
            builder.header("x-goog-api-key", provider.apiKey)
        }

        ProviderKind.ANTHROPIC -> {
            builder.header("x-api-key", provider.apiKey)
        }

        ProviderKind.ANTHROPIC_MESSAGES -> {
            // Anthropic Messages 兼容中转站的认证方式不统一：
            // 官方与部分服务商使用 x-api-key，多数中转站使用 Authorization: Bearer。
            // 同时发送两者可覆盖两类实现；两个头部互相独立，不会互相干扰。
            builder.header("x-api-key", provider.apiKey)
            builder.header("Authorization", "Bearer ${provider.apiKey}")
        }

        else -> {
            builder.header("Authorization", "Bearer ${provider.apiKey}")
        }
    }

    headers.forEach { (key, value) ->
        builder.header(key, value)
    }
    return builder.build()
}

internal fun AiGateway.baseRequest(
    provider: ModelProviderConfig,
    url: String,
    payload: JsonObject,
    headers: Map<String, String> = emptyMap(),
    googleApiKeyHeader: Boolean = false
): Request = baseRequest(provider, url, payload, json, JSON, headers, googleApiKeyHeader)
