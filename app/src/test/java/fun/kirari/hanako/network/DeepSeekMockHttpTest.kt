package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 提供商（复用 OpenAI 兼容适配器）的 Mock HTTP 测试。
 *
 * 覆盖：请求路径与认证头、模型名称透传、流式文本增量、以及错误可诊断性。
 */
class DeepSeekMockHttpTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun provider(baseUrl: String, model: String = "deepseek-flash") = ModelProviderConfig(
        name = "DeepSeek",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = baseUrl,
        apiKey = "ds-key",
        chatModel = model,
        visionModel = model,
        ocrModel = model
    )

    private fun collect(
        provider: ModelProviderConfig,
        model: String = "deepseek-flash"
    ): List<LlmEvent> = runBlocking {
        val events = mutableListOf<LlmEvent>()
        UnifiedLLMClient().stream(
            provider = provider,
            model = model,
            systemPrompt = "sys",
            userPrompt = "user",
            firstDeltaTimeoutMillis = 8_000L
        ).collect { events += it }
        events
    }

    @Test
    fun request_uses_chat_completions_path_with_bearer_auth_and_custom_model() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(openAiTextDelta("ok"), "data: [DONE]\n\n")
        }
        server.start()
        try {
            collect(provider(server.baseUrl), model = "deepseek-reasoner")

            val request = server.requests.first()
            assertEquals("POST", request.method)
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer ds-key", request.header("Authorization"))
            assertTrue(request.header("Content-Type").orEmpty().startsWith("application/json"))

            val body = json.parseToJsonElement(request.body).jsonObject
            assertEquals("deepseek-reasoner", body["model"]?.jsonPrimitive?.content)
            assertEquals(true, body["stream"]?.jsonPrimitive?.content?.toBoolean())
            assertNotNull(body["messages"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun streaming_text_deltas_are_aggregated_and_finish_with_done() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(
                openAiTextDelta("He"),
                openAiTextDelta("llo"),
                "data: [DONE]\n\n"
            )
        }
        server.start()
        try {
            val events = collect(provider(server.baseUrl))
            assertEquals(listOf("He", "llo"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
            assertTrue(events.last() is LlmEvent.Done)
        } finally {
            server.stop()
        }
    }

    @Test
    fun api_error_is_diagnosable() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondJson(400, "{\"error\":{\"message\":\"invalid model\"}}")
        }
        server.start()
        try {
            var thrown: Throwable? = null
            try {
                collect(provider(server.baseUrl))
            } catch (t: Throwable) {
                thrown = t
            }
            assertNotNull("4xx should throw", thrown)
        } finally {
            server.stop()
        }
    }
}