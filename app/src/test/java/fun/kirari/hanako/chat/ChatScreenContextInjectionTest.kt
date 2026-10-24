package `fun`.kirari.hanako.chat

import `fun`.kirari.hanako.data.ChatMessageStatus
import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.network.LlmEvent
import `fun`.kirari.hanako.screencontext.ScreenContextSource
import `fun`.kirari.hanako.screencontext.ScreenReadReport
import `fun`.kirari.hanako.screencontext.ScreenReadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenContextInjectionTest {

    private val provider = ModelProviderConfig(
        name = "Test",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        chatModel = "test-model"
    )

    private val config = ChatConfig(
        provider = provider,
        model = "test-model",
        systemPrompt = "sys",
        trustAllHttpsCertificates = false
    )

    private fun contextReport(
        traceId: String,
        source: ScreenContextSource,
        charCount: Int,
        status: ScreenReadStatus = ScreenReadStatus.CONTEXT_AVAILABLE
    ) = ScreenReadReport(
        traceId = traceId,
        status = status,
        source = source,
        targetPackage = "com.example.quiz",
        windowType = 1,
        windowValid = true,
        charCount = charCount,
        ocrCharCount = charCount,
        readAtMillis = 123L
    )

    private class CapturingStreamSource : ChatStreamSource {
        @Volatile
        var lastUserPrompt: String? = null

        @Volatile
        var lastSystemPrompt: String? = null

        @Volatile
        var streamCalls = 0

        /** R8.4：捕获实际送入模型的图片 base64，用于断言 `imageCount` 与实际请求一致。 */
        @Volatile
        var lastImagesBase64: List<String>? = null

        /** R8.4：捕获实际送入模型的会话历史，用于断言新题不携带旧题问答。 */
        @Volatile
        var lastRecentHistory: List<`fun`.kirari.hanako.network.ChatHistoryMessage>? = null

        /** R8.5：捕获实际送入模型的请求级 traceId，用于断言「发送 → 读取 → 模型请求」同一 traceId。 */
        @Volatile
        var lastTraceId: String? = null

        override suspend fun stream(
            provider: ModelProviderConfig,
            model: String,
            systemPrompt: String,
            userPrompt: String,
            imagesBase64: List<String>,
            webSearch: Boolean,
            thinking: Boolean,
            firstDeltaTimeoutMillis: Long,
            trustAllHttpsCertificates: Boolean,
            traceId: String?,
            recentHistory: List<`fun`.kirari.hanako.network.ChatHistoryMessage>
        ): Flow<LlmEvent> {
            streamCalls++
            lastUserPrompt = userPrompt
            lastSystemPrompt = systemPrompt
            lastImagesBase64 = imagesBase64
            lastRecentHistory = recentHistory
            lastTraceId = traceId
            return flowOf(LlmEvent.Done)
        }
    }

    /**
     * `holder.session` 是 `stateIn` 派生流，可能比内部状态滞后一次发射；
     * 断言前等待其收敛到终态（不再有 Streaming 消息），避免读取到中间态造成偶发失败。
     * 本方法只等待、不放松任何断言要求。
     */
    private suspend fun awaitSessionTerminal(holder: ChatSessionHolder) {
        withTimeout(5_000) {
            holder.session.first { session ->
                session.messages.isNotEmpty() &&
                    session.messages.none { it.status == ChatMessageStatus.Streaming }
            }
        }
    }

    @Test
    fun buildInjectedPromptWrapsContextWithBoundary() {
        val prompt = buildInjectedPrompt("帮我分析这道题", "题目：1+1=?")
        assertTrue(prompt.contains("<屏幕上下文>"))
        assertTrue(prompt.contains("题目：1+1=?"))
        assertTrue(prompt.contains("用户指令："))
        assertTrue(prompt.contains("帮我分析这道题"))
        // 空白上下文原样返回用户指令，不注入任何边界。
        assertEquals("帮我分析这道题", buildInjectedPrompt("帮我分析这道题", "  \n "))
    }

    @Test
    fun sendInjectsScreenContextIntoPromptWithoutPollutingUserBubble() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { _ ->
                ScreenContextResult.Available(
                    "当前屏幕题目文字",
                    "trace-1",
                    ScreenContextSource.ACCESSIBILITY,
                    contextReport("trace-1", ScreenContextSource.ACCESSIBILITY, "当前屏幕题目文字".length)
                )
            }
        )
        holder.send("帮我分析当前这道题")

        withTimeout(5_000) {
            while (source.lastUserPrompt == null) delay(5)
            while (!holder.isIdleForTest()) delay(5)
        }

        awaitSessionTerminal(holder)

        val recorded = source.lastUserPrompt!!
        assertTrue(recorded.contains("当前屏幕题目文字"))
        assertTrue(recorded.contains("用户指令："))
        assertTrue(recorded.contains("帮我分析当前这道题"))

        // 用户气泡只保留原始指令，不写入自动附加的屏幕上下文。
        val userMessage = holder.session.value.messages.first { it.role == ChatRole.User }
        assertEquals("帮我分析当前这道题", userMessage.content)
    }

    @Test
    fun sendWithoutInjectorKeepsPromptUnchanged() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope
        )
        holder.send("普通问题")

        withTimeout(5_000) {
            while (source.lastUserPrompt == null) delay(5)
        }

        assertEquals("普通问题", source.lastUserPrompt)
    }

    @Test
    fun sendWithMissingContextDoesNotSilentlySendAsNormalChat() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { _ -> ScreenContextResult.Missing("无障碍服务未启用，请前往系统设置开启。") }
        )
        holder.send("帮我分析当前这道题")

        withTimeout(5_000) {
            while (!holder.isIdleForTest()) delay(5)
        }

        // 实时模式已开启但上下文不可用：不得发起模型请求，也不得把请求静默降级成普通聊天。
        assertEquals(0, source.streamCalls)
        assertNull(source.lastUserPrompt)

        awaitSessionTerminal(holder)

        // 应以失败提示消息回应用户（原因明确，而不是伪装成「已看到屏幕」）。
        val assistant = holder.session.value.messages.first { it.role == ChatRole.Assistant }
        assertEquals("无障碍服务未启用，请前往系统设置开启。", assistant.content)
        // 未发起模型请求：终态是 Failed（发送失败），而非「响应未完整结束」的 Error。
        assertEquals(ChatMessageStatus.Failed, assistant.status)
        // 不得残留仍在生成的消息。
        assertFalse(holder.session.value.isStreaming)
        assertTrue(holder.session.value.messages.none { it.status == ChatMessageStatus.Streaming })
    }

    @Test
    fun sendWithAvailableContextReportsInjectedOnlyAfterPromptBuilt() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        val reports = mutableListOf<ScreenReadReport>()
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { _ ->
                ScreenContextResult.Available(
                    "屏幕内容",
                    "trace-9",
                    ScreenContextSource.LOCAL_OCR,
                    contextReport("trace-9", ScreenContextSource.LOCAL_OCR, "屏幕内容".length)
                )
            },
            screenContextReporter = { reports += it }
        )
        holder.send("分析")

        withTimeout(5_000) {
            while (source.lastUserPrompt == null) delay(5)
            while (!holder.isIdleForTest()) delay(5)
        }

        awaitSessionTerminal(holder)

        // 上下文进入最终 prompt 后才标记 SEND_INJECTED，且 traceId/来源/字数来自同一次读取结果。
        assertEquals(1, reports.size)
        val report = reports.single()
        assertEquals("trace-9", report.traceId)
        assertEquals(ScreenReadStatus.SEND_INJECTED, report.status)
        assertEquals(ScreenContextSource.LOCAL_OCR, report.source)
        assertTrue(report.injected)
        assertEquals("屏幕内容".length, report.charCount)
        // 读取诊断元数据随注入结果一起保留（同一次读取结果，不含完整文本）。
        assertEquals("com.example.quiz", report.targetPackage)
        assertEquals(1, report.windowType)
        assertTrue(report.windowValid)
    }

    @Test
    fun sendWithNotRequestedContextStreamsOriginalPromptOnce() = runBlocking {
        // realTimeMode=false 的等价场景：注入器已配置但返回 NotRequested（完全绕过屏幕读取）。
        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { _ -> ScreenContextResult.NotRequested }
        )
        holder.send("你好，请介绍你自己")

        withTimeout(5_000) {
            while (source.lastUserPrompt == null) delay(5)
            while (!holder.isIdleForTest()) delay(5)
        }

        awaitSessionTerminal(holder)

        // 普通聊天必须真正发起模型请求，且 prompt 为原始用户问题，不掺杂屏幕上下文。
        assertEquals(1, source.streamCalls)
        assertEquals("你好，请介绍你自己", source.lastUserPrompt)

        val assistant = holder.session.value.messages.first { it.role == ChatRole.Assistant }
        assertEquals(ChatMessageStatus.Done, assistant.status)
        assertFalse(holder.session.value.isStreaming)
    }

    @Test
    fun networkFailureWithNotRequestedShowsErrorNotSendFailed() = runBlocking {
        // 普通聊天网络失败属于真实请求错误（Error），不得被误标为上下文读取失败（Failed）。
        val scope = CoroutineScope(Dispatchers.Default)
        val failingStream = ChatStreamSource { _, _, _, _, _, _, _, _, _, _, _ ->
            flow { throw IllegalStateException("network reset") }
        }
        val holder = ChatSessionHolder(
            streamSource = failingStream,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { _ -> ScreenContextResult.NotRequested }
        )
        holder.send("普通问题")

        withTimeout(5_000) {
            while (!holder.isIdleForTest()) delay(5)
        }

        awaitSessionTerminal(holder)

        val assistant = holder.session.value.messages.first { it.role == ChatRole.Assistant }
        assertEquals(ChatMessageStatus.Error, assistant.status)
        assertFalse(holder.session.value.isStreaming)
    }

    @Test
    fun poolSnapshotContextReachesActualModelRequest() = runBlocking {
        // 场景 8：验证最终**真正发送给模型的请求**包含当前题目屏幕上下文，
        // 而不只是打印了 context-injected 一类日志。用 POOL_SNAPSHOT 来源模拟实时累计题目池。
        val poolSnapshot = listOf(
            "专项智能练习（资料分析）",
            "问题：2023年上半年全国规模以上工业企业实现利润总额同比约减少多少亿元？",
            "A 约5600亿元",
            "B 约6800亿元"
        ).joinToString("\n")

        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { _ ->
                ScreenContextResult.Available(
                    poolSnapshot,
                    "trace-pool",
                    ScreenContextSource.POOL_SNAPSHOT,
                    contextReport("trace-pool", ScreenContextSource.POOL_SNAPSHOT, poolSnapshot.length)
                )
            }
        )
        holder.send("这道题选什么？")

        withTimeout(5_000) {
            while (source.lastUserPrompt == null) delay(5)
            while (!holder.isIdleForTest()) delay(5)
        }
        awaitSessionTerminal(holder)

        // 真正发出的请求：恰好一次，且 userPrompt 含完整题目池内容；系统提示已切换为实时答题提示词。
        assertEquals(1, source.streamCalls)
        val sent = source.lastUserPrompt!!
        assertTrue("最终请求必须包含当前题干", sent.contains("利润总额同比约减少多少亿元"))
        assertTrue("最终请求必须包含当前选项", sent.contains("约5600亿元"))
        assertTrue("最终请求必须包含屏幕上下文边界", sent.contains("<屏幕上下文>"))
        assertTrue("最终请求必须包含用户指令", sent.contains("这道题选什么？"))
        assertEquals(REALTIME_ANSWER_SYSTEM_PROMPT, source.lastSystemPrompt)

        // 用户气泡仍只保留原始指令，题目内容不进气泡。
        val userMessage = holder.session.value.messages.first { it.role == ChatRole.User }
        assertEquals("这道题选什么？", userMessage.content)
    }

    @Test
    fun freshQuestionRequestCarriesNoHistoryAndNoImageInput() = runBlocking {
        // 场景 8 / 9：断言**实际传入 streamSource.stream(...)** 的 userPrompt、history 与 imagesBase64，
        // 而不是只看辅助函数返回值。实时题目上下文以**文本**形式注入，不发送截图（imageCount=0 与实际一致）。
        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        val fresh = "第 10/15 题\n材料：某市 2020 年新业态营业收入 1250 亿元\nA 甲\nB 乙"
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { tid ->
                ScreenContextResult.Available(
                    fresh,
                    tid,
                    ScreenContextSource.ACCESSIBILITY,
                    contextReport(tid, ScreenContextSource.ACCESSIBILITY, fresh.length)
                )
            }
        )
        holder.send("这题选什么")

        withTimeout(5_000) {
            while (source.lastUserPrompt == null) delay(5)
            while (!holder.isIdleForTest()) delay(5)
        }
        awaitSessionTerminal(holder)

        val sent = source.lastUserPrompt!!
        assertTrue("最终请求必须含本轮新鲜题目题干", sent.contains("新业态营业收入"))
        assertTrue("最终请求必须含本轮选项", sent.contains("A 甲"))
        assertTrue("最终请求必须含屏幕上下文边界", sent.contains("<屏幕上下文>"))
        assertNotNull("必须捕获到实际传入的历史", source.lastRecentHistory)
        assertEquals("新题首次提问不得携带任何历史消息", 0, source.lastRecentHistory!!.size)

        assertNotNull("必须捕获到实际传入的图片列表", source.lastImagesBase64)
        assertTrue(
            "实时题目链路以文本注入上下文，不带图片输入（imageCount=0 与实际构造请求一致）",
            source.lastImagesBase64!!.isEmpty()
        )
    }

    @Test
    fun newQuestionAfterPreviousDoesNotCarryOldQuestionContextOrHistory() = runBlocking {
        // 场景 12：新题请求不得自动拼入旧题的题干 / 选项 / 助手答案；换题后历史必须为空。
        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        val q10 = "第 10/15 题\n材料：某市 2020 年新业态营业收入\nA 甲\nB 乙"
        val q11 = "第 11/15 题\n材料：某市 2020 年新业态营业收入\nC 丙\nD 丁"
        val responses = ArrayDeque(listOf(q10, q11))
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { tid ->
                val text = responses.removeFirst()
                ScreenContextResult.Available(
                    text,
                    tid,
                    ScreenContextSource.ACCESSIBILITY,
                    contextReport(tid, ScreenContextSource.ACCESSIBILITY, text.length)
                )
            }
        )

        holder.send("第 10 题选什么")
        withTimeout(5_000) {
            while (source.streamCalls < 1) delay(5)
            while (!holder.isIdleForTest()) delay(5)
        }
        awaitSessionTerminal(holder)
        assertEquals("首题无疑问历史", 0, source.lastRecentHistory!!.size)

        holder.send("第 11 题选什么")
        withTimeout(5_000) {
            while (source.streamCalls < 2) delay(5)
            while (!holder.isIdleForTest()) delay(5)
        }
        awaitSessionTerminal(holder)

        val second = source.lastUserPrompt!!
        assertTrue("新题请求必须含本轮第 11 题上下文", second.contains("第 11/15 题"))
        assertFalse("不得把上一题题干混入新题请求", second.contains("第 10/15 题"))
        assertFalse("不得把上一题选项混入新题请求", second.contains("A 甲"))
        assertEquals("换题后不得携带上一题问答历史", 0, source.lastRecentHistory!!.size)
        assertTrue("换题后请求同样不得携带图片输入", source.lastImagesBase64!!.isEmpty())
    }

    // ==================== R8.5：请求级 traceId 贯穿 / 识别失败拦截 / 有界性 ====================

    @Test
    fun requestTraceIdIsSharedByScreenReadAndActualModelRequest() = runBlocking {
        // req 8 的对应关系：同一次用户提问的 traceId 必须从发送贯穿到**最终模型请求**，
        // 使「本次提问 → 本次读取 → 本次请求」一一对应，而不是把后台探测的 OCR 当成本次结果。
        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        var injectorTraceId: String? = null
        val fresh = "第 12/15 题\n材料：某市 2021 年规模以上工业增加值 3200 亿元\nA 甲\nB 乙"
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { tid ->
                injectorTraceId = tid
                ScreenContextResult.Available(
                    fresh,
                    tid,
                    ScreenContextSource.LOCAL_OCR,
                    contextReport(tid, ScreenContextSource.LOCAL_OCR, fresh.length)
                )
            }
        )
        holder.send("这题选什么")
        withTimeout(5_000) {
            while (source.lastUserPrompt == null) delay(5)
            while (!holder.isIdleForTest()) delay(5)
        }
        awaitSessionTerminal(holder)

        assertNotNull("发送时必须为本次请求生成 traceId", injectorTraceId)
        assertEquals("屏幕读取与最终模型请求必须使用同一 traceId", injectorTraceId, source.lastTraceId)
        assertTrue("最终请求必须使用本次读取的上下文", source.lastUserPrompt!!.contains("3200 亿元"))
    }

    @Test
    fun blockedContentInsufficientRequestSendsNothingAndReportsReason() = runBlocking {
        // req 9：识别失败（只有页面外壳 / 输入法键盘 / 旧题文本）时必须拦截模型请求并明确告知原因，
        // 绝不偷偷发出只含这些文本的请求。
        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        val reason = `fun`.kirari.hanako.screencontext.failureReasonText("content_insufficient")
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { _ -> ScreenContextResult.Missing(reason) }
        )
        holder.send("这题选什么")
        withTimeout(5_000) { while (!holder.isIdleForTest()) delay(5) }
        awaitSessionTerminal(holder)

        assertEquals("识别失败时不得发起任何模型请求", 0, source.streamCalls)
        assertNull("不得凭外壳 / 键盘文本构造请求", source.lastUserPrompt)
        val assistant = holder.session.value.messages.first { it.role == ChatRole.Assistant }
        assertEquals("必须把识别失败原因如实告知用户", reason, assistant.content)
        assertEquals(ChatMessageStatus.Failed, assistant.status)
    }

    @Test
    fun repeatedSendsKeepOneScreenReadPerSendAndNeverLeakPreviousContext() = runBlocking {
        // req 10 的请求级不变式：N 次发送恰好触发 N 次屏幕读取、N 次模型请求，
        // 且每次请求只带自己的上下文（不会因重复发送进入无界截图 / OCR 循环）。
        val scope = CoroutineScope(Dispatchers.Default)
        val source = CapturingStreamSource()
        var injectorCalls = 0
        val contexts = ArrayDeque(
            listOf(
                "第 1/3 题\nA 甲\nB 乙",
                "第 2/3 题\nC 丙\nD 丁",
                "第 3/3 题\nE 戊\nF 己"
            )
        )
        val prompts = mutableListOf<String>()
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            screenContextInjector = { tid ->
                injectorCalls++
                val text = contexts.removeFirst()
                ScreenContextResult.Available(
                    text,
                    tid,
                    ScreenContextSource.LOCAL_OCR,
                    contextReport(tid, ScreenContextSource.LOCAL_OCR, text.length)
                )
            }
        )
        repeat(3) { index ->
            holder.send("第 ${index + 1} 题选什么")
            withTimeout(5_000) {
                while (source.streamCalls < index + 1) delay(5)
                while (!holder.isIdleForTest()) delay(5)
            }
            prompts += source.lastUserPrompt!!
        }
        awaitSessionTerminal(holder)

        assertEquals("每次发送恰好一次屏幕读取", 3, injectorCalls)
        assertEquals("每次发送恰好一次模型请求（无重复 / 无循环）", 3, source.streamCalls)
        assertTrue("第 1 题请求必须含本轮上下文", prompts[0].contains("第 1/3 题"))
        assertFalse("第 3 题请求不得混入第 1 题上下文", prompts[2].contains("第 1/3 题"))
        assertTrue("第 3 题请求必须含本轮上下文", prompts[2].contains("第 3/3 题"))
    }
}