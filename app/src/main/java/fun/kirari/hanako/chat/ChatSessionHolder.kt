package `fun`.kirari.hanako.chat

import `fun`.kirari.hanako.data.ChatMessage
import `fun`.kirari.hanako.data.ChatMessageStatus
import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.data.ChatSession
import `fun`.kirari.hanako.data.ChatSessionsContainer
import `fun`.kirari.hanako.data.ChatSessionPersistence
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.network.LlmEvent
import `fun`.kirari.hanako.network.UnifiedLLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

/** 一次聊天请求所需的运行时配置（由 `settings` 派生，见 `AppContainer`）。 */
internal data class ChatConfig(
    val provider: ModelProviderConfig?,
    val model: String,
    val systemPrompt: String,
    val trustAllHttpsCertificates: Boolean,
    val providers: List<ModelProviderConfig> = emptyList()
)

/**
 * 可注入的流式事件来源接缝。
 *
 * 生产实现走 [UnifiedLLMClient]；单元测试可注入伪造的 [LlmEvent] 序列，无需联网。
 */
internal fun interface ChatStreamSource {
    suspend fun stream(
        provider: ModelProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean
    ): Flow<LlmEvent>
}

/** 复用项目真实网络栈的默认实现。 */
internal class UnifiedChatStreamSource(
    private val client: UnifiedLLMClient
) : ChatStreamSource {
    override suspend fun stream(
        provider: ModelProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean
    ): Flow<LlmEvent> = client.stream(
        provider = provider,
        model = model,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        imagesBase64 = emptyList(),
        tools = null,
        firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
        trustAllHttpsCertificates = trustAllHttpsCertificates
    )
}

/**
 * 全局共享的聊天会话状态持有者（由 `AppContainer` 注入的单例）。
 *
 * 设计要点：
 * - 多会话：持有「会话列表 + 当前会话 id」，`session` 语义为「当前会话」。
 * - 会话元数据（id/title/createdAtMillis/selectedProviderId/selectedModel）落在 [ChatSession] 上，
 *   模型选择按会话生效；未选时回退到 settings 默认文本模型。
 * - 可选持久化（[ChatSessionPersistence] 接缝，生产走 DataStore + JSON，独立于 AppSettings）。
 * - 流式增量以不可变方式累积；完成判定只认 [LlmEvent.Done]；流结束未收到 Done 标记 [ChatMessageStatus.Error]。
 * - 流式增量按发起会话 id 归位：流式进行中切换会话不会把增量写错会话。
 */
internal class ChatSessionHolder(
    private val streamSource: ChatStreamSource,
    private val configProvider: () -> ChatConfig,
    private val scope: CoroutineScope,
    private val persistence: ChatSessionPersistence? = null,
    private val firstDeltaTimeoutMillis: Long = DEFAULT_FIRST_DELTA_TIMEOUT_MILLIS
) {
    // 初始即归一化：老用户无历史时也保证存在一个可写的空会话。
    private val _state = MutableStateFlow(normalize(ChatSessionsContainer()))

    /** 当前会话（流式/发送/停止作用于当前会话）。 */
    val session: StateFlow<ChatSession> = _state
        .map { it.currentSession() }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ChatSession())

    /** 全部会话（用于历史列表），按创建顺序排列。 */
    val sessions: StateFlow<List<ChatSession>> = _state
        .map { it.sessions }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    private var activeJob: Job? = null

    init {
        // 启动时恢复持久化的会话列表（老用户无历史时归一化为一个空会话）。
        persistence?.let { store ->
            scope.launch {
                val loaded = store.load()
                if (loaded.sessions.isNotEmpty() || loaded.currentSessionId != null) {
                    _state.value = normalize(loaded)
                }
            }
        }
    }

    /** 发送一条用户消息并开始流式回复。 */
    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || isBusy()) return
        val sessionId = _state.value.currentSession().id
        updateState { container ->
            container.updateSession(sessionId) { session ->
                // 会话尚无有效标题时，以第一条用户消息为标题
                session.copy(
                    title = if (session.title.isBlank() || session.title == ChatSession.DEFAULT_TITLE) {
                        prompt.take(18)
                    } else {
                        session.title
                    },
                    messages = session.messages + ChatMessage(
                        role = ChatRole.User,
                        content = prompt,
                        status = ChatMessageStatus.Done
                    )
                )
            }
        }
        startStream(sessionId, prompt)
    }

    /** 重新请求上一条用户消息（用于错误态重试），不会重复追加用户气泡。 */
    fun retry() {
        if (isBusy()) return
        val container = _state.value
        val session = container.currentSession()
        val lastUser = session.messages.lastOrNull { it.role == ChatRole.User } ?: return
        val sessionId = session.id
        updateState { state ->
            state.updateSession(sessionId) { s ->
                val messages = s.messages.toMutableList()
                while (messages.isNotEmpty() &&
                    messages.last().role == ChatRole.Assistant &&
                    messages.last().status != ChatMessageStatus.Done
                ) {
                    messages.removeAt(messages.lastIndex)
                }
                s.copy(messages = messages)
            }
        }
        startStream(sessionId, lastUser.content)
    }

    /** 停止当前流式回复，保留已生成文本。 */
    fun stop() {
        val job = activeJob
        activeJob = null
        finishStreaming(ChatMessageStatus.Done)
        job?.cancel()
    }

    // ========================
    // 多会话管理
    // ========================

    /**
     * 新建会话并切换到新会话。
     *
     * 当前会话仍是空会话（尚未使用）时不重复创建，避免反复点击「新建对话」
     * 在会话历史中堆积大量空记录；仅当用户真正发送过消息后才产生新会话。
     */
    fun newSession() {
        if (isBusy()) return
        updateState { container ->
            val current = container.currentSession()
            if (current.messages.isEmpty()) {
                container
            } else {
                val newSession = ChatSession()
                container.copy(
                    sessions = container.sessions + newSession,
                    currentSessionId = newSession.id
                )
            }
        }
    }

    /** 切换到指定会话（id 不存在时忽略）。 */
    fun selectSession(id: String) {
        if (isBusy()) return
        updateState { container ->
            if (container.sessions.any { it.id == id }) {
                container.copy(currentSessionId = id)
            } else {
                container
            }
        }
    }

    /** 删除指定会话；删除当前会话时回退到剩余会话或新建空会话。 */
    fun deleteSession(id: String) {
        if (isBusy()) return
        updateState { container ->
            val remaining = container.sessions.filterNot { it.id == id }
            if (remaining.isEmpty()) {
                val empty = ChatSession()
                ChatSessionsContainer(
                    sessions = listOf(empty),
                    currentSessionId = empty.id
                )
            } else {
                val current = container.currentSession()
                container.copy(
                    sessions = remaining,
                    currentSessionId = if (current.id == id) remaining.first().id else container.currentSessionId
                )
            }
        }
    }

    /** 清空当前会话的全部消息，保留会话元数据（标题/模型选择）。 */
    fun clearCurrentSession() {
        if (isBusy()) return
        val sessionId = _state.value.currentSession().id
        updateState { container ->
            container.updateSession(sessionId) { it.copy(messages = emptyList()) }
        }
    }

    /**
     * 为当前会话选择文本模型。
     *
     * 仅记录 providerId/model，不校验 model 是否属于 provider（由 UI 弹窗保证）。
     * 空 providerId 表示回退到默认文本模型。
     */
    fun selectModel(providerId: String?, model: String) {
        val sessionId = _state.value.currentSession().id
        updateState { container ->
            container.updateSession(sessionId) { it.copy(selectedProviderId = providerId, selectedModel = model) }
        }
    }

    /**
     * 重命名指定会话（空白标题忽略）。
     *
     * 走既有 [updateState]，经 [ChatSessionPersistence] 持久化到独立存储文件，
     * 不并入 AppSettings。
     */
    fun renameSession(id: String, newTitle: String) {
        if (isBusy()) return
        val title = newTitle.trim()
        if (title.isEmpty()) return
        updateState { container ->
            container.updateSession(id) { it.copy(title = title) }
        }
    }

    /** 会话列表本地搜索：按标题过滤（忽略大小写）；空查询返回全量。 */
    fun searchSessions(query: String): List<ChatSession> {
        val q = query.trim()
        if (q.isEmpty()) return _state.value.sessions
        return _state.value.sessions.filter { it.title.contains(q, ignoreCase = true) }
    }

    /** 切换指定会话的置顶状态（持久化）。 */
    fun togglePin(id: String) {
        if (isBusy()) return
        updateState { container ->
            container.updateSession(id) { it.copy(pinned = !it.pinned) }
        }
    }

    /** 批量删除会话（多选模式用）；删除当前会话时回退到剩余会话或新建空会话。 */
    fun deleteSessions(ids: List<String>) {
        if (isBusy() || ids.isEmpty()) return
        updateState { container ->
            val remaining = container.sessions.filterNot { it.id in ids }
            if (remaining.isEmpty()) {
                val empty = ChatSession()
                ChatSessionsContainer(sessions = listOf(empty), currentSessionId = empty.id)
            } else {
                val current = container.currentSession()
                container.copy(
                    sessions = remaining,
                    currentSessionId = if (current.id in ids) remaining.first().id else container.currentSessionId
                )
            }
        }
    }

    // ========================
    // 内部
    // ========================

    private fun isBusy(): Boolean = activeJob?.isActive == true

    /** 供单元测试等待流式任务真正收尾（避免 activeJob 未置空导致的时序竞态）。 */
    internal fun isIdleForTest(): Boolean = !isBusy()

    private fun startStream(sessionId: String, prompt: String) {
        val config = configProvider()
        val session = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        val provider = session.selectedProviderId
            ?.let { id -> config.providers.firstOrNull { it.id == id } }
            ?: config.provider
        val model = session.selectedModel.ifBlank { config.model }
        val assistantId = UUID.randomUUID().toString()

        if (provider == null || model.isBlank()) {
            updateState { container ->
                container.updateSession(sessionId) { s ->
                    s.copy(
                        messages = s.messages + ChatMessage(
                            id = assistantId,
                            role = ChatRole.Assistant,
                            content = "尚未配置文本模型，请先在「设置 → 模型设置」中完成配置。",
                            status = ChatMessageStatus.Error
                        )
                    )
                }
            }
            return
        }

        updateState { container ->
            container.updateSession(sessionId) { s ->
                s.copy(
                    messages = s.messages + ChatMessage(
                        id = assistantId,
                        role = ChatRole.Assistant,
                        content = "",
                        status = ChatMessageStatus.Streaming
                    )
                )
            }
        }

        activeJob = scope.launch {
            var receivedDone = false
            try {
                streamSource.stream(
                    provider = provider,
                    model = model,
                    systemPrompt = config.systemPrompt,
                    userPrompt = prompt,
                    firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                    trustAllHttpsCertificates = config.trustAllHttpsCertificates
                ).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> appendDelta(sessionId, assistantId, event.text)
                        is LlmEvent.Done -> {
                            receivedDone = true
                            finish(sessionId, assistantId, ChatMessageStatus.Done)
                        }
                        // M1 不实现工具调用 UI，忽略工具事件（不影响文本流式）。
                        is LlmEvent.ToolCall -> Unit
                    }
                }
                if (!receivedDone) finish(sessionId, assistantId, ChatMessageStatus.Error)
            } catch (cancellation: CancellationException) {
                finish(sessionId, assistantId, ChatMessageStatus.Done)
                throw cancellation
            } catch (error: Throwable) {
                finish(sessionId, assistantId, ChatMessageStatus.Error)
            } finally {
                activeJob = null
            }
        }
    }

    private fun appendDelta(sessionId: String, id: String, delta: String) {
        if (delta.isEmpty()) return
        updateState { container ->
            container.updateSession(sessionId) { session ->
                session.copy(
                    messages = session.messages.map { message ->
                        if (message.id == id) message.copy(content = message.content + delta) else message
                    }
                )
            }
        }
    }

    /** 仅将仍处于 [ChatMessageStatus.Streaming] 的目标消息落定，避免覆盖已完成的终态。 */
    private fun finish(sessionId: String, id: String, status: ChatMessageStatus) {
        updateState { container ->
            container.updateSession(sessionId) { session ->
                session.copy(
                    messages = session.messages.map { message ->
                        if (message.id == id && message.status == ChatMessageStatus.Streaming) {
                            message.copy(status = status)
                        } else {
                            message
                        }
                    }
                )
            }
        }
    }

    private fun finishStreaming(status: ChatMessageStatus) {
        updateState { container ->
            container.updateCurrentSession { session ->
                session.copy(
                    messages = session.messages.map { message ->
                        if (message.status == ChatMessageStatus.Streaming) message.copy(status = status) else message
                    }
                )
            }
        }
    }

    /** 保证容器始终非空且 currentSessionId 有效。 */
    private fun normalize(container: ChatSessionsContainer): ChatSessionsContainer {
        if (container.sessions.isEmpty()) {
            val empty = ChatSession()
            return ChatSessionsContainer(sessions = listOf(empty), currentSessionId = empty.id)
        }
        if (container.currentSessionId == null ||
            container.sessions.none { it.id == container.currentSessionId }
        ) {
            return container.copy(currentSessionId = container.sessions.first().id)
        }
        return container
    }

    /** 每次状态变更后异步落盘（先归一化，保证当前会话始终有效可写）。 */
    private fun updateState(transform: (ChatSessionsContainer) -> ChatSessionsContainer) {
        _state.update { container -> normalize(transform(normalize(container))) }
        persistAsync()
    }

    private fun persistAsync() {
        persistence?.let { store ->
            scope.launch {
                runCatching { store.save(_state.value) }
            }
        }
    }

    companion object {
        const val DEFAULT_FIRST_DELTA_TIMEOUT_MILLIS = 20_000L
    }
}

/**
 * 历史抽屉时间分组（纯函数，便于单元测试）。
 *
 * 以本地时区的「今天零点」为界，将会话分为（今天，更早）两组，
 * 各组内按创建时间倒序（最新在前）。
 */
internal fun splitSessionsByTime(
    sessions: List<ChatSession>,
    nowMillis: Long = System.currentTimeMillis()
): Pair<List<ChatSession>, List<ChatSession>> {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayStartMillis = calendar.timeInMillis
    val sorted = { list: List<ChatSession> -> list.sortedByDescending { it.createdAtMillis } }
    return Pair(
        sorted(sessions.filter { it.createdAtMillis >= todayStartMillis }),
        sorted(sessions.filter { it.createdAtMillis < todayStartMillis })
    )
}

private fun ChatSessionsContainer.currentSession(): ChatSession =
    sessions.firstOrNull { it.id == currentSessionId } ?: sessions.firstOrNull() ?: ChatSession()

private fun ChatSessionsContainer.updateSession(
    sessionId: String,
    transform: (ChatSession) -> ChatSession
): ChatSessionsContainer = copy(
    sessions = sessions.map { if (it.id == sessionId) transform(it) else it }
)

private fun ChatSessionsContainer.updateCurrentSession(
    transform: (ChatSession) -> ChatSession
): ChatSessionsContainer {
    val currentId = currentSession()
    return updateSession(currentId.id, transform)
}
