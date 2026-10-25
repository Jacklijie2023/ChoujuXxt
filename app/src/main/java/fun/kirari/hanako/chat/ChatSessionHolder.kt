package `fun`.kirari.hanako.chat

import `fun`.kirari.hanako.data.ChatMessage
import `fun`.kirari.hanako.data.ChatMessageStatus
import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.data.ChatSession
import `fun`.kirari.hanako.data.ChatSessionsContainer
import `fun`.kirari.hanako.data.ChatSessionPersistence
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.evidence.ContextImageRef
import `fun`.kirari.hanako.evidence.ImageAssetRecord
import `fun`.kirari.hanako.evidence.RequestTraceRecord
import `fun`.kirari.hanako.evidence.VisionCapability
import `fun`.kirari.hanako.evidence.planVisionImages
import `fun`.kirari.hanako.evidence.visionCapabilityOf
import `fun`.kirari.hanako.network.ChatHistoryMessage
import `fun`.kirari.hanako.network.LlmEvent
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.screencontext.ScreenReadReport
import `fun`.kirari.hanako.screencontext.ocrFingerprintOf
import `fun`.kirari.hanako.screencontext.ScreenReadStatus
import `fun`.kirari.hanako.screencontext.questionNumberOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 *
 * [traceId] 为本次发送的请求追踪 ID（助手消息 id），用于把「用户发送 → 屏幕读取 → 注入 → 模型请求」
 * 关联成同一次请求；[recentHistory] 仅在**同题追问**时非空，见 [recentHistoryBefore]。
 */
internal fun interface ChatStreamSource {
    suspend fun stream(
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
        recentHistory: List<ChatHistoryMessage>
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
        imagesBase64: List<String>,
        webSearch: Boolean,
        thinking: Boolean,
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean,
        traceId: String?,
        recentHistory: List<ChatHistoryMessage>
    ): Flow<LlmEvent> = client.stream(
        provider = provider,
        model = model,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        imagesBase64 = imagesBase64,
        tools = null,
        webSearch = webSearch,
        thinking = thinking,
        firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
        trustAllHttpsCertificates = trustAllHttpsCertificates,
        traceId = traceId,
        recentHistory = recentHistory
    )
}

/**
 * 把「当前屏幕上下文」与「用户指令」拼成最终送入模型的用户消息（纯函数，便于单元测试）。
 *
 * 两段之间用显式边界分隔，使模型能明确区分「屏幕上的题目」与「用户本条指令」；
 * [contextText] 为空白时**原样返回用户指令**，不注入任何边界（普通聊天不受影响）。
 */
internal fun buildInjectedPrompt(userInstruction: String, contextText: String): String {
    if (contextText.isBlank()) return userInstruction
    return "<屏幕上下文>\n$contextText\n</屏幕上下文>\n\n用户指令：$userInstruction"
}

/**
 * 判定两次请求的「题目上下文」是否为同一道题（纯函数，便于单元测试）。
 *
 * 原则：**宁可少带历史，也不冒跨题风险**。
 * - 任一侧缺失（无读取记录 / 读取失败）→ 无法判定，按新题处理（false）；
 * - **题号隔离（R8.3）**：两侧都能解析出题号且**不同** → 已换题，直接判 false。
 *   这是共享材料题（资料分析「一拖 N」）下唯一可靠的区别信号 —— 材料相同、题号不同，
 *   仅靠行重合会把两道不同的题误判为同题，从而把旧题问答历史带进新题；
 * - 按非空行比对，重合行数占较大一侧的 **60%** 以上才判为同题，容忍 OCR 抖动带来的
 *   界面文字增减，但不把「只有一行相同」的明显不同题误判为同题。
 *
 * 判定**不依赖 OCR 指纹**：指纹对 OCR 抖动极其敏感，会把同一道题的两次抖动判成不同题。
 */
internal fun sameQuestionContext(previous: String?, current: String?): Boolean {
    if (previous == null || current == null) return false
    val prevQuestion = questionNumberOf(previous)
    val curQuestion = questionNumberOf(current)
    if (prevQuestion != null && curQuestion != null && prevQuestion != curQuestion) return false
    val prevLines = previous.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val curLines = current.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (prevLines.isEmpty() || curLines.isEmpty()) return false
    val overlap = prevLines.count { it in curLines }
    val larger = maxOf(prevLines.size, curLines.size)
    return overlap * 10 >= larger * 6
}

/**
 * 取「当前用户消息之前」最近必要的问答历史（纯函数，便于单元测试）。
 *
 * - 定位不到当前用户消息（[currentUserId] 为空 / 不存在）或它已是首条消息 → 无历史；
 * - 流式中的消息与空白消息一律排除，避免把「尚未生成完的助手回复」当成既成事实带入下一轮。
 */
internal fun recentHistoryBefore(
    messages: List<ChatMessage>,
    currentUserId: String?
): List<ChatHistoryMessage> {
    if (currentUserId == null) return emptyList()
    val index = messages.indexOfFirst { it.id == currentUserId }
    if (index <= 0) return emptyList()
    return messages.subList(0, index)
        .filter { it.status != ChatMessageStatus.Streaming && it.content.isNotBlank() }
        .map { ChatHistoryMessage(it.role, it.content) }
}

/**
 * 注入了当前屏幕题目时使用的系统提示词。
 *
 * 显式约束「只依据本轮注入的题目作答」，防止模型把对话历史里上一道题的题干 / 选项 / 答案
 * 混进本次回答（跨题污染最典型的错误表现）。
 */
internal const val REALTIME_ANSWER_SYSTEM_PROMPT =
    "你是 Hanako 实时刷题助手。请只依据本轮注入的屏幕题目上下文作答，" +
        "不得把之前任何一道题目的题干、选项或答案混入本次回答；" +
        "若本轮上下文未包含该题所需信息，请明确指出无法确认，而不要凭印象作答。"

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
 * - **题目上下文隔离**：每次请求按需解析当前屏幕上下文；新题不携带上一题的题干 / 答案 / 问答历史，
 *   仅在同题追问时携带最近一组问答（作为独立历史消息，不拼进当前用户指令）。
 */
internal class ChatSessionHolder(
    private val streamSource: ChatStreamSource,
    private val configProvider: () -> ChatConfig,
    private val scope: CoroutineScope,
    private val persistence: ChatSessionPersistence? = null,
    private val firstDeltaTimeoutMillis: Long = DEFAULT_FIRST_DELTA_TIMEOUT_MILLIS,
    /**
     * 屏幕上下文注入接缝（可为 null = 普通聊天）。
     *
     * 入参为本次请求的 traceId（助手消息 id）；返回三态结果见 [ScreenContextResult]。
     */
    private val screenContextInjector: (suspend (String) -> ScreenContextResult)? = null,
    /** 注入结果上报接缝：上下文真正进入最终 prompt 后才上报 [ScreenReadStatus.SEND_INJECTED]。 */
    private val screenContextReporter: ((ScreenReadReport) -> Unit)? = null,
    /** 聊天发送链路诊断日志接缝（非敏感，含 traceId）。 */
    private val traceLogger: ((String) -> Unit)? = null,
    /**
     * 题目图像证据读取接缝：把已选中的图像证据读成模型输入用的 base64（JPEG）。
     *
     * 返回数量必须与入参一一对应；任一读取失败时返回的列表会变短，发送侧据此如实记录
     * 「图像未发送」而不是伪造成功。
     */
    private val contextImageLoader: ((List<ContextImageRef>) -> List<String>)? = null,
    /** 请求追踪写入接缝：记录实际发送的文字指纹、图像 id、视觉能力状态与失败阶段。 */
    private val requestTraceSink: ((RequestTraceRecord) -> Unit)? = null
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

    @Volatile
    private var activeJob: Job? = null

    /** 请求序号：保证「被取消的旧请求收尾」不会清空新请求的活跃标记。 */
    @Volatile
    private var requestSeq: Long = 0L

    /** 上次请求真正绑定的题目上下文（用于同题判定 + 重试复用原绑定）。 */
    @Volatile
    private var boundRequest: BoundRequest? = null

    /** 上一次请求所用的题目文本；仅用于「本轮是否与上一轮同题」的判定。 */
    @Volatile
    private var lastQuestionText: String? = null

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

    /**
     * 发送一条用户消息（可附带图片 base64）并开始流式回复。
     *
     * @param imagePaths 本次发送图片的本地副本路径（仅用于气泡回显，不参与模型上下文）。
     * @param contextText 调用方显式提供的当前页面上下文（分析页「继续追问」/迷你窗读取当前处理页内容）。
     *   非空时直接注入本次请求，不再另行读取屏幕；null/blank 时走既有屏幕感知链路。
     */
    fun send(
        text: String,
        imagesBase64: List<String> = emptyList(),
        imagePaths: List<String> = emptyList(),
        contextText: String? = null
    ) {
        val prompt = text.trim()
        // 允许「仅发送图片、不带文字」。
        if ((prompt.isEmpty() && imagesBase64.isEmpty()) || isBusy()) return
        val sessionId = _state.value.currentSession().id
        // 无文字时用占位文案展示在用户气泡，避免出现空气泡（不参与模型上下文，模型只收到 prompt）。
        val display = prompt.ifBlank { ChatMessage.IMAGE_ONLY_PLACEHOLDER }
        // 显式给出用户消息 id：题目上下文与历史裁剪都以它为锚点。
        val userMessageId = UUID.randomUUID().toString()
        updateState { container ->
            container.updateSession(sessionId) { session ->
                // 会话尚无有效标题时，以第一条用户消息为标题
                session.copy(
                    title = if (session.title.isBlank() || session.title == ChatSession.DEFAULT_TITLE) {
                        display.take(18)
                    } else {
                        session.title
                    },
                    messages = session.messages + ChatMessage(
                        id = userMessageId,
                        role = ChatRole.User,
                        content = display,
                        status = ChatMessageStatus.Done,
                        imagePaths = imagePaths
                    )
                )
            }
        }
        logImageSend(userMessageId, imagesBase64.size, imagePaths)
        startStream(sessionId, userMessageId, prompt, imagesBase64, contextText = contextText)
    }

    /** 仅记录图片数量与回显路径数量，便于真机核对气泡回显链路；不记录聊天正文。 */
    private fun logImageSend(userMessageId: String, imageCount: Int, imagePaths: List<String>) {
        AppDebugLogStore.i(
            "chat_send",
            "user_message_id=$userMessageId images=$imageCount imagePaths=${imagePaths.size}"
        )
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
        // 重试必须**复用该条用户消息原先绑定的题目上下文**，不得重新读取当前屏幕
        // （用户可能已经切到下一题，重新读取会把新题 OCR 注入对旧题的重试请求）。
        startStream(
            sessionId = sessionId,
            userMessageId = lastUser.id,
            instruction = lastUser.content,
            imagesBase64 = emptyList(),
            reuseBoundRequest = true
        )
    }

    /** 停止当前流式回复，保留已生成文本。 */
    fun stop() {
        val job = activeJob
        activeJob = null
        finishStreaming(ChatMessageStatus.Done)
        job?.cancel()
    }

    /**
     * 显式重绑定目标应用时的**会话边界通知**（R7）。
     *
     * 丢弃上一题的上下文快照与绑定请求，使下一次请求不可能携带上一题的问答历史 ——
     * 旧题内容不得串入新应用。由 `AppContainer` 在「目标确实发生变化」时调用。
     */
    fun onTargetRebound() {
        if (isBusy()) return
        boundRequest = null
        lastQuestionText = null
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

    /** 切换当前会话的「联网搜索」开关（持久化，聊天页与迷你窗共享）。 */
    fun setWebSearch(enabled: Boolean) {
        val sessionId = _state.value.currentSession().id
        updateState { container ->
            container.updateSession(sessionId) { it.copy(webSearch = enabled) }
        }
    }

    /** 切换当前会话的「思考模式」开关（持久化，聊天页与迷你窗共享）。 */
    fun setThinking(enabled: Boolean) {
        val sessionId = _state.value.currentSession().id
        updateState { container ->
            container.updateSession(sessionId) { it.copy(thinking = enabled) }
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

    /** 一次请求解析完成后的最终绑定（题目上下文 + 最终用户消息）。 */
    private data class BoundRequest(
        val userMessageId: String,
        /** 最终送入模型的用户消息（已按需注入屏幕上下文）。 */
        val prompt: String,
        /** 本轮是否真的注入了屏幕上下文。 */
        val hadContext: Boolean,
        /** 本轮绑定的题目文本（无上下文时为 null）。 */
        val questionText: String?,
        /** 非 null 表示本轮因上下文不可用而**未发起**模型请求。 */
        val failureReason: String?,
        // ---- R8.7 P3：随上下文一起带出的**注入证据**，用于在真正发送时自证 ----
        /** 实际贡献了 [questionText] 的池内片段 id（不是池内片段总数）。 */
        val includedSegmentIds: List<String> = emptyList(),
        /** [questionText] 中是否含分段标识（累计段 / 当前屏被明确分开）。 */
        val hasSegmentBreak: Boolean = false,
        /** 本次新鲜视口是否被并入。 */
        val liveIncluded: Boolean = false,
        /** 本次新鲜视口是否被采信为当前屏幕。 */
        val liveVerified: Boolean = true,
        /** 本次视口标识（**与 traceId 不同**）。 */
        val liveViewportId: String? = null,
        /** 上下文来源（[`fun`.kirari.hanako.screencontext.ScreenContextSource] 的名字）。 */
        val source: String? = null,
        // ---- 实时互动证据层：上下文身份 + 图像证据 + 明确缺失 ----
        val questionId: String? = null,
        val contextVersion: Int = 0,
        val contextStatus: String? = null,
        val contextImages: List<ContextImageRef> = emptyList(),
        val contextMissing: List<String> = emptyList()
    )

    /** 迷你窗可见的实时上下文状态（数据来自证据库组装结果，不是静态样例）。 */
    internal data class RealtimeContextUiStatus(
        val status: String,
        val questionId: String?,
        val version: Int,
        val imageCount: Int,
        val missing: List<String>,
        val updatedAtMillis: Long
    ) {
        val label: String
            get() {
                val base = when (status) {
                    "READY" -> "题目上下文：就绪"
                    "PARTIAL" -> "题目上下文：部分可用"
                    "UPDATING" -> "题目上下文：更新中"
                    "STALE" -> "题目上下文：已过期"
                    else -> "题目上下文：不可用"
                }
                val imagePart = if (imageCount > 0) " · 图像 $imageCount 张" else ""
                return base + imagePart + " · v$version"
            }
    }

    private val _realtimeContextStatus = MutableStateFlow<RealtimeContextUiStatus?>(null)

    /** 最近一次请求绑定的题目上下文状态；供迷你聊天窗口如实展示。 */
    internal val realtimeContextStatus: StateFlow<RealtimeContextUiStatus?> =
        _realtimeContextStatus.asStateFlow()

    private fun publishContextStatus(result: ScreenContextResult.Available) {
        val status = result.contextStatus ?: return
        _realtimeContextStatus.value = RealtimeContextUiStatus(
            status = status,
            questionId = result.questionId,
            version = result.contextVersion,
            imageCount = result.contextImages.count { it.available },
            missing = result.contextMissing,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    private fun ContextImageRef.toImageAsset(): ImageAssetRecord = ImageAssetRecord(
        imageId = imageId,
        evidenceId = evidenceId,
        questionId = questionId,
        originalRef = originalRef,
        previewRef = previewRef,
        sha256 = sha256,
        capturedAtMillis = capturedAtMillis,
        width = 0,
        height = 0,
        available = available
    )

    private fun ImageAssetRecord.toContextRef(): ContextImageRef = ContextImageRef(
        imageId = imageId,
        evidenceId = evidenceId,
        questionId = questionId,
        originalRef = originalRef,
        previewRef = previewRef,
        available = available,
        capturedAtMillis = capturedAtMillis,
        sha256 = sha256
    )

    private fun startStream(
        sessionId: String,
        userMessageId: String,
        instruction: String,
        imagesBase64: List<String> = emptyList(),
        reuseBoundRequest: Boolean = false,
        /** 调用方显式提供的页面上下文；非空时直接注入，绕过屏幕读取。 */
        contextText: String? = null
    ) {
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

        val token = ++requestSeq
        // 用 LAZY 起步，保证 `activeJob` 一定在协程体执行前完成赋值，避免收尾竞态。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val bound = if (reuseBoundRequest) {
                    // 复用原绑定：不得重新读取屏幕，也不得携带历史。
                    boundRequest?.takeIf { it.userMessageId == userMessageId }
                        ?: BoundRequest(userMessageId, instruction, false, null, null)
                } else {
                    val explicitContext = contextText?.takeIf { it.isNotBlank() }
                    if (explicitContext != null) {
                        // 显式上下文（分析页「继续追问」/迷你窗）：页面内容由调用方提供，
                        // 直接按注入语义组装 prompt，不再触发屏幕读取。
                        traceLogger?.invoke(
                            "trace=$assistantId context-explicit source=overlay_result " +
                                "chars=${explicitContext.length}"
                        )
                        BoundRequest(
                            userMessageId = userMessageId,
                            prompt = buildInjectedPrompt(instruction, explicitContext),
                            hadContext = true,
                            questionText = explicitContext,
                            failureReason = null,
                            source = "overlay_result"
                        )
                    } else {
                        resolveBoundRequest(assistantId, userMessageId, instruction)
                    }.also { boundRequest = it }
                }

                // 上下文不可用：不发起模型请求，以失败提示消息回应，终态 Failed。
                val failureReason = bound.failureReason
                if (failureReason != null) {
                    updateState { container ->
                        container.updateSession(sessionId) { s ->
                            s.copy(
                                messages = s.messages + ChatMessage(
                                    id = assistantId,
                                    role = ChatRole.Assistant,
                                    content = failureReason,
                                    status = ChatMessageStatus.Failed
                                )
                            )
                        }
                    }
                    traceLogger?.invoke("trace=$assistantId send-not-requested-then-failed reason=$failureReason")
                    requestTraceSink?.invoke(
                        RequestTraceRecord(
                            traceId = assistantId,
                            questionId = bound.questionId,
                            contextVersion = bound.contextVersion,
                            contextStatus = bound.contextStatus ?: "UNAVAILABLE",
                            textChars = 0,
                            textFingerprint = "-",
                            imageIds = emptyList(),
                            imageCount = 0,
                            visionStatus = "NOT_ATTEMPTED",
                            sent = false,
                            failStage = "context_unavailable",
                            errorMessage = failureReason,
                            createdAtMillis = System.currentTimeMillis()
                        )
                    )
                    return@launch
                }

                // 历史裁剪：仅「本轮与上一轮确属同一道题」时才携带最近一组问答。
                val messagesNow = _state.value.sessions.firstOrNull { it.id == sessionId }?.messages.orEmpty()
                val history = if (sameQuestionContext(lastQuestionText, bound.questionText)) {
                    recentHistoryBefore(messagesNow, userMessageId)
                } else {
                    emptyList()
                }
                lastQuestionText = bound.questionText

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

                val systemPrompt = if (bound.hadContext) REALTIME_ANSWER_SYSTEM_PROMPT else config.systemPrompt
                // ---- 视觉输入能力门控：能力未确认时不发送图像，并如实说明（绝不静默忽略）----
                val visionCapability = visionCapabilityOf(provider.kind, model)
                val visionPlan = planVisionImages(
                    capability = visionCapability,
                    images = bound.contextImages.map { it.toImageAsset() }
                )
                val loadedContextImages = if (visionPlan.imagesToSend.isEmpty()) {
                    emptyList()
                } else {
                    val refs = visionPlan.imagesToSend.map { it.toContextRef() }
                    contextImageLoader?.invoke(refs).orEmpty()
                }
                val sentContextImages = visionPlan.imagesToSend.take(loadedContextImages.size)
                val contextImagesBase64 = loadedContextImages.take(loadedContextImages.size)
                val visionNotice = if (bound.contextImages.isNotEmpty() && sentContextImages.isEmpty()) {
                    "\n\n【本次未发送图像：${visionPlan.note}；文字上下文可能缺少图表/公式信息】"
                } else {
                    ""
                }
                val effectivePrompt = bound.prompt + visionNotice
                val visionStatus = buildString {
                    append(visionCapability.name)
                    append(" sent=").append(sentContextImages.size)
                    if (visionPlan.imagesToSend.size > sentContextImages.size) {
                        append(" loadFailed=").append(visionPlan.imagesToSend.size - sentContextImages.size)
                    }
                    if (visionPlan.omittedImages.isNotEmpty()) {
                        append(" omitted=").append(visionPlan.omittedImages.size)
                    }
                    if (bound.contextImages.isNotEmpty() && sentContextImages.isEmpty()) {
                        append(" note=").append(visionPlan.note.replace(' ', '_'))
                    }
                }
                // R8.7 P3.4/P3.6：这里记录的是**即将真正传给 streamSource.stream() 的同一个变量**
                // （effectivePrompt / bound.questionText），不是任何「预期文本」的替身。
                // 与 AppContainer 侧的 `contextFp` 逐字比对，即可证明「注入的上下文 = 真正发出的 prompt」。
                traceLogger?.invoke(
                    "trace=$assistantId stream-send source=${bound.source ?: "none"} " +
                        "promptChars=${effectivePrompt.length} promptFp=${ocrFingerprintOf(effectivePrompt)} " +
                        "contextChars=${bound.questionText?.length ?: 0} " +
                        "contextFp=${bound.questionText?.let(::ocrFingerprintOf) ?: "-"} " +
                        "injectedSegmentIds=${bound.includedSegmentIds.joinToString(",").ifBlank { "-" }} " +
                        "hasSegmentBreak=${bound.hasSegmentBreak} liveIncluded=${bound.liveIncluded} " +
                        "liveVerified=${bound.liveVerified} liveViewportId=${bound.liveViewportId ?: "-"} " +
                        "recentHistory=${history.size} provider=$model " +
                        "vision=$visionStatus sentImageIds=${sentContextImages.joinToString(",") { it.imageId }.ifBlank { "-" }}"
                )
                var receivedDone = false
                var traceFailureStage: String? = null
                var traceErrorMessage: String? = null
                try {
                    streamSource.stream(
                        provider = provider,
                        model = model,
                        systemPrompt = systemPrompt,
                        userPrompt = effectivePrompt,
                        imagesBase64 = imagesBase64 + contextImagesBase64,
                        webSearch = session.webSearch,
                        thinking = session.thinking,
                        firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                        trustAllHttpsCertificates = config.trustAllHttpsCertificates,
                        traceId = assistantId,
                        recentHistory = history
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
                    traceFailureStage = "cancelled"
                    throw cancellation
                } catch (error: Throwable) {
                    finish(sessionId, assistantId, ChatMessageStatus.Error)
                    traceFailureStage = "stream"
                    traceErrorMessage = error.message ?: error.javaClass.simpleName
                } finally {
                    // 请求追踪：与测试中心展示的是同一份真实数据（文字指纹 + 实际图像 id + 能力状态）。
                    requestTraceSink?.invoke(
                        RequestTraceRecord(
                            traceId = assistantId,
                            questionId = bound.questionId,
                            contextVersion = bound.contextVersion,
                            contextStatus = bound.contextStatus ?: "UNAVAILABLE",
                            textChars = bound.questionText?.length ?: 0,
                            textFingerprint = bound.questionText?.let(::ocrFingerprintOf) ?: "-",
                            imageIds = sentContextImages.map { it.imageId },
                            imageCount = sentContextImages.size,
                            visionStatus = visionStatus,
                            sent = traceFailureStage == null,
                            failStage = traceFailureStage,
                            errorMessage = traceErrorMessage,
                            createdAtMillis = System.currentTimeMillis()
                        )
                    )
                }
            } finally {
                // 只清空「仍属于本次请求」的活跃标记：旧请求收尾不得清空新请求的标记。
                if (requestSeq == token) activeJob = null
            }
        }
        activeJob = job
        job.start()
    }

    /**
     * 解析本轮请求要绑定的题目上下文（普通聊天完全绕过屏幕读取）。
     *
     * 只有 [ScreenContextResult.Available] 才上报 [ScreenReadStatus.SEND_INJECTED]，
     * 且上报的报告严格来自本次读取结果（copy 只改注入状态，不改 traceId/来源/诊断元数据）。
     */
    private suspend fun resolveBoundRequest(
        assistantId: String,
        userMessageId: String,
        instruction: String
    ): BoundRequest {
        val injector = screenContextInjector
            ?: return BoundRequest(userMessageId, instruction, false, null, null)
        return when (val result = injector.invoke(assistantId)) {
            ScreenContextResult.NotRequested ->
                BoundRequest(userMessageId, instruction, false, null, null)

            is ScreenContextResult.Missing ->
                // R8.4：内容不足（只读到页面标题 / 导航等外壳文字）或无法确认目标应用身份时，
                // 本次请求**没有**注入屏幕上下文。日志必须明确写出「已阻止」，使「识别失败」
                // 与「模型实际收到了壳文字」在诊断上不可混淆。
                BoundRequest(userMessageId, instruction, false, null, result.reason).also {
                    traceLogger?.invoke("trace=$assistantId context-blocked reason=${result.reason}")
                }

            is ScreenContextResult.Available -> {
                val prompt = buildInjectedPrompt(instruction, result.text)
                screenContextReporter?.invoke(
                    result.readReport.copy(
                        status = ScreenReadStatus.SEND_INJECTED,
                        injected = true,
                        injectedText = result.text,
                        charCount = result.text.length
                    )
                )
                // R8.4：`context-injected` 不能再只表示「某段字符串被包装进 prompt」，必须同时说明
                // 来源、内容质量门控结论与是否携带视觉输入，才能区分「读到壳文字」与「读到题目」。
                publishContextStatus(result)
                traceLogger?.invoke(
                    "trace=$assistantId context-injected source=${result.source} chars=${result.text.length} " +
                        "quality=${result.readReport.contentQuality ?: "-"} " +
                        "visualInput=${result.readReport.visualInput ?: false} " +
                        "contextQuestion=${result.questionId ?: "-"} contextVersion=${result.contextVersion} " +
                        "contextStatus=${result.contextStatus ?: "-"} " +
                        "contextImages=${result.contextImages.count { it.available }} " +
                        "contextMissing=${result.contextMissing.size}"
                )
                BoundRequest(
                    userMessageId = userMessageId,
                    prompt = prompt,
                    hadContext = true,
                    questionText = result.text,
                    failureReason = null,
                    includedSegmentIds = result.includedSegmentIds,
                    hasSegmentBreak = result.hasSegmentBreak,
                    liveIncluded = result.liveIncluded,
                    liveVerified = result.liveVerified,
                    liveViewportId = result.liveViewportId,
                    source = result.source.name,
                    questionId = result.questionId,
                    contextVersion = result.contextVersion,
                    contextStatus = result.contextStatus,
                    contextImages = result.contextImages,
                    contextMissing = result.contextMissing
                )
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

/** 历史抽屉时间分组（纯函数，便于单元测试）。 */
internal data class SessionTimeGroup(
    val label: String,
    val sessions: List<ChatSession>
)

/**
 * 历史抽屉时间分组（纯函数，便于单元测试）。
 *
 * 以本地时区的「今天零点」为基准，按会话创建时间（createdAtMillis，即建立该会话那一刻起计）
 * 分为：今天 / 昨天 / 7天内 / 30天内 / 更早按「yyyy年M月」分组（如 2026年10月、2022年3月）。
 * 各组内按创建时间倒序（最新在前），月份组从新到旧排列。
 */
internal fun groupSessionsByTime(
    sessions: List<ChatSession>,
    nowMillis: Long = System.currentTimeMillis()
): List<SessionTimeGroup> {
    val dayMillis = 24 * 3600_000L
    val calendar = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayStart = calendar.timeInMillis
    val yesterdayStart = todayStart - dayMillis
    val sevenDaysStart = todayStart - 7 * dayMillis
    val thirtyDaysStart = todayStart - 30 * dayMillis

    val sorted = sessions.sortedByDescending { it.createdAtMillis }
    val groups = mutableListOf<SessionTimeGroup>()
    fun slice(label: String, start: Long, end: Long) {
        val part = sorted.filter { it.createdAtMillis in start until end }
        if (part.isNotEmpty()) groups += SessionTimeGroup(label, part)
    }
    slice("今天", todayStart, Long.MAX_VALUE)
    slice("昨天", yesterdayStart, todayStart)
    slice("7天内", sevenDaysStart, yesterdayStart)
    slice("30天内", thirtyDaysStart, sevenDaysStart)

    // 30 天前：按「yyyy年M月」分组（月份从新到旧），组内保持时间倒序。
    sorted
        .filter { it.createdAtMillis < thirtyDaysStart }
        .groupBy { session ->
            val c = Calendar.getInstance().apply { timeInMillis = session.createdAtMillis }
            c.get(Calendar.YEAR) * 100 + (c.get(Calendar.MONTH) + 1)
        }
        .toSortedMap(compareByDescending { it })
        .forEach { (key, list) -> groups += SessionTimeGroup("${key / 100}年${key % 100}月", list) }

    return groups
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
