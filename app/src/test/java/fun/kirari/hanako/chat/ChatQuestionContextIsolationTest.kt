package `fun`.kirari.hanako.chat

import `fun`.kirari.hanako.data.ChatMessage
import `fun`.kirari.hanako.data.ChatMessageStatus
import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.data.ChatSession
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.network.ChatHistoryMessage
import `fun`.kirari.hanako.network.LlmEvent
import `fun`.kirari.hanako.screencontext.ScreenContextSource
import `fun`.kirari.hanako.screencontext.ScreenReadReport
import `fun`.kirari.hanako.screencontext.ScreenReadStatus
import `fun`.kirari.hanako.screencontext.shouldResolveScreenContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val testProvider = ModelProviderConfig(
    name = "Test",
    kind = ProviderKind.OPENAI_COMPATIBLE,
    baseUrl = "https://example.invalid/v1",
    apiKey = "test-key",
    chatModel = "test-model"
)

private val testConfig = ChatConfig(
    provider = testProvider,
    model = "test-model",
    systemPrompt = "sys",
    trustAllHttpsCertificates = false
)

/**
 * 实时刷题场景下「题目上下文隔离 / 请求与消息绑定」的纯逻辑单元测试（不联网）。
 *
 * 覆盖本轮 P0 关注的四类问题：
 * 1. 新题请求不得携带上一题的 OCR、上一题的助手答案与上一题的问答历史（模型只收到「当前题目 + 当前用户消息」）；
 * 2. 同题追问在题目上下文一致时，可携带「最近必要」的上一轮问答（作为独立历史消息，而非拼进 userPrompt）；
 * 3. 重试必须复用「原请求绑定」的题目上下文，不得误用用户已切到的新题 OCR，且不携带历史；
 * 4. 旧请求的流式增量只按自己的助手消息 id 归位，不会污染新题助手消息；
 *    被取消的旧请求收尾也不得把新请求的活跃标记清空（否则会误判空闲并开启并发请求）。
 *
 * 补充覆盖：同题判定（[sameQuestionContext]）与历史裁剪（[recentHistoryBefore]）的纯函数行为，
 * 明确「不依赖 OCR 指纹」「无法判定时按新题处理（不携带历史）」两条原则。
 */
class ChatQuestionContextIsolationTest {

    /**
     * 测试夹具：记录每次请求真正送入模型的 systemPrompt / userPrompt，
     * 由可控注入器返回「当前屏幕题目文本」，同时记录诊断报告与注入器调用。
     */
    private class Harness(withInjector: Boolean = true) {
        private val scope = CoroutineScope(Dispatchers.Default)
        private val flows = ArrayDeque<Flow<LlmEvent>>()

        /** 当前屏幕（题目）文本；null 表示读取失败。 */
        @Volatile
        var screenText: String? = null

        val injectedTraceIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reports = java.util.Collections.synchronizedList(mutableListOf<ScreenReadReport>())
        val capturedSystemPrompts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val capturedUserPrompts = java.util.Collections.synchronizedList(mutableListOf<String>())

        /** 每次请求实际携带的「同题追问历史」（新题必须为空）。 */
        val capturedHistory =
            java.util.Collections.synchronizedList(mutableListOf<List<`fun`.kirari.hanako.network.ChatHistoryMessage>>())

        fun enqueue(vararg streams: Flow<LlmEvent>) {
            flows.addAll(streams.toList())
        }

        val holder = ChatSessionHolder(
            streamSource = ChatStreamSource { _, _, systemPrompt, userPrompt, _, _, _, _, _, _, history ->
                capturedSystemPrompts += systemPrompt
                capturedUserPrompts += userPrompt
                capturedHistory += history
                flows.removeFirst()
            },
            configProvider = { testConfig },
            scope = scope,
            screenContextInjector = if (withInjector) {
                { traceId ->
                    injectedTraceIds += traceId
                    val text = screenText
                    if (text == null) {
                        ScreenContextResult.Missing("no-screen")
                    } else {
                        ScreenContextResult.Available(
                            text = text,
                            traceId = traceId,
                            source = ScreenContextSource.LOCAL_OCR,
                            readReport = ScreenReadReport(
                                traceId = traceId,
                                status = ScreenReadStatus.CONTEXT_AVAILABLE,
                                source = ScreenContextSource.LOCAL_OCR,
                                charCount = text.length,
                                injectedText = text,
                                readAtMillis = 1L
                            )
                        )
                    }
                }
            } else {
                null
            },
            screenContextReporter = { reports += it }
        )
    }

    /** 等待会话进入「无 Streaming 消息」且流式任务已收尾的稳定态。 */
    private fun settle(holder: ChatSessionHolder): ChatSession = runBlocking {
        withTimeout(5_000) {
            var result: ChatSession? = null
            var lastStable: ChatSession? = null
            var stable = 0
            while (result == null) {
                val session = holder.session.value
                if (session.messages.isNotEmpty() &&
                    session.messages.none { it.status == ChatMessageStatus.Streaming } &&
                    holder.isIdleForTest()
                ) {
                    if (session == lastStable) {
                        stable++
                        if (stable >= 3) result = session
                    } else {
                        lastStable = session
                        stable = 0
                    }
                } else {
                    lastStable = null
                    stable = 0
                }
                if (result == null) delay(10)
            }
            result!!
        }
    }

    /** 等待某条消息内容满足条件。 */
    private fun awaitContent(holder: ChatSessionHolder, predicate: (String) -> Boolean): ChatSession =
        runBlocking {
            withTimeout(5_000) { holder.session.first { s -> s.messages.any { predicate(it.content) } } }
        }

    // ========================
    // 1. 新题请求不得携带上一题的 OCR / 历史问答
    // ========================

    @Test
    fun new_question_request_does_not_carry_previous_question_text_or_answers() {
        val h = Harness()
        h.screenText = "题目A：1+1=?"
        h.enqueue(flowOf(LlmEvent.TextDelta("A的答案"), LlmEvent.Done))

        h.holder.send("第一题怎么做")
        settle(h.holder)

        // 用户切到 B 题后发送新问题
        h.screenText = "题目B：2+2=?"
        h.enqueue(flowOf(LlmEvent.TextDelta("B的答案"), LlmEvent.Done))
        h.holder.send("这道呢")
        settle(h.holder)

        assertEquals(2, h.capturedUserPrompts.size)
        val secondPrompt = h.capturedUserPrompts[1]

        assertTrue("B 题请求必须包含 B 的当前 OCR", secondPrompt.contains("题目B"))
        assertFalse("B 题请求不得再注入上一题 A 的 OCR", secondPrompt.contains("题目A"))
        assertFalse("B 题请求不得携带上一题的助手答案", secondPrompt.contains("A的答案"))
        assertFalse("B 题请求不得携带上一题的用户消息", secondPrompt.contains("第一题怎么做"))

        // 切题（屏幕文本变化）后，同题判定必须判为「新题」：不得携带任何问答历史。
        assertTrue("首次请求没有历史可带", h.capturedHistory[0].isEmpty())
        assertTrue("新题请求不得携带任何历史问答", h.capturedHistory[1].isEmpty())
    }

    // ========================
    // 2. 同题追问可访问上一轮必要问答
    // ========================

    @Test
    fun follow_up_on_same_question_can_access_previous_round_answer() {
        val h = Harness()
        h.screenText = "题目A：1+1=?\nA.1 B.2 C.3 D.4"
        h.enqueue(flowOf(LlmEvent.TextDelta("答案是 D"), LlmEvent.Done))

        h.holder.send("这题选什么")
        settle(h.holder)

        h.enqueue(flowOf(LlmEvent.TextDelta("因为 1+1=2"), LlmEvent.Done))
        h.holder.send("为什么不是 C")
        settle(h.holder)

        assertEquals(2, h.capturedUserPrompts.size)
        assertTrue(h.capturedUserPrompts[0].contains("A.1 B.2 C.3 D.4"))
        assertTrue("同题追问必须保留当前题目上下文", h.capturedUserPrompts[1].contains("A.1 B.2 C.3 D.4"))
        // 上一轮助手答案不得被拼进本轮 userPrompt（历史是独立消息，不污染当前指令）。
        assertFalse("助手答案不得拼进本轮用户 prompt", h.capturedUserPrompts[1].contains("答案是 D"))

        // 同题追问确实能访问「最近必要」的上一轮问答：用户问题 + 助手答案。
        val history = h.capturedHistory[1]
        assertEquals("同题追问应携带最近一组问答", 2, history.size)
        assertEquals(ChatHistoryMessage(ChatRole.User, "这题选什么"), history[0])
        assertEquals(ChatHistoryMessage(ChatRole.Assistant, "答案是 D"), history[1])
    }

    // ========================
    // 3. OCR 文本与当前用户消息绑定正确
    // ========================

    @Test
    fun screen_text_is_bound_to_the_current_user_message() {
        val h = Harness()
        val question = "题目C：下列哪个是质数？A.4 B.6 C.7 D.9"
        h.screenText = question
        h.enqueue(flowOf(LlmEvent.Done))

        h.holder.send("帮我解答")
        settle(h.holder)

        val prompt = h.capturedUserPrompts.single()
        assertTrue("同一次请求必须同时包含当前题目文本与当前用户指令", prompt.contains("C.7"))
        assertTrue(prompt.contains("帮我解答"))

        // 诊断报告必须携带「真正注入前的文本」，并与本次请求的 trace（助手消息 id）一致。
        val report = h.reports.lastOrNull { it.injected }
        assertNotNull(report)
        assertEquals(question, report!!.injectedText)
        assertTrue(report.traceId.isNotBlank())
    }

    @Test
    fun injected_request_uses_realtime_answer_system_prompt() {
        val h = Harness()
        h.screenText = "题目A：1+1=?"
        h.enqueue(flowOf(LlmEvent.Done))

        h.holder.send("作答")
        settle(h.holder)

        val systemPrompt = h.capturedSystemPrompts.single()
        assertNotEquals("注入了题目上下文时必须改用实时作答提示", "sys", systemPrompt)
        assertTrue(systemPrompt.contains("不得把之前任何一道题目"))
    }

    // ========================
    // 4. 重试不得误用另一题的 OCR 快照
    // ========================

    @Test
    fun retry_reuses_original_request_context_instead_of_current_screen() {
        val h = Harness()
        h.screenText = "题目A：1+1=?"
        // 第一次请求不带 Done → 助手消息落入 Error，形成可重试状态。
        h.enqueue(flowOf(LlmEvent.TextDelta("半截")))
        h.holder.send("第一题")
        settle(h.holder)
        assertEquals(ChatMessageStatus.Error, h.holder.session.value.messages.last().status)

        // 用户此时已经切到 B 题：重试必须复用 A 的原绑定，不得重新读取当前屏幕。
        h.screenText = "题目B：2+2=?"
        h.enqueue(flowOf(LlmEvent.TextDelta("y"), LlmEvent.Done))
        h.holder.retry()
        settle(h.holder)

        assertEquals("重试不得重新读取屏幕", 1, h.injectedTraceIds.size)
        assertEquals(2, h.capturedUserPrompts.size)
        assertTrue("重试请求必须仍使用上一题的 OCR", h.capturedUserPrompts[1].contains("题目A"))
        assertFalse("重试请求不得混入用户已切换到的下一题 OCR", h.capturedUserPrompts[1].contains("题目B"))
        assertTrue("重试只复用原绑定，不得携带问答历史", h.capturedHistory[1].isEmpty())
    }

    // ========================
    // 5. 旧请求的迟到增量不污染新题助手消息
    // ========================

    @Test
    fun late_delta_of_old_request_never_lands_on_new_request_message() {
        val h = Harness()
        h.screenText = "题目A"
        // A 的流：先给一小段，随后延迟很久再给「迟到增量」。
        h.enqueue(
            flow {
                emit(LlmEvent.TextDelta("A1"))
                delay(400)
                emit(LlmEvent.TextDelta("A-LATE"))
                emit(LlmEvent.Done)
            }
        )
        h.holder.send("A题")
        awaitContent(h.holder) { it == "A1" }

        // 用户停止后立即换题：旧请求被取消，其迟到增量不得写入新请求的消息。
        h.holder.stop()
        h.screenText = "题目B"
        h.enqueue(
            flow {
                emit(LlmEvent.TextDelta("B1"))
                delay(600)
                emit(LlmEvent.Done)
            }
        )
        h.holder.send("B题")
        val session = settle(h.holder)

        val messages = session.messages
        val assistantA = messages.first { it.role == ChatRole.Assistant && it.content.startsWith("A") }
        val assistantB = messages.last { it.role == ChatRole.Assistant }

        assertTrue("A 的助手消息只应保留它自己的那段文本", assistantA.content.startsWith("A1"))
        assertFalse("A 的迟到增量不得写进 B 题助手消息", assistantB.content.contains("A-LATE"))
        assertFalse("A 的文本不得写进 B 题助手消息", assistantB.content.contains("A1"))
        assertTrue("B 题助手消息只应包含 B 的答复", assistantB.content.startsWith("B1"))
        assertNotEquals(assistantA.id, assistantB.id)
        assertEquals(2, h.capturedUserPrompts.size)
    }

    // ========================
    // 6. 新题开始后请求状态与消息 ID 相互隔离
    // ========================

    @Test
    fun new_question_request_state_and_message_ids_are_isolated() {
        val h = Harness()
        h.screenText = "题目A"
        h.enqueue(
            flow {
                emit(LlmEvent.TextDelta("A1"))
                awaitCancellation()
            }
        )
        h.holder.send("A题")
        awaitContent(h.holder) { it == "A1" }

        h.holder.stop()
        h.screenText = "题目B"
        h.enqueue(
            flow {
                emit(LlmEvent.TextDelta("B1"))
                awaitCancellation()
            }
        )
        h.holder.send("B题")
        awaitContent(h.holder) { it == "B1" }

        // B 仍在流式中：此时再发送必须被拒绝，不允许开启第二条并发请求。
        h.holder.send("C题")

        val messages = h.holder.session.value.messages
        assertEquals("B 流式期间不得再插入新的用户/助手消息", 4, messages.size)
        assertEquals(1, messages.count { it.status == ChatMessageStatus.Streaming })
        val ids = messages.map { it.id }
        assertEquals("每条消息的 id 必须唯一", ids.size, ids.toSet().size)

        h.holder.stop()
        settle(h.holder)
    }

    // ========================
    // 8/9. 普通聊天完全绕过屏幕读取
    // ========================

    @Test
    fun normal_chat_without_injector_sends_plain_prompt_and_default_system_prompt() {
        val h = Harness(withInjector = false)
        h.screenText = "题目A"
        h.enqueue(flowOf(LlmEvent.Done))

        h.holder.send("普通聊天")
        settle(h.holder)

        assertEquals("普通聊天不得注入任何屏幕上下文", "普通聊天", h.capturedUserPrompts.single())
        assertEquals("普通聊天必须沿用配置的系统提示", "sys", h.capturedSystemPrompts.single())
        assertTrue(h.injectedTraceIds.isEmpty())
        assertTrue("普通聊天（无屏幕上下文）不得携带问答历史", h.capturedHistory.single().isEmpty())
    }

    @Test
    fun realtime_gate_requires_both_mode_and_running_runtime() {
        assertFalse(
            "仅选择实时互动模式但服务未启动时必须按普通聊天处理",
            shouldResolveScreenContext(realTimeMode = false, realTimeRuntimeRunning = true)
        )
        assertFalse(
            "实时服务运行但模式未选中时也必须按普通聊天处理",
            shouldResolveScreenContext(realTimeMode = true, realTimeRuntimeRunning = false)
        )
        assertTrue(shouldResolveScreenContext(realTimeMode = true, realTimeRuntimeRunning = true))
    }

    // ========================
    // 10. 切题后即便仍在同一个可见聊天窗口，也不把旧题历史带进请求
    // ========================

    @Test
    fun switching_question_in_same_visible_chat_window_keeps_old_history_out_of_request() {
        val h = Harness()
        // 同一道题连问两轮：可见聊天历史里已经有 A 的问答记录。
        h.screenText = "题目A：1+1=?\nA.1 B.2 C.3 D.4"
        h.enqueue(flowOf(LlmEvent.TextDelta("A 第一轮答案"), LlmEvent.Done))
        h.holder.send("这题选什么")
        settle(h.holder)

        h.enqueue(flowOf(LlmEvent.TextDelta("A 第二轮答案"), LlmEvent.Done))
        h.holder.send("为什么不是 C")
        settle(h.holder)

        // 切到题目 B —— 仍是同一个会话、同一个可见聊天窗口。
        h.screenText = "题目B：下列哪个是质数？A.4 B.6 C.7 D.9"
        h.enqueue(flowOf(LlmEvent.TextDelta("B 的答案"), LlmEvent.Done))
        h.holder.send("这题怎么解")
        settle(h.holder)

        assertTrue("同题追问那一轮必须带上最近问答历史", h.capturedHistory[1].isNotEmpty())

        val switchedHistory = h.capturedHistory[2]
        assertTrue("切题后发送给模型的历史必须为空", switchedHistory.isEmpty())
        assertFalse("切题请求不得混入 A 的题干", h.capturedUserPrompts[2].contains("题目A"))
        assertFalse("切题请求不得混入 A 的第一轮答案", h.capturedUserPrompts[2].contains("A 第一轮答案"))
        assertFalse("切题请求不得混入 A 的第二轮答案", h.capturedUserPrompts[2].contains("A 第二轮答案"))

        // 可见聊天历史（UI）完整保留，不受上下文隔离影响。
        assertEquals(6, h.holder.session.value.messages.size)
    }

    // ========================
    // 11. 同题判定与历史裁剪的纯函数行为
    // ========================

    @Test
    fun same_question_detection_is_conservative_and_not_based_on_ocr_fingerprint() {
        // 完全一致 → 同题。
        assertTrue(
            sameQuestionContext("题目A：1+1=?\nA.1 B.2 C.3 D.4", "题目A：1+1=?\nA.1 B.2 C.3 D.4")
        )
        // OCR 抖动（多了/少了一行界面文字）但题目行仍多数重叠 → 仍判同题。
        assertTrue(
            sameQuestionContext("题目A：1+1=?\nA.1 B.2 C.3 D.4\n得分:0", "题目A：1+1=?\nA.1 B.2 C.3 D.4")
        )
        // 只有一行相同 → 保守判为不同题（宁可少带历史，也不冒跨题风险）。
        assertFalse(sameQuestionContext("题目A", "题目A\nB.5 C.6"))
        // 完全不同 → 不同题。
        assertFalse(sameQuestionContext("题目A：1+1=?", "题目B：2+2=?"))
        // 任一侧缺失（无读取记录 / 读取失败）→ 无法判定，按新题处理。
        assertFalse(sameQuestionContext(null, "题目A：1+1=?"))
        assertFalse(sameQuestionContext("题目A：1+1=?", null))
    }

    @Test
    fun recent_history_is_limited_and_excludes_streaming_or_blank_messages() {
        val user1 = ChatMessage(id = "u1", role = ChatRole.User, content = "这题选什么")
        val assistant1 = ChatMessage(id = "a1", role = ChatRole.Assistant, content = "答案是 D")
        val user2 = ChatMessage(id = "u2", role = ChatRole.User, content = "为什么不是 C")
        val messages = listOf(user1, assistant1, user2)

        val history = recentHistoryBefore(messages, "u2")
        assertEquals(2, history.size)
        assertEquals(ChatHistoryMessage(ChatRole.User, "这题选什么"), history[0])
        assertEquals(ChatHistoryMessage(ChatRole.Assistant, "答案是 D"), history[1])

        // 首条消息之前没有历史；定位不到当前用户消息时也不携带历史。
        assertTrue(recentHistoryBefore(messages, "u1").isEmpty())
        assertTrue(recentHistoryBefore(messages, null).isEmpty())
        assertTrue(recentHistoryBefore(messages, "不存在").isEmpty())

        // 流式中的消息与空白消息必须被排除。
        val streaming = ChatMessage(
            id = "a2",
            role = ChatRole.Assistant,
            content = "生成中",
            status = ChatMessageStatus.Streaming
        )
        val blank = ChatMessage(id = "u3", role = ChatRole.User, content = "   ")
        val user4 = ChatMessage(id = "u4", role = ChatRole.User, content = "再问一次")
        val trimmed = recentHistoryBefore(listOf(user1, assistant1, streaming, blank, user4), "u4")
        assertEquals(2, trimmed.size)
        assertTrue(trimmed.none { it.content == "生成中" })
        assertTrue(trimmed.none { it.content.isBlank() })
    }

    // ========================
    // 12. R8.3：共享材料的「一拖 N」题号守卫与切题请求隔离
    // ========================

    @Test
    fun same_question_detection_rejects_shared_material_different_question_number() {
        // 资料分析「一拖 N」：材料行完全重叠（行重合 4/6 ≥ 60%，旧逻辑会**误判同题**），
        // 但题号不同 → 必须是不同题。R8.3 题号守卫正是为拦截这种跨题历史污染。
        val a = "第 10/15 题\n材料行一\n材料行二\n材料行三\n材料行四\n问题A：占比是多少"
        val b = "第 11/15 题\n材料行一\n材料行二\n材料行三\n材料行四\n问题B：同比增长多少"
        assertFalse("材料高度重叠但题号不同时必须判为不同题（R8.3）", sameQuestionContext(a, b))

        // 题号相同（同题）时仍按行重合判定 —— 证明守卫只拦「题号矛盾」，不误伤同题滚动。
        assertTrue(
            sameQuestionContext(
                "第 10/15 题\n材料行一\n材料行二\n材料行三\n材料行四\n问题A：占比是多少",
                "第 10/15 题\n材料行一\n材料行二\n材料行三\n材料行四\n问题C：另一种问法"
            )
        )
    }

    @Test
    fun shared_material_question_switch_does_not_carry_old_question_text_or_history() {
        // R8.3 端到端（断言实际送入模型的 userPrompt）：共享材料下从 A 题切到 B 题，
        // B 的请求必须只含当前题 B 的题号 / 问句 / 选项，绝不携带 A 的问句 / 选项 / 助手答案与历史。
        val h = Harness()
        val a = "第 10/15 题\n材料行一\n材料行二\n材料行三\n材料行四\n问题A：占比是多少\nA.10% B.12%"
        val b = "第 11/15 题\n材料行一\n材料行二\n材料行三\n材料行四\n问题B：同比增长多少\nA.5% B.8%"

        h.screenText = a
        h.enqueue(flowOf(LlmEvent.TextDelta("A的答案"), LlmEvent.Done))
        h.holder.send("第一题怎么做")
        settle(h.holder)

        // 用户滚到同一材料的下一题 B（材料行完全相同）。
        h.screenText = b
        h.enqueue(flowOf(LlmEvent.TextDelta("B的答案"), LlmEvent.Done))
        h.holder.send("这道题怎么做")
        settle(h.holder)

        assertEquals(2, h.capturedUserPrompts.size)
        val second = h.capturedUserPrompts[1]
        assertTrue("切题请求必须包含当前题 B 的题号", second.contains("第 11/15 题"))
        assertTrue("切题请求必须包含当前题 B 的问句", second.contains("问题B"))
        assertFalse("共享材料下切题请求不得混入 A 的问句", second.contains("问题A"))
        assertFalse("共享材料下切题请求不得混入 A 的选项", second.contains("A.10%"))
        assertFalse("切题请求不得携带 A 的助手答案", second.contains("A的答案"))
        assertTrue(
            "共享材料切题后历史必须为空（R8.3 题号守卫拦截行重合误判）",
            h.capturedHistory[1].isEmpty()
        )
    }
}
