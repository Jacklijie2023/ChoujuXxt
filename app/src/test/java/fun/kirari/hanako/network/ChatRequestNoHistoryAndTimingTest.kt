package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ChatRole
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体「会话历史的携带规则」与「思考模式耗时指标」的纯逻辑单元测试（不联网）。
 *
 * 设计意图：
 * - 默认（新题 / 普通聊天）请求体**只有 system + 当前用户消息**，一旦有人无条件塞入历史消息，测试会立刻失败；
 * - 仅当 `StreamRequest.recentHistory` 被显式填充（同题追问）时，历史消息才插入到 system 与当前用户消息之间；
 *   是否填充由 `ChatSessionHolder` 依据「当前题目上下文是否与上一次请求一致」决定，本测试只校验组装层。
 */
class ChatRequestNoHistoryAndTimingTest {

    private val provider = ModelProviderConfig(
        name = "Test",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        chatModel = "test-model"
    )

    private fun request(userPrompt: String, model: String = "test-model") = StreamRequest(
        provider = provider,
        model = model,
        systemPrompt = "sys",
        userPrompt = userPrompt,
        firstDeltaTimeoutMillis = 20_000L
    )

    @Test
    fun open_ai_payload_contains_only_system_and_current_user_message() {
        val payload = buildOpenAiChatPayload(request("当前题目：1+1=?"))
        val messages = payload["messages"]!!.jsonArray

        assertEquals("请求体必须只有 system + 当前用户消息两条", 2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("sys", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("当前题目：1+1=?", messages[1].jsonObject["content"]!!.jsonPrimitive.content)

        // 不得出现任何 assistant 角色消息（即不得携带历史问答）。
        assertTrue(
            "请求体不得携带任何历史助手消息",
            messages.none { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }
        )
    }

    @Test
    fun open_ai_payload_inserts_recent_history_between_system_and_current_user() {
        val withHistory = StreamRequest(
            provider = provider,
            model = "test-model",
            systemPrompt = "sys",
            userPrompt = "为什么不是 C",
            recentHistory = listOf(
                ChatHistoryMessage(ChatRole.User, "这题选什么"),
                ChatHistoryMessage(ChatRole.Assistant, "答案是 D")
            ),
            firstDeltaTimeoutMillis = 20_000L
        )

        val messages = buildOpenAiChatPayload(withHistory)["messages"]!!.jsonArray

        assertEquals("system + 历史问答 + 当前用户 = 4 条", 4, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("sys", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("这题选什么", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("答案是 D", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[3].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("为什么不是 C", messages[3].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun open_ai_payload_uses_image_url_only_when_images_are_provided() {
        // 场景 9：图像输入支持与否必须有明确判断，且与实际构造请求一致。
        // 提供 imagesBase64 时 → 走图像输入路径（content 为 text + image_url 数组）；
        // 未提供（实时题目链路现状：屏幕上下文以文本注入）→ content 为纯文本，无任何图像字段，
        // 与日志中的 imageCount=imagesBase64.size=0 保持一致。
        val withImage = StreamRequest(
            provider = provider,
            model = "test-model",
            systemPrompt = "sys",
            userPrompt = "看图作答",
            imagesBase64 = listOf("QUJD"),
            firstDeltaTimeoutMillis = 20_000L
        )
        assertTrue("提供图片时适配器必须走视觉输入路径", withImage.hasImages)
        val content = buildOpenAiChatPayload(withImage)["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonArray
        assertEquals("text + 1 张图片", 2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            "图像字段必须是 data URI",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                .startsWith("data:image/jpeg;base64,")
        )

        val noImage = request("纯文本题目")
        assertFalse("无图片时不得走视觉输入路径", noImage.hasImages)
        val textContent = buildOpenAiChatPayload(noImage)["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("无图片时 content 必须是纯文本", "纯文本题目", textContent)
    }

    @Test
    fun stream_timing_records_first_event_reasoning_content_and_total_separately() {
        val summary = streamTimingSummary(
            traceId = "trace-1",
            startNanos = 0L,
            endNanos = 12_000_000_000L,
            firstEventNanos = 100_000_000L,
            firstReasoningNanos = 150_000_000L,
            firstContentNanos = 9_000_000_000L,
            reasoningChunks = 7,
            contentChars = 42
        )

        assertTrue(summary.contains("trace=trace-1"))
        assertTrue("首个 SSE 事件时间必须单独记录", summary.contains("firstEventMs=100"))
        assertTrue("首个推理增量时间必须单独记录", summary.contains("firstReasoningMs=150"))
        assertTrue("首个正式正文增量时间必须单独记录", summary.contains("firstContentMs=9000"))
        assertTrue("整体完成时间必须记录", summary.contains("totalMs=12000"))
        assertTrue(summary.contains("reasoningChunks=7"))
        assertTrue(summary.contains("contentChars=42"))
    }

    @Test
    fun stream_timing_marks_absent_phases_as_minus_one() {
        val summary = streamTimingSummary(
            traceId = null,
            startNanos = 1_000L,
            endNanos = 2_000_000L,
            firstEventNanos = null,
            firstReasoningNanos = null,
            firstContentNanos = null,
            reasoningChunks = 0,
            contentChars = 0
        )

        assertTrue(summary.contains("trace=-"))
        assertTrue(summary.contains("firstEventMs=-1"))
        assertTrue(summary.contains("firstReasoningMs=-1"))
        assertTrue(summary.contains("firstContentMs=-1"))
        assertTrue(summary.contains("totalMs=1"))
    }
}
