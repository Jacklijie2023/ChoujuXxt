package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

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

    @Test
    fun first_delta_timeout_is_reported_when_server_sends_no_text_within_deadline() {
        // 服务端仅返回响应头、迟迟不下发任何 SSE 事件：应由「应用自身首字定时器」触发超时，
        // 且文案为「首字延迟超时」（这才是真实耗时对应的失败原因）。
        val server = MockHttpServer { _, exchange ->
            exchange.beginSse()
            Thread.sleep(2_000)
        }
        server.start()
        try {
            var thrown: Throwable? = null
            try {
                runBlocking {
                    UnifiedLLMClient().stream(
                        provider = provider(server.baseUrl),
                        model = "deepseek-flash",
                        systemPrompt = "sys",
                        userPrompt = "user",
                        firstDeltaTimeoutMillis = 800L
                    ).collect { }
                }
            } catch (t: Throwable) {
                thrown = t
            }
            assertNotNull("no-delta should time out", thrown)
            assertTrue("app first-delta timer -> SocketTimeoutException", thrown is SocketTimeoutException)
            assertTrue(
                "message should be first-delta timeout, got: ${thrown?.message}",
                thrown!!.message.orEmpty().contains("首字延迟超时")
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun http_error_is_preserved_and_not_masked_as_first_delta_timeout() {
        // HTTP 4xx 属于明确的协议层错误，必须保留真实失败（含状态码），
        // 不得统一伪装成「首字延迟超时」而掩盖真实原因。
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
            assertFalse(
                "4xx must not be normalized into first-delta timeout",
                thrown is SocketTimeoutException && thrown?.message.orEmpty().contains("首字延迟超时")
            )
            assertTrue(
                "real HTTP status should be preserved, got: ${thrown?.message}",
                thrown!!.message.orEmpty().contains("400")
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun reasoning_only_stream_reports_error_instead_of_faking_the_answer() {
        // 服务端只下发 reasoning_content、不下发 content：模型并未给出最终正文。
        // 1) 不得被应用自身首字定时器判成「首字延迟超时」（事件确实在到达）；
        // 2) 更不得把 reasoning_content 伪装成最终答案——必须返回可诊断的明确错误。
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(
                openAiReasoningDelta("思考中"),
                openAiReasoningDelta("……"),
                "data: [DONE]\n\n"
            )
        }
        server.start()
        try {
            var thrown: Throwable? = null
            val events = mutableListOf<LlmEvent>()
            try {
                runBlocking {
                    UnifiedLLMClient().stream(
                        provider = provider(server.baseUrl),
                        model = "deepseek-flash",
                        systemPrompt = "sys",
                        userPrompt = "user",
                        // 若仍以「必须收到正文片段」判定首字，800ms 足以把这条正常输出的请求误判为超时。
                        firstDeltaTimeoutMillis = 800L
                    ).collect { events += it }
                }
            } catch (t: Throwable) {
                thrown = t
            }

            assertNotNull("仅推理内容、无最终正文时必须报错", thrown)
            assertTrue(
                "应为可诊断的专用错误，实际: ${thrown?.javaClass?.simpleName}",
                thrown is ReasoningOnlyStreamException
            )
            assertFalse(
                "有 SSE 事件但无正文时不得判成首字延迟超时",
                thrown is SocketTimeoutException && thrown?.message.orEmpty().contains("首字延迟超时")
            )
            assertTrue(
                "错误消息应说明未返回最终正文，实际: ${thrown?.message}",
                thrown!!.message.orEmpty().contains("未返回最终正文")
            )
            assertTrue(
                "不得把推理内容伪装成最终答案（不得产生 TextDelta）",
                events.filterIsInstance<LlmEvent.TextDelta>().isEmpty()
            )
        } finally {
            server.stop()
        }
    }

    // ===== 思考模式参数（DeepSeek 官方专属，按 baseUrl 端点判定） =====

    @Test
    fun official_deepseek_with_thinking_disabled_sends_explicit_disabled_flag() {
        // 官方 DeepSeek Chat Completions 思考模式默认开启：非思考模式必须显式发送
        // thinking={"type":"disabled"}，否则会因缺省而继续走思考模式、拖慢最终正文。
        val payload = buildOpenAiChatPayload(
            streamRequest(provider(DEEPSEEK_OFFICIAL_BASE), thinking = false)
        )
        val thinking = payload["thinking"]?.jsonObject
        assertNotNull("官方 DeepSeek 非思考模式必须显式发送 thinking 字段", thinking)
        assertEquals("disabled", thinking!!["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun official_deepseek_with_thinking_enabled_does_not_force_disable() {
        // 用户显式启用思考模式：尊重该选择，不得悄悄强制关闭（不得发送 disabled）。
        val payload = buildOpenAiChatPayload(
            streamRequest(provider(DEEPSEEK_OFFICIAL_BASE), thinking = true)
        )
        val type = payload["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        assertFalse("启用思考模式时不得发送 disabled", type == "disabled")
    }

    @Test
    fun third_party_openai_compatible_provider_never_gets_deepseek_thinking_param() {
        // DeepSeek 专属参数绝不能无条件注入到其它 OpenAI 兼容中转站（即便模型名相同）。
        val payload = buildOpenAiChatPayload(
            streamRequest(provider("https://relay.example.com/v1"), thinking = false)
        )
        assertEquals(null, payload["thinking"])
    }

    @Test
    fun official_deepseek_base_url_detection_is_host_scoped() {
        assertTrue(isOfficialDeepSeekBaseUrl("https://api.deepseek.com/v1"))
        assertTrue(isOfficialDeepSeekBaseUrl("https://api.deepseek.com"))
        assertFalse(isOfficialDeepSeekBaseUrl("https://relay.example.com/v1"))
        assertFalse("同前缀的伪装域名不得被误判", isOfficialDeepSeekBaseUrl("https://api.deepseek.com.evil.com/v1"))
        assertFalse(isOfficialDeepSeekBaseUrl(null))
        assertFalse(isOfficialDeepSeekBaseUrl("not a url"))
    }

    // ===== 正常结束后的迟到取消回调 =====

    @Test
    fun late_cancel_after_normal_completion_is_ignored_but_pre_finish_errors_are_kept() {
        assertTrue(
            "请求已正常终止后的迟到取消回调应被忽略",
            isLateCancellationAfterFinish(alreadyFinished = true)
        )
        assertFalse(
            "请求终止前的真实 HTTP/网络/解析错误必须照常上报",
            isLateCancellationAfterFinish(alreadyFinished = false)
        )
    }

    // ===== 思考模式开关端到端到达请求体（UnifiedLLMClient → OpenAiChatAdapter → payload） =====

    @Test
    fun unified_client_thinking_on_reaches_the_request_payload() {
        // 区分能力验证：非官方端点（Mock 站）唯一可观测的「开启思考」证据是 reasoning_effort，
        // 它只在 thinking=true 且模型为 o 系列时注入 —— 用它证明开关真正到达 payload，
        // 而不依赖官方端点的 thinking 分支。
        // 同时必须仍然不注入 DeepSeek 官方专属 thinking 参数（中转站隔离）。
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(openAiTextDelta("ok"), "data: [DONE]\n\n")
        }
        server.start()
        try {
            runBlocking {
                UnifiedLLMClient().stream(
                    provider = provider(server.baseUrl),
                    model = "o3-mini",
                    systemPrompt = "sys",
                    userPrompt = "user",
                    thinking = true,
                    firstDeltaTimeoutMillis = 8_000L
                ).collect { }
            }

            val body = json.parseToJsonElement(server.requests.first().body).jsonObject
            assertEquals(
                "thinking=true 必须真正到达 payload",
                "high",
                body["reasoning_effort"]?.jsonPrimitive?.content
            )
            assertNull("第三方端点不得注入 DeepSeek 官方专属 thinking 参数", body["thinking"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun unified_client_thinking_off_reaches_the_request_payload() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(openAiTextDelta("ok"), "data: [DONE]\n\n")
        }
        server.start()
        try {
            runBlocking {
                UnifiedLLMClient().stream(
                    provider = provider(server.baseUrl),
                    model = "o3-mini",
                    systemPrompt = "sys",
                    userPrompt = "user",
                    thinking = false,
                    firstDeltaTimeoutMillis = 8_000L
                ).collect { }
            }

            val body = json.parseToJsonElement(server.requests.first().body).jsonObject
            assertNull("关闭思考时不得发送 reasoning_effort", body["reasoning_effort"])
            assertNull(body["thinking"])
        } finally {
            server.stop()
        }
    }

    private fun streamRequest(
        provider: ModelProviderConfig,
        model: String = "deepseek-flash",
        thinking: Boolean = false
    ) = StreamRequest(
        provider = provider,
        model = model,
        systemPrompt = "sys",
        userPrompt = "user",
        thinking = thinking,
        firstDeltaTimeoutMillis = 8_000L
    )

    private companion object {
        const val DEEPSEEK_OFFICIAL_BASE = "https://api.deepseek.com/v1"
    }
}