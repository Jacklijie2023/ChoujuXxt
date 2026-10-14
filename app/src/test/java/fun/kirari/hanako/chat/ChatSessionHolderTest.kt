package `fun`.kirari.hanako.chat

import `fun`.kirari.hanako.data.ChatMessageStatus
import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.data.ChatSession
import `fun`.kirari.hanako.data.ChatSessionsContainer
import `fun`.kirari.hanako.data.ChatSessionPersistence
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.network.LlmEvent
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatSessionHolder] 的纯逻辑单元测试（不联网）。
 *
 * 通过注入伪造的 [LlmEvent] 序列（[ChatStreamSource] 接缝）覆盖：
 * 流式累积、Done 收尾、中途异常、无 Done 直接结束、用户停止、未配置模型、重试。
 */
class ChatSessionHolderTest {

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

    private fun holder(scope: CoroutineScope, vararg streams: Flow<LlmEvent>): ChatSessionHolder {
        val queue = ArrayDeque(streams.toList())
        val source = ChatStreamSource { _, _, _, _, _, _, _ -> queue.removeFirst() }
        return ChatSessionHolder(streamSource = source, configProvider = { config }, scope = scope)
    }

    private fun holderWithoutProvider(scope: CoroutineScope): ChatSessionHolder =
        ChatSessionHolder(
            streamSource = ChatStreamSource { _, _, _, _, _, _, _ -> flowOf() },
            configProvider = { config.copy(provider = null) },
            scope = scope
        )

    /** 等待会话进入「无 Streaming 消息」且流式任务已收尾的稳定态。 */
    private fun settle(holder: ChatSessionHolder): ChatSession = runBlocking {
        withTimeout(5_000) {
            // stateIn 派生流（holder.session）的缓存值可能滞后于 _state 的同步更新，
            // 因此要求连续多次读取到相同结果才算稳定，避免在旧缓存上提前返回。
            var result: ChatSession? = null
            var stable = 0
            var lastStable: ChatSession? = null
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
                        stable = 1
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

    @Test
    fun streaming_deltas_accumulate_into_single_message_and_finish_on_done() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(
            scope,
            flowOf(LlmEvent.TextDelta("Hel"), LlmEvent.TextDelta("lo"), LlmEvent.Done)
        )

        holder.send("hi")
        val session = settle(holder)

        assertEquals(2, session.messages.size)
        assertEquals(ChatRole.User, session.messages[0].role)
        assertEquals("hi", session.messages[0].content)
        assertEquals(ChatRole.Assistant, session.messages[1].role)
        assertEquals("Hello", session.messages[1].content)
        assertEquals(ChatMessageStatus.Done, session.messages[1].status)
        assertFalse(session.isStreaming)
    }

    @Test
    fun stream_without_done_marks_message_incomplete() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(scope, flowOf(LlmEvent.TextDelta("abc")))

        holder.send("hi")
        val session = settle(holder)

        val assistant = session.messages.last()
        assertEquals(ChatRole.Assistant, assistant.role)
        assertEquals("abc", assistant.content)
        assertEquals(ChatMessageStatus.Error, assistant.status)
    }

    @Test
    fun mid_stream_exception_marks_error_and_keeps_partial_text() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(
            scope,
            flow {
                emit(LlmEvent.TextDelta("ab"))
                throw IllegalStateException("connection reset")
            }
        )

        holder.send("hi")
        val session = settle(holder)

        assertEquals(2, session.messages.size)
        val assistant = session.messages.last()
        assertEquals(ChatRole.Assistant, assistant.role)
        assertEquals("ab", assistant.content)
        assertEquals(ChatMessageStatus.Error, assistant.status)
    }

    @Test
    fun stop_keeps_generated_text() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(
            scope,
            flow {
                emit(LlmEvent.TextDelta("partial"))
                awaitCancellation()
            }
        )

        holder.send("hi")
        runBlocking {
            withTimeout(5_000) {
                holder.session.first { it.messages.lastOrNull()?.content == "partial" }
            }
        }
        holder.stop()
        val session = settle(holder)

        val assistant = session.messages.last()
        assertEquals("partial", assistant.content)
        assertEquals(ChatMessageStatus.Done, assistant.status)
        assertFalse(session.isStreaming)
    }

    @Test
    fun send_without_text_model_appends_error_message() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holderWithoutProvider(scope)

        holder.send("hi")
        val session = settle(holder)

        assertEquals(2, session.messages.size)
        assertEquals(ChatRole.User, session.messages[0].role)
        assertEquals(ChatRole.Assistant, session.messages[1].role)
        assertEquals(ChatMessageStatus.Error, session.messages[1].status)
        assertTrue(session.messages[1].content.isNotBlank())
    }

    @Test
    fun retry_replaces_failed_assistant_message_without_duplicating_user_message() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(
            scope,
            flowOf(LlmEvent.TextDelta("x")),
            flowOf(LlmEvent.TextDelta("y"), LlmEvent.Done)
        )

        holder.send("hi")
        var session = settle(holder)
        assertEquals(ChatMessageStatus.Error, session.messages.last().status)

        holder.retry()
        session = settle(holder)

        assertEquals(2, session.messages.size)
        assertEquals(1, session.messages.count { it.role == ChatRole.User })
        assertEquals("y", session.messages.last().content)
        assertEquals(ChatMessageStatus.Done, session.messages.last().status)
    }

    // ========================
    // M1：多会话管理
    // ========================

    /** 等待会话列表满足条件（stateIn 派生为异步，需等待发射）。 */
    private fun awaitSessions(holder: ChatSessionHolder, predicate: (List<`fun`.kirari.hanako.data.ChatSession>) -> Boolean): List<`fun`.kirari.hanako.data.ChatSession> =
        runBlocking {
            withTimeout(5_000) {
                holder.sessions.first { predicate(it) }
            }
        }

    /** 等待当前会话满足条件。 */
    private fun awaitSession(holder: ChatSessionHolder, predicate: (ChatSession) -> Boolean): ChatSession =
        runBlocking {
            withTimeout(5_000) {
                holder.session.first { predicate(it) }
            }
        }

    @Test
    fun new_session_creates_new_empty_session_and_switches() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(scope, flowOf(LlmEvent.Done))

        holder.send("first")
        settle(holder)
        val firstId = holder.session.value.id
        assertEquals(1, holder.sessions.value.size)

        holder.newSession()

        val sessions = awaitSessions(holder) { it.size == 2 }
        assertEquals(firstId, sessions.first().id)
        val current = awaitSession(holder) { it.id != firstId }
        assertTrue(current.messages.isEmpty())
    }

    @Test
    fun select_session_switches_current_session() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(
            scope,
            flowOf(LlmEvent.Done),
            flowOf(LlmEvent.Done)
        )

        holder.send("q1")
        settle(holder)
        holder.newSession()
        val firstId = holder.sessions.value.first().id
        holder.send("q2")
        settle(holder)
        val secondId = holder.session.value.id
        assertEquals("q2", holder.session.value.messages.first().content)

        holder.selectSession(firstId)

        val switched = awaitSession(holder) { it.id == firstId }
        assertEquals("q1", switched.messages.first().content)

        // 切换后发送不影响其他会话
        holder.send("q1b")
        settle(holder)
        val second = awaitSessions(holder) { it.any { s -> s.id == secondId } }
            .first { it.id == secondId }
        assertEquals("q2", second.messages.first().content)
    }

    @Test
    fun delete_session_falls_back_to_remaining_or_creates_empty() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(scope, flowOf(LlmEvent.Done))

        holder.send("only")
        settle(holder)
        val onlyId = holder.session.value.id

        holder.deleteSession(onlyId)
        awaitSessions(holder) { it.size == 1 }
        val current = awaitSession(holder) { it.messages.isEmpty() }
        assertTrue(current.messages.isEmpty())

        // 两个会话时删除非当前会话，当前会话内容保留
        holder.send("a")
        settle(holder)
        holder.newSession()
        val sessions2 = awaitSessions(holder) { it.size == 2 }
        val aSessionId = sessions2.first { it.messages.any { m -> m.content == "a" } }.id
        holder.selectSession(aSessionId)
        awaitSession(holder) { it.id == aSessionId }
        val otherId = sessions2.first { it.id != aSessionId }.id
        holder.deleteSession(otherId)

        awaitSessions(holder) { it.size == 1 }
        val remaining = awaitSession(holder) { it.id == aSessionId }
        assertEquals("a", remaining.messages.first().content)
    }

    @Test
    fun clear_current_session_keeps_metadata() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(scope, flowOf(LlmEvent.Done))
        val providerB = provider.copy(id = "provider-b", name = "Provider B", chatModel = "b-model")

        holder.selectModel(providerB.id, "b-model")
        holder.send("question")
        settle(holder)
        assertTrue(holder.session.value.messages.size == 2)

        holder.clearCurrentSession()

        val cleared = awaitSession(holder) { it.messages.isEmpty() }
        assertEquals(providerB.id, cleared.selectedProviderId)
        assertEquals("b-model", cleared.selectedModel)
        assertEquals("question", cleared.title)
    }

    @Test
    fun select_model_overrides_provider_and_model_for_stream() {
        val scope = CoroutineScope(Dispatchers.Default)
        val providerB = provider.copy(id = "provider-b", name = "Provider B", chatModel = "b-model")
        var capturedProvider: ModelProviderConfig? = null
        var capturedModel: String? = null
        val holder = ChatSessionHolder(
            streamSource = ChatStreamSource { p, m, _, _, _, _, _ ->
                capturedProvider = p
                capturedModel = m
                flowOf(LlmEvent.Done)
            },
            configProvider = { config.copy(providers = listOf(provider, providerB)) },
            scope = scope
        )

        holder.selectModel(providerB.id, "b-model")
        holder.send("hi")
        settle(holder)

        assertEquals("provider-b", capturedProvider?.id)
        assertEquals("b-model", capturedModel)
        assertEquals("b-model", holder.session.value.selectedModel)
    }

    @Test
    fun select_model_blank_falls_back_to_default_text_model() {
        val scope = CoroutineScope(Dispatchers.Default)
        var capturedProvider: ModelProviderConfig? = null
        var capturedModel: String? = null
        val holder = ChatSessionHolder(
            streamSource = ChatStreamSource { p, m, _, _, _, _, _ ->
                capturedProvider = p
                capturedModel = m
                flowOf(LlmEvent.Done)
            },
            configProvider = { config.copy(providers = listOf(provider)) },
            scope = scope
        )

        holder.send("hi")
        settle(holder)

        assertEquals(provider.id, capturedProvider?.id)
        assertEquals("test-model", capturedModel)
    }

    // ========================
    // S1：会话重命名 + 本地搜索
    // ========================

    /** 内存版持久化接缝，记录最后一次落盘内容。 */
    private class FakePersistence : ChatSessionPersistence {
        var saved: ChatSessionsContainer? = null
            private set
        override suspend fun load(): ChatSessionsContainer = saved ?: ChatSessionsContainer()
        override suspend fun save(container: ChatSessionsContainer) {
            saved = container
        }
    }

    private fun holderWithPersistence(
        scope: CoroutineScope,
        persistence: ChatSessionPersistence,
        vararg streams: Flow<LlmEvent>
    ): ChatSessionHolder {
        val queue = ArrayDeque(streams.toList())
        val source = ChatStreamSource { _, _, _, _, _, _, _ -> queue.removeFirst() }
        return ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = scope,
            persistence = persistence
        )
    }

    @Test
    fun rename_session_updates_title_and_persists() {
        val scope = CoroutineScope(Dispatchers.Default)
        val persistence = FakePersistence()
        val holder = holderWithPersistence(scope, persistence, flowOf(LlmEvent.Done))

        holder.send("hello")
        settle(holder)
        val id = holder.session.value.id

        holder.renameSession(id, "重要讨论")

        val renamed = awaitSession(holder) { it.title == "重要讨论" }
        assertEquals("重要讨论", renamed.title)
        val persisted = runBlocking { persistence.load() }
        assertEquals("重要讨论", persisted.sessions.first { it.id == id }.title)
    }

    @Test
    fun rename_session_with_blank_title_ignored() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(scope, flowOf(LlmEvent.Done))

        holder.send("标题保持不变")
        settle(holder)
        val id = holder.session.value.id
        val originalTitle = holder.session.value.title

        holder.renameSession(id, "   ")
        holder.renameSession(id, "")

        val unchanged = awaitSession(holder) { it.id == id }
        assertEquals(originalTitle, unchanged.title)
    }

    @Test
    fun search_sessions_filters_by_title_case_insensitive() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(scope, flowOf(LlmEvent.Done))

        holder.send("Alpha 会话")
        settle(holder)
        val alphaId = holder.session.value.id
        holder.newSession()
        awaitSessions(holder) { it.size == 2 }
        holder.send("Beta 会话")
        settle(holder)

        assertEquals(2, holder.searchSessions("").size)
        assertEquals(listOf(alphaId), holder.searchSessions("alpha").map { it.id })
        assertEquals(listOf(alphaId), holder.searchSessions("ALPHA").map { it.id })
        assertTrue(holder.searchSessions("不存在").isEmpty())
    }

    // ========================
    // S2：历史时间分组
    // ========================

    @Test
    fun split_sessions_by_time_groups_today_and_earlier() {
        val now = 1_700_000_000_000L // 固定瞬时，避免真实时钟影响
        val today = ChatSession(title = "today", createdAtMillis = now)
        val earlier = ChatSession(title = "earlier", createdAtMillis = now - 2 * 24 * 3600_000L)

        val (todayList, earlierList) = splitSessionsByTime(listOf(earlier, today), nowMillis = now)

        assertEquals(listOf("today"), todayList.map { it.title })
        assertEquals(listOf("earlier"), earlierList.map { it.title })
    }

    @Test
    fun split_sessions_by_time_orders_newest_first_within_group() {
        val now = 1_700_000_000_000L
        val older = ChatSession(title = "older", createdAtMillis = now - 3_600_000L)
        val newest = ChatSession(title = "newest", createdAtMillis = now)

        val (todayList, earlierList) = splitSessionsByTime(listOf(older, newest), nowMillis = now)

        assertEquals(listOf("newest", "older"), todayList.map { it.title })
        assertTrue(earlierList.isEmpty())
    }

    // ========================
    // S3：置顶 + 批量删除
    // ========================

    /** 轮询等待持久化内容满足条件（落盘走 scope.launch 异步，需等待发射）。 */
    private fun awaitPersisted(
        persistence: FakePersistence,
        predicate: (ChatSessionsContainer) -> Boolean
    ): ChatSessionsContainer = runBlocking {
        withTimeout(5_000) {
            var last = persistence.load()
            while (!predicate(last)) {
                delay(10)
                last = persistence.load()
            }
            last
        }
    }

    @Test
    fun toggle_pin_toggles_state_and_persists() {
        val scope = CoroutineScope(Dispatchers.Default)
        val persistence = FakePersistence()
        val holder = holderWithPersistence(scope, persistence, flowOf(LlmEvent.Done))

        holder.send("置顶测试")
        settle(holder)
        val id = holder.session.value.id

        holder.togglePin(id)
        val pinnedSession = awaitSession(holder) { it.pinned }
        assertTrue(pinnedSession.pinned)
        val persistedPin = awaitPersisted(persistence) { container ->
            container.sessions.firstOrNull { it.id == id }?.pinned == true
        }
        assertTrue(persistedPin.sessions.first { it.id == id }.pinned)

        holder.togglePin(id)
        awaitSession(holder) { !it.pinned }
        val persistedUnpin = awaitPersisted(persistence) { container ->
            container.sessions.firstOrNull { it.id == id }?.pinned == false
        }
        assertFalse(persistedUnpin.sessions.first { it.id == id }.pinned)
    }

    @Test
    fun delete_sessions_batch_removes_selected_and_falls_back_current() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(
            scope,
            flowOf(LlmEvent.Done),
            flowOf(LlmEvent.Done),
            flowOf(LlmEvent.Done)
        )

        holder.send("会话A")
        settle(holder)
        holder.newSession()
        awaitSessions(holder) { it.size == 2 }
        holder.send("会话B")
        settle(holder)
        holder.newSession()
        awaitSessions(holder) { it.size == 3 }
        holder.send("会话C")
        settle(holder)

        val currentId = holder.session.value.id
        val otherId = holder.sessions.value.first { it.id != currentId }.id
        val keepId = holder.sessions.value.first { it.id != currentId && it.id != otherId }.id

        // 批量删除「非当前 + 当前」会话，当前会话回退到剩余会话
        holder.deleteSessions(listOf(otherId, currentId))

        val remaining = awaitSessions(holder) { it.size == 1 }
        assertEquals(listOf(keepId), remaining.map { it.id })
        val current = awaitSession(holder) { it.id == keepId }
        assertEquals(keepId, current.id)
    }

    @Test
    fun delete_sessions_all_creates_new_empty_session() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(scope, flowOf(LlmEvent.Done))

        holder.send("唯一会话")
        settle(holder)
        val id = holder.session.value.id

        holder.deleteSessions(listOf(id))

        val sessions = awaitSessions(holder) { it.size == 1 && it.first().id != id }
        assertTrue(sessions.first().messages.isEmpty())
    }

    @Test
    fun delete_sessions_with_empty_ids_is_ignored() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(scope, flowOf(LlmEvent.Done))

        holder.send("保留会话")
        settle(holder)
        val id = holder.session.value.id

        holder.deleteSessions(emptyList())

        val sessions = awaitSessions(holder) { it.size == 1 }
        assertEquals(id, sessions.first().id)
    }

    // ========================
    // S3 增补：新建对话不产生空历史记录
    // ========================

    @Test
    fun new_session_on_empty_current_does_not_create_or_switch() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(scope, flowOf(LlmEvent.Done))
        // 等待 sessions 流收敛到归一化的空会话（stateIn 占位符可能尚未替换）。
        val initialId = runBlocking {
            withTimeout(5_000) { holder.sessions.first { it.isNotEmpty() } }
        }.first().id

        // 首次使用/清空后：当前为空会话，反复点击「新建对话」不产生新记录
        holder.newSession()
        holder.newSession()

        val sessions = awaitSessions(holder) { it.size == 1 }
        assertEquals(1, sessions.size)
        assertEquals(initialId, sessions.first().id)
    }

    @Test
    fun new_session_after_use_creates_one_and_repeated_click_keeps_single() {
        val scope = CoroutineScope(Dispatchers.Default)
        val holder = holder(scope, flowOf(LlmEvent.Done))

        // 使用后（有消息）才允许产生新会话
        holder.send("已使用")
        settle(holder)
        val usedId = holder.session.value.id

        holder.newSession()
        val afterFirst = awaitSessions(holder) { it.size == 2 }
        assertEquals(listOf(usedId), afterFirst.filter { it.messages.isNotEmpty() }.map { it.id })

        // 新会话仍是空会话时，再次点击不再创建
        holder.newSession()
        val afterSecond = awaitSessions(holder) { it.size == 2 }
        assertEquals(2, afterSecond.size)
        assertEquals(1, afterSecond.count { it.messages.isNotEmpty() })
    }
}