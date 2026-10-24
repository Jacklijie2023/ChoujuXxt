package `fun`.kirari.hanako.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天会话独立存储的序列化兼容测试（纯 JVM，不联网）。
 *
 * 验证：roundtrip、旧格式（仅 messages、缺元数据字段）可反序列化、
 * 未知字段忽略、枚举/默认值向后兼容。
 */
class ChatSessionsJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun roundtrip_preserves_sessions_and_metadata() {
        val container = ChatSessionsContainer(
            sessions = listOf(
                ChatSession(
                    title = "会话一",
                    messages = listOf(
                        ChatMessage(
                            role = ChatRole.User,
                            content = "hi",
                            imagePaths = listOf("/data/user/0/app/files/chat_images/chat_1_0.img")
                        ),
                        ChatMessage(role = ChatRole.Assistant, content = "hello", status = ChatMessageStatus.Done)
                    ),
                    selectedProviderId = "p1",
                    selectedModel = "model-a"
                )
            ),
            currentSessionId = "s1"
        )

        val decoded = json.decodeFromString<ChatSessionsContainer>(
            json.encodeToString(ChatSessionsContainer.serializer(), container)
        )

        assertEquals(1, decoded.sessions.size)
        assertEquals("会话一", decoded.sessions[0].title)
        assertEquals("p1", decoded.sessions[0].selectedProviderId)
        assertEquals("model-a", decoded.sessions[0].selectedModel)
        assertEquals(2, decoded.sessions[0].messages.size)
        assertEquals(ChatRole.User, decoded.sessions[0].messages[0].role)
        assertEquals(
            "已发送图片的本地路径必须可持久化并在气泡中回显",
            listOf("/data/user/0/app/files/chat_images/chat_1_0.img"),
            decoded.sessions[0].messages[0].imagePaths
        )
        assertEquals(ChatMessageStatus.Done, decoded.sessions[0].messages[1].status)
    }

    @Test
    fun old_format_without_metadata_fields_still_decodes() {
        // 旧格式：仅 messages，无 id/title/createdAtMillis/selectedProviderId/selectedModel
        val oldJson = """
            {
              "sessions": [
                {
                  "messages": [
                    {"role": "User", "content": "旧消息", "status": "Done"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString<ChatSessionsContainer>(oldJson)

        assertEquals(1, decoded.sessions.size)
        assertEquals(ChatSession.DEFAULT_TITLE, decoded.sessions[0].title)
        assertNull(decoded.sessions[0].selectedProviderId)
        assertEquals("", decoded.sessions[0].selectedModel)
        assertEquals(1, decoded.sessions[0].messages.size)
        assertEquals("旧消息", decoded.sessions[0].messages[0].content)
        assertEquals("旧数据缺少 imagePaths 时必须回退为空列表", emptyList<String>(), decoded.sessions[0].messages[0].imagePaths)
        assertTrue(decoded.sessions[0].messages[0].id.isNotBlank())
        assertNull(decoded.currentSessionId)
    }

    @Test
    fun unknown_fields_are_ignored() {
        val jsonWithUnknown = """
            {
              "sessions": [],
              "currentSessionId": null,
              "futureField": "ignored"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<ChatSessionsContainer>(jsonWithUnknown)

        assertEquals(emptyList<ChatSession>(), decoded.sessions)
        assertNull(decoded.currentSessionId)
    }
}
