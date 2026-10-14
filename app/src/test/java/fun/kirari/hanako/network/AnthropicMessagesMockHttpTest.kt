package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic Messages 提供商（ANTHROPIC_MESSAGES）的 Mock HTTP 测试。
 *
 * 通过 JDK 内置 HttpServer 真实走一遍 OkHttp + SSE，覆盖：
 * 请求 URL/方法/认证头/请求体、SSE 文本增量、正常结束、错误事件、非 2xx、
 * 未知事件容错、取消请求、以及模型列表失败的可诊断性。
 */
class AnthropicMessagesMockHttpTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun provider(baseUrl: String, apiKey: String = "test-key") = ModelProviderConfig(
        name = "Anthropic Messages",
        kind = ProviderKind.ANTHROPIC_MESSAGES,
        baseUrl = baseUrl,
        apiKey = apiKey,
        chatModel = "claude-opus-4-6",
        visionModel = "claude-opus-4-6",
        ocrModel = "claude-opus-4-6"
    )

    private fun newClient() = UnifiedLLMClient()

    private fun collect(
        client: UnifiedLLMClient,
        provider: ModelProviderConfig,
        model: String = "claude-opus-4-6"
    ): List<LlmEvent> = runBlocking {
        val events = mutableListOf<LlmEvent>()
        client.stream(
            provider = provider,
            model = model,
            systemPrompt = "sys",
            userPrompt = "user",
            firstDeltaTimeoutMillis = 8_000L
        ).collect { events += it }
        events
    }

    @Test
    fun request_uses_correct_url_method_headers_and_body() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(
                anthropicTextDelta("ok"),
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
            )
        }
        server.start()
        try {
            val events = collect(newClient(), provider(server.baseUrl))

            val request = server.requests.first()
            assertEquals("POST", request.method)
            assertEquals("/v1/messages", request.path)
            assertEquals("test-key", request.header("x-api-key"))
            assertEquals("Bearer test-key", request.header("Authorization"))
            assertEquals(ANTHROPIC_VERSION, request.header("anthropic-version"))
            assertTrue(request.header("Content-Type").orEmpty().startsWith("application/json"))

            val body = json.parseToJsonElement(request.body).jsonObject
            assertEquals("claude-opus-4-6", body["model"]?.jsonPrimitive?.content)
            assertEquals(true, body["stream"]?.jsonPrimitive?.booleanOrNull)
            assertNotNull(body["max_tokens"]?.jsonPrimitive?.intOrNull)
            assertNotNull(body["messages"])

            assertTrue(events.any { it is LlmEvent.TextDelta })
            assertTrue(events.last() is LlmEvent.Done)
        } finally {
            server.stop()
        }
    }

    @Test
    fun sse_text_deltas_are_streamed_in_order_and_finish_with_message_stop() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(
                anthropicTextDelta("Hel"),
                anthropicTextDelta("lo"),
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
            )
        }
        server.start()
        try {
            val events = collect(newClient(), provider(server.baseUrl))
            val text = events.filterIsInstance<LlmEvent.TextDelta>().map { it.text }
            assertEquals(listOf("Hel", "lo"), text)
            assertTrue(events.last() is LlmEvent.Done)
        } finally {
            server.stop()
        }
    }

    @Test
    fun stream_completes_when_server_closes_without_message_stop() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(anthropicTextDelta("done-by-close"))
        }
        server.start()
        try {
            val events = collect(newClient(), provider(server.baseUrl))
            assertEquals(listOf("done-by-close"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
            assertTrue(events.last() is LlmEvent.Done)
        } finally {
            server.stop()
        }
    }

    @Test
    fun relay_style_done_marker_completes_stream() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(anthropicTextDelta("relay"), "data: [DONE]\n\n")
        }
        server.start()
        try {
            val events = collect(newClient(), provider(server.baseUrl))
            assertEquals(listOf("relay"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
            assertTrue(events.last() is LlmEvent.Done)
        } finally {
            server.stop()
        }
    }

    @Test
    fun error_event_surfaces_as_exception() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse("event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"rate limited\"}}\n\n")
        }
        server.start()
        try {
            var message: String? = null
            try {
                collect(newClient(), provider(server.baseUrl))
            } catch (t: Throwable) {
                message = t.message
            }
            assertTrue("expected error to surface, got: $message", message?.contains("rate limited") == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun non_2xx_response_is_not_treated_as_success() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondJson(401, "{\"error\":{\"message\":\"invalid api key\"}}")
        }
        server.start()
        try {
            var thrown: Throwable? = null
            try {
                collect(newClient(), provider(server.baseUrl))
            } catch (t: Throwable) {
                thrown = t
            }
            assertNotNull("non-2xx should throw", thrown)
        } finally {
            server.stop()
        }
    }

    @Test
    fun unknown_events_are_ignored_without_crash() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(
                "event: ping\ndata: {\"type\":\"ping\"}\n\n",
                "event: message_start\ndata: {\"type\":\"message_start\"}\n\n",
                "event: totally_unknown\ndata: not-json\n\n",
                anthropicTextDelta("ok"),
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
            )
        }
        server.start()
        try {
            val events = collect(newClient(), provider(server.baseUrl))
            assertEquals(listOf("ok"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
            assertTrue(events.last() is LlmEvent.Done)
        } finally {
            server.stop()
        }
    }

    @Test
    fun cancel_after_first_delta_completes_promptly() {
        val server = MockHttpServer { _, exchange ->
            val out = exchange.beginSse()
            out.write(anthropicTextDelta("first").toByteArray(Charsets.UTF_8))
            out.flush()
            // 保持连接打开，模拟服务端持续流式输出；客户端取消后应立即结束。
            Thread.sleep(3_000)
        }
        server.start()
        try {
            val client = newClient()
            val firstEvent = runBlocking {
                withTimeout(5_000) {
                    client.stream(
                        provider = provider(server.baseUrl),
                        model = "claude-opus-4-6",
                        systemPrompt = "sys",
                        userPrompt = "user",
                        firstDeltaTimeoutMillis = 8_000L
                    ).first { it is LlmEvent.TextDelta }
                }
            }
            assertEquals("first", (firstEvent as LlmEvent.TextDelta).text)
        } finally {
            server.stop()
        }
    }

    @Test
    fun base_url_with_trailing_slash_and_v1_prefix_produce_correct_path() {
        listOf("", "/", "/v1", "/v1/", "/anthropic").forEach { suffix ->
            val server = MockHttpServer { _, exchange ->
                exchange.respondSse("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
            }
            server.start()
            try {
                collect(newClient(), provider(server.baseUrl + suffix))
                val path = server.requests.first().path
                val doubledV1 = path.contains("/v1/v1")
                val doubledMessages = path.contains("/messages/messages")
                assertTrue("unexpected path for suffix '$suffix': $path", path.endsWith("/messages"))
                assertTrue("doubled /v1 for suffix '$suffix': $path", !doubledV1)
                assertTrue("doubled /messages for suffix '$suffix': $path", !doubledMessages)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun models_list_failure_is_diagnosable() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondJson(404, "{\"error\":{\"message\":\"no such endpoint\"}}")
        }
        server.start()
        try {
            var message: String? = null
            try {
                runBlocking { ProviderModelsApi().listModels(provider(server.baseUrl)) }
            } catch (t: Throwable) {
                message = t.message
            }
            assertTrue("expected HTTP status in error message, got: $message", message?.contains("404") == true)
        } finally {
            server.stop()
        }
    }

    // ---------- P1-01：模型列表路径必须与聊天路径同规则 ----------

    @Test
    fun models_list_uses_versioned_path_consistent_with_chat() {
        listOf("", "/v1", "/v1/messages").forEach { suffix ->
            val server = MockHttpServer { _, exchange ->
                exchange.respondJson(200, "{\"data\":[{\"id\":\"claude-opus-4-6\"}]}")
            }
            server.start()
            try {
                val models = runBlocking { ProviderModelsApi().listModels(provider(server.baseUrl + suffix)) }
                val request = server.requests.first()
                assertEquals("GET", request.method)
                assertEquals("unexpected models path for suffix '$suffix'", "/v1/models", request.path)
                assertEquals("test-key", request.header("x-api-key"))
                assertEquals(listOf("claude-opus-4-6"), models.map { it.id })
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun base_url_with_trailing_slash_after_messages_produces_single_messages_path() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(anthropicMessageStop())
        }
        server.start()
        try {
            collect(newClient(), provider(server.baseUrl + "/v1/messages/"))
            assertEquals("/v1/messages", server.requests.first().path)
        } finally {
            server.stop()
        }
    }

    // ---------- P1-03：工具调用 SSE ----------

    @Test
    fun tool_use_events_produce_tool_call_with_id_name_and_accumulated_arguments() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(
                anthropicToolUseStart(id = "toolu_123", name = "set_clipboard"),
                anthropicInputJsonDelta("{\"text\":\"he"),
                anthropicInputJsonDelta("llo\"}"),
                anthropicMessageStop()
            )
        }
        server.start()
        try {
            val events = collect(newClient(), provider(server.baseUrl))
            val toolCalls = events.filterIsInstance<LlmEvent.ToolCall>()
            assertEquals("exactly one tool call expected", 1, toolCalls.size)
            val toolCall = toolCalls.single()
            assertEquals("set_clipboard", toolCall.name)
            assertEquals("toolu_123", toolCall.id)
            assertEquals("hello", toolCall.arguments["text"]?.jsonPrimitive?.content)
            assertTrue(events.last() is LlmEvent.Done)
        } finally {
            server.stop()
        }
    }

    // ---------- P1-02：错误事件识别（event 字段可选 + 安全提取） ----------

    @Test
    fun error_payload_without_event_field_is_surfaced() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(
                anthropicErrorEvent("{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}", withEventField = false)
            )
        }
        server.start()
        try {
            var message: String? = null
            try {
                collect(newClient(), provider(server.baseUrl))
            } catch (t: Throwable) {
                message = t.message
            }
            assertTrue("payload-only error must surface, got: $message", message?.contains("overloaded") == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun error_payload_without_message_is_still_diagnosable() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(anthropicErrorEvent("{}", withEventField = false))
        }
        server.start()
        try {
            var thrown: Throwable? = null
            try {
                collect(newClient(), provider(server.baseUrl))
            } catch (t: Throwable) {
                thrown = t
            }
            assertNotNull("payload-only error without message must still throw", thrown)
            assertTrue("error message must not be blank", !thrown?.message.isNullOrBlank())
        } finally {
            server.stop()
        }
    }

    @Test
    fun error_payload_with_plain_string_error_is_surfaced() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(anthropicErrorEvent("\"boom\"", withEventField = false))
        }
        server.start()
        try {
            var message: String? = null
            try {
                collect(newClient(), provider(server.baseUrl))
            } catch (t: Throwable) {
                message = t.message
            }
            assertTrue("string error must surface, got: $message", message?.contains("boom") == true)
        } finally {
            server.stop()
        }
    }
}