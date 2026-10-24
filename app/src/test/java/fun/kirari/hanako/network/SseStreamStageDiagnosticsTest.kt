package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * SSE 请求阶段诊断测试。
 *
 * 目标：证明首字定时器超时能按「请求真实到达的网络阶段」分类，
 * 而不是把所有阶段统一伪装成同一种「首字延迟超时」；
 * 同时证明真实 HTTP / 网络错误、正常结束、迟到取消回调都不会被误判。
 */
class SseStreamStageDiagnosticsTest {

    private fun provider(baseUrl: String) = ModelProviderConfig(
        name = "DeepSeek",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = baseUrl,
        apiKey = "ds-key",
        chatModel = "deepseek-flash",
        visionModel = "deepseek-flash",
        ocrModel = "deepseek-flash"
    )

    /** 走一遍完整的 UnifiedLLMClient → OpenAiChatAdapter → SseStreamClient 链路，返回抛出的异常（若有）。 */
    private fun streamOnceExpectThrow(baseUrl: String, firstDeltaTimeoutMillis: Long): Throwable? {
        var thrown: Throwable? = null
        try {
            runBlocking {
                UnifiedLLMClient().stream(
                    provider = provider(baseUrl),
                    model = "deepseek-flash",
                    systemPrompt = "sys",
                    userPrompt = "user",
                    firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                    traceId = "trace-stage"
                ).collect { }
            }
        } catch (t: Throwable) {
            thrown = t
        }
        return thrown
    }

    /** 取最近若干条调试日志；断言失败时给出可核对的阶段证据（不含任何密钥）。 */
    private fun recentStageLogs(limit: Int = 30): String =
        AppDebugLogStore.entries.value
            .takeLast(limit)
            .joinToString("\n") { "${it.level}/${it.tag} ${it.message}" }

    // ===== 纯逻辑：最远到达阶段 → 阶段分类 =====

    @Test
    fun phase_classification_covers_every_real_stage() {
        // 请求尚未真正进入网络调用：未创建 / 创建后调用未开始
        assertEquals(
            SSE_PHASE_NOT_DISPATCHED,
            streamTimeoutPhaseLabel(SseRequestStage.UNKNOWN, 0, false)
        )
        assertEquals(
            SSE_PHASE_NOT_DISPATCHED,
            streamTimeoutPhaseLabel(SseRequestStage.CALL_START, 0, false)
        )
        // DNS 解析阶段
        assertEquals(SSE_PHASE_DNS, streamTimeoutPhaseLabel(SseRequestStage.DNS_START, 0, false))
        // TCP 建连阶段
        assertEquals(SSE_PHASE_CONNECT, streamTimeoutPhaseLabel(SseRequestStage.DNS_END, 0, false))
        assertEquals(SSE_PHASE_CONNECT, streamTimeoutPhaseLabel(SseRequestStage.CONNECT_START, 0, false))
        assertEquals(SSE_PHASE_CONNECT, streamTimeoutPhaseLabel(SseRequestStage.CONNECT_END, 0, false))
        // TLS 握手阶段
        assertEquals(SSE_PHASE_TLS, streamTimeoutPhaseLabel(SseRequestStage.TLS_START, 0, false))
        assertEquals(SSE_PHASE_TLS, streamTimeoutPhaseLabel(SseRequestStage.TLS_END, 0, false))
        // 请求已发出、等待 HTTP 响应头
        assertEquals(
            SSE_PHASE_AWAITING_RESPONSE_HEADERS,
            streamTimeoutPhaseLabel(SseRequestStage.REQUEST_HEADERS_START, 0, false)
        )
        assertEquals(
            SSE_PHASE_AWAITING_RESPONSE_HEADERS,
            streamTimeoutPhaseLabel(SseRequestStage.REQUEST_HEADERS_END, 0, false)
        )
        // 响应头已到、等待首个 SSE 事件
        assertEquals(
            SSE_PHASE_AWAITING_FIRST_EVENT,
            streamTimeoutPhaseLabel(SseRequestStage.RESPONSE_HEADERS_START, 0, false)
        )
        assertEquals(
            SSE_PHASE_AWAITING_FIRST_EVENT,
            streamTimeoutPhaseLabel(SseRequestStage.RESPONSE_HEADERS_END, 0, false)
        )
        // 已有 SSE 事件、但还没有任何正文
        assertEquals(
            SSE_PHASE_AWAITING_FIRST_DELTA,
            streamTimeoutPhaseLabel(SseRequestStage.RESPONSE_HEADERS_END, 3, false)
        )
        // 服务端已开始输出：正常情形，不应由定时器中断
        assertEquals(
            SSE_PHASE_COMPLETED,
            streamTimeoutPhaseLabel(SseRequestStage.RESPONSE_HEADERS_END, 3, true)
        )
    }

    @Test
    fun timeout_exception_is_stage_specific_instead_of_uniform_first_delta_text() {
        val notDispatched = firstDeltaTimeoutException(SSE_PHASE_NOT_DISPATCHED, "unknown", 20_000L)
        assertTrue(
            "请求未进入网络调用不是网络超时，不得伪装成超时",
            notDispatched is IllegalStateException
        )
        assertTrue(notDispatched.message.orEmpty().contains("未进入网络调用"))

        val awaitingHeaders =
            firstDeltaTimeoutException(SSE_PHASE_AWAITING_RESPONSE_HEADERS, "request-headers-end", 20_000L)
        assertTrue(awaitingHeaders is SocketTimeoutException)
        assertTrue(
            "应明确指出未收到 HTTP 响应头，实际: ${awaitingHeaders.message}",
            awaitingHeaders.message.orEmpty().contains("未收到 HTTP 响应头")
        )
        assertFalse(
            "响应头都未到达时不得继续写成首字延迟超时，实际: ${awaitingHeaders.message}",
            awaitingHeaders.message.orEmpty().contains("首字延迟超时")
        )

        val awaitingEvent = firstDeltaTimeoutException(SSE_PHASE_AWAITING_FIRST_EVENT, "response-headers-end", 20_000L)
        assertTrue(awaitingEvent is SocketTimeoutException)
        assertTrue(awaitingEvent.message.orEmpty().contains("首字延迟超时"))
        assertTrue(awaitingEvent.message.orEmpty().contains("未收到首个 SSE 事件"))

        val awaitingDelta = firstDeltaTimeoutException(SSE_PHASE_AWAITING_FIRST_DELTA, "response-headers-end", 20_000L)
        assertTrue(awaitingDelta.message.orEmpty().contains("已收到 SSE 事件但未收到正文"))

        // 不足 1 秒的超时必须显示毫秒，不得出现「等待 0 秒」这种无法定位的文案。
        val shortTimeout = firstDeltaTimeoutException(SSE_PHASE_AWAITING_RESPONSE_HEADERS, "request-headers-end", 800L)
        assertTrue("实际: ${shortTimeout.message}", shortTimeout.message.orEmpty().contains("800ms"))
        assertFalse("实际: ${shortTimeout.message}", shortTimeout.message.orEmpty().contains("0 秒"))
    }

    // ===== 端到端：真实 Mock 服务器上的各阶段 =====

    /**
     * 「请求根本没有进入网络调用」的失败必须保持真实原因，不得被静默等待后伪装成「首字延迟超时」。
     *
     * 这里直接把调度器已关闭的客户端注入 [SseStreamClient]，让 OkHttp 在派发阶段即失败，
     * 并核对阶段分类落在 `not-dispatched`、且真实异常消息被保留。
     *
     * 说明：`stream-create-failed` 是防御性日志分支。就当前依赖 okhttp-sse 4.12.0 而言，
     * `newEventSource` 不会同步抛异常（调度器拒绝会转成 onFailure 上报），因此该分支在真实链路上不可达；
     * 「未入网」的分类本身由 `streamTimeoutPhaseLabel` / `firstDeltaTimeoutException` 的纯逻辑测试保证。
     */
    @Test
    fun request_that_never_enters_network_call_is_not_masked_as_timeout() {
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher())
            .build()
        client.dispatcher.executorService.shutdown()

        var thrown: Throwable? = null
        try {
            runBlocking {
                SseStreamClient(client).stream(
                    request = Request.Builder().url("http://127.0.0.1:1/chat/completions").build(),
                    traceId = "trace-create-fail",
                    firstDeltaTimeoutMillis = 20_000L,
                    onEvent = { _, _, _, _ -> null },
                    onDelta = { }
                )
            }
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(
            "未入网的失败必须立即上报，而不是静默等待 20 秒，日志:\n${recentStageLogs()}",
            thrown
        )
        assertFalse(
            "未入网的失败不得被伪装成首字延迟超时，实际: ${thrown?.javaClass?.name}: ${thrown?.message}",
            thrown?.message.orEmpty().contains("首字延迟超时")
        )
        val logs = AppDebugLogStore.entries.value
            .filter { it.tag == "HanakoSseClient" && it.message.contains("trace=trace-create-fail") }
            .joinToString("\n") { it.message }
        assertTrue("必须留下真实失败阶段分类，实际:\n$logs", logs.contains("phase=not-dispatched"))
        assertTrue("必须保留底层真实异常消息，实际:\n$logs", logs.contains("executor rejected"))
        assertTrue(
            "未由首字定时器触发时不得标记为定时器超时，实际:\n$logs",
            logs.contains("firstDeltaTimerFired=false")
        )
    }

    @Test
    fun timeout_without_response_headers_is_classified_and_late_cancel_does_not_override() {
        val server = MockHttpServer { _, _ ->
            // 请求已被读到（因此请求确实发出），但服务端一个字节都不写：
            // HTTP 响应头始终没有到达。
            Thread.sleep(4_000)
        }
        server.start()
        try {
            val thrown = streamOnceExpectThrow(server.baseUrl, firstDeltaTimeoutMillis = 800L)
            assertNotNull("响应头未到达时必须超时结束，而不是无限等待", thrown)
            val typeInfo = "实际=${thrown?.javaClass?.name}: ${thrown?.message}"
            assertTrue(
                "应由应用定时器产生 SocketTimeoutException，$typeInfo\n日志:\n${recentStageLogs()}",
                thrown is SocketTimeoutException
            )
            val message = thrown!!.message.orEmpty()
            assertTrue("应指出未收到 HTTP 响应头，实际: $message", message.contains("未收到 HTTP 响应头"))
            assertFalse("不得伪装成首字延迟超时，实际: $message", message.contains("首字延迟超时"))
            assertFalse(
                "定时器取消引发的迟到回调不得覆盖原始故障，实际: $message",
                message.contains("Canceled")
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun timeout_with_response_headers_but_no_sse_event_reports_first_event_phase() {
        val server = MockHttpServer { _, exchange ->
            // 只写出 SSE 响应头，随后不下发任何事件。
            exchange.beginSse()
            Thread.sleep(4_000)
        }
        server.start()
        try {
            val thrown = streamOnceExpectThrow(server.baseUrl, firstDeltaTimeoutMillis = 800L)
            assertNotNull(thrown)
            assertTrue(
                "实际=${thrown?.javaClass?.name}: ${thrown?.message}\n日志:\n${recentStageLogs()}",
                thrown is SocketTimeoutException
            )
            val message = thrown!!.message.orEmpty()
            assertTrue("该阶段才是真正的首字延迟超时，实际: $message", message.contains("首字延迟超时"))
            assertTrue(
                "应指出未收到首个 SSE 事件（区别于零事件以外的其它阶段），实际: $message",
                message.contains("未收到首个 SSE 事件")
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun timeout_with_sse_event_but_no_body_is_distinguished_from_zero_events() {
        val server = MockHttpServer { _, exchange ->
            val out = exchange.beginSse()
            // 真实 OpenAI 流首帧形态：只有角色帧、没有正文。
            out.write("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n".toByteArray())
            out.flush()
            Thread.sleep(5_000)
        }
        server.start()
        try {
            val thrown = streamOnceExpectThrow(server.baseUrl, firstDeltaTimeoutMillis = 2_500L)
            assertNotNull(thrown)
            assertTrue(thrown is SocketTimeoutException)
            val message = thrown!!.message.orEmpty()
            assertTrue(
                "事件确实在到达时，必须与「零事件」区分开，实际: $message",
                message.contains("已收到 SSE 事件但未收到正文")
            )
            assertFalse(
                "事件已到达时不得写成「未收到首个 SSE 事件」，实际: $message",
                message.contains("未收到首个 SSE 事件")
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun real_connection_failure_is_preserved_and_not_masked() {
        // 取一个随即释放的本地端口：连接必然被拒绝，这是真实网络错误而非首字延迟超时。
        val port = ServerSocket(0).use { it.localPort }
        val thrown = streamOnceExpectThrow("http://127.0.0.1:$port", firstDeltaTimeoutMillis = 8_000L)
        assertNotNull("连接失败必须上报", thrown)
        assertFalse(
            "真实连接失败不得被伪装成首字延迟超时，实际: ${thrown?.message}",
            thrown is SocketTimeoutException && thrown?.message.orEmpty().contains("首字延迟超时")
        )
        assertFalse(
            "真实连接失败不得被阶段分类文案覆盖，实际: ${thrown?.message}",
            thrown?.message.orEmpty().contains("未收到 HTTP 响应头")
        )
    }

    @Test
    fun normal_body_and_done_still_completes_on_the_same_request_configuration() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse(openAiTextDelta("He"), openAiTextDelta("llo"), "data: [DONE]\n\n")
        }
        server.start()
        try {
            val events = mutableListOf<LlmEvent>()
            runBlocking {
                UnifiedLLMClient().stream(
                    provider = provider(server.baseUrl),
                    model = "deepseek-flash",
                    systemPrompt = "sys",
                    userPrompt = "user",
                    firstDeltaTimeoutMillis = 5_000L,
                    traceId = "trace-stage"
                ).collect { events += it }
            }
            assertEquals(
                listOf("He", "llo"),
                events.filterIsInstance<LlmEvent.TextDelta>().map { it.text }
            )
            assertTrue("正常流必须收尾为 Done", events.last() is LlmEvent.Done)

            // 新增的阶段诊断不得改变既有线上请求配置（普通聊天与实时互动继续共用）。
            val request = server.requests.first()
            assertEquals("POST", request.method)
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer ds-key", request.header("Authorization"))
            assertTrue(
                "请求体仍应是 JSON",
                request.header("Content-Type").orEmpty().startsWith("application/json")
            )
        } finally {
            server.stop()
        }
    }

    /**
     * 回归：阶段探针必须在 SSE 路径上真实生效。
     *
     * 依据 okhttp-sse 4.12.0 的 RealEventSource.connect() 真实源码，它会执行
     * `client.newBuilder().eventListener(EventListener.NONE).build()`，把外部注册的 OkHttp
     * EventListener 强制覆盖为 NONE。因此本项目的阶段探针只能用 OkHttp Interceptor
     * （会被 newBuilder 复制），不能用 EventListener（会被覆盖）。
     *
     * 若将来有人把探针改回 EventListener，或升级 okhttp-sse 后其行为变化，本测试必须失败，
     * 从而避免「阶段诊断静默失效、所有超时又退化成同一种文案」。
     */
    @Test
    fun stage_probe_observes_real_network_stages_on_the_sse_path() {
        val server = MockHttpServer { _, exchange ->
            exchange.respondSse("data: [DONE]\n\n")
        }
        server.start()
        try {
            runBlocking {
                UnifiedLLMClient().stream(
                    provider = provider(server.baseUrl),
                    model = "deepseek-flash",
                    systemPrompt = "sys",
                    userPrompt = "user",
                    firstDeltaTimeoutMillis = 5_000L,
                    traceId = "trace-probe"
                ).collect { }
            }
            val logs = AppDebugLogStore.entries.value
                .filter { it.tag == "HanakoSseClient" && it.message.contains("trace=trace-probe") }
                .joinToString("\n") { it.message }
            assertTrue("SSE 路径必须记录「已进入 OkHttp 调用链」阶段，实际:\n$logs", logs.contains("stage=chain-enter"))
            assertTrue(
                "SSE 路径必须记录「连接已建立、请求头发出」阶段（这是区分卡在连接 vs 卡在响应头的关键证据），实际:\n$logs",
                logs.contains("stage=connected-request-headers")
            )
            assertTrue(
                "SSE 路径必须记录「响应头已到达」阶段与 HTTP 状态码，实际:\n$logs",
                logs.contains("stage=response-headers-end(code=200)")
            )
            assertTrue("阶段日志必须携带单调时钟耗时，实际:\n$logs", logs.contains("elapsedMonotonicMs="))
            assertFalse(
                "阶段日志不得泄露 Authorization / API Key，实际:\n$logs",
                logs.contains("ds-key") || logs.contains("Authorization")
            )
        } finally {
            server.stop()
        }
    }
}
