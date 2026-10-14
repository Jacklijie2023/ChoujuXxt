package `fun`.kirari.hanako.data

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * 聊天消息角色。
 *
 * 说明：聊天会话（[ChatMessage] / [ChatSession]）与截图结果 [ProcessingResult] 严格分离，
 * 二者不共享字段、不互相转换。
 */
@Serializable
enum class ChatRole {
    User,
    Assistant
}

/** 聊天消息状态；**完成判定只以收到 `LlmEvent.Done` 为准**。 */
@Serializable
enum class ChatMessageStatus {
    Streaming,
    Done,
    Error
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val status: ChatMessageStatus = ChatMessageStatus.Done
)

/**
 * 单个聊天会话（含会话级元数据）。
 *
 * 元数据字段全部带默认值以保证向后兼容：旧格式 JSON（仅含 messages）
 * 与既有内存构造均无需改动即可反序列化/创建。
 *
 * [selectedProviderId] / [selectedModel] 为空表示使用默认文本模型（settings 派生）。
 */
@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_TITLE,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedModel: String = "",
    /** 是否置顶（历史抽屉排序用）。旧数据缺失时回退 false，保持向后兼容。 */
    val pinned: Boolean = false,
    /** 是否开启「联网搜索」（会话级，聊天页与迷你窗共享）。旧数据缺失时回退 false。 */
    val webSearch: Boolean = false,
    /** 是否开启「思考模式」（会话级，聊天页与迷你窗共享）。旧数据缺失时回退 false。 */
    val thinking: Boolean = false
) {
    val isStreaming: Boolean
        get() = messages.any { it.status == ChatMessageStatus.Streaming }

    companion object {
        const val DEFAULT_TITLE = "新会话"
    }
}

/**
 * 会话列表 + 当前会话 id 的持久化容器。
 *
 * 独立于 [AppSettings] 存储，单独序列化，避免任何与既有配置/历史相互污染。
 */
@Serializable
data class ChatSessionsContainer(
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String? = null
)

/**
 * 会话持久化接缝。
 *
 * 生产实现走 [ChatSessionStore]（DataStore + JSON）；单元测试可不注入（内存态）。
 */
interface ChatSessionPersistence {
    suspend fun load(): ChatSessionsContainer
    suspend fun save(container: ChatSessionsContainer)
}
