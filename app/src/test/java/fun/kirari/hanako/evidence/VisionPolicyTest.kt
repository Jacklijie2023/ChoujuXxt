package `fun`.kirari.hanako.evidence

import `fun`.kirari.hanako.data.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多模态能力门控与发送计划的纯 JVM 单测。
 *
 * 关注点（对应需求 §6.3）：
 * - 不能仅凭「OpenAI 兼容」就推断支持视觉；
 * - 不支持 / 未确认时**不得**发送图像，且必须给出显式说明；
 * - 同一图像（sha256）不重复发送，优先最新，超出上限的旧图明确列为未发送。
 */
class VisionPolicyTest {

    private fun image(
        imageId: String,
        sha: String = "sha-$imageId",
        capturedAt: Long = 1_000L,
        available: Boolean = true
    ) = ImageAssetRecord(
        imageId = imageId,
        evidenceId = "ev-$imageId",
        questionId = "q-1",
        originalRef = "$imageId.png",
        previewRef = "${imageId}_preview.jpg",
        sha256 = sha,
        capturedAtMillis = capturedAt,
        width = 1080,
        height = 2400,
        available = available
    )

    // ========================
    // 1. 能力判定
    // ========================

    @Test
    fun native_vision_protocols_are_supported() {
        assertEquals(VisionCapability.SUPPORTED, visionCapabilityOf(ProviderKind.GOOGLE, "gemini-2.0-flash"))
        assertEquals(VisionCapability.SUPPORTED, visionCapabilityOf(ProviderKind.ANTHROPIC, "claude-3-5-sonnet"))
        assertEquals(
            VisionCapability.SUPPORTED,
            visionCapabilityOf(ProviderKind.ANTHROPIC_MESSAGES, "claude-sonnet-4")
        )
        assertEquals(
            VisionCapability.SUPPORTED,
            visionCapabilityOf(ProviderKind.OPENAI_RESPONSES, "gpt-4.1")
        )
    }

    @Test
    fun openai_compatible_known_vision_model_is_supported() {
        assertEquals(VisionCapability.SUPPORTED, visionCapabilityOf(ProviderKind.OPENAI_COMPATIBLE, "gpt-4o-mini"))
        assertEquals(VisionCapability.SUPPORTED, visionCapabilityOf(ProviderKind.OPENAI_COMPATIBLE, "qwen-vl-max"))
    }

    @Test
    fun openai_compatible_text_only_model_is_unsupported() {
        assertEquals(
            VisionCapability.UNSUPPORTED,
            visionCapabilityOf(ProviderKind.OPENAI_COMPATIBLE, "deepseek-chat")
        )
        assertEquals(
            VisionCapability.UNSUPPORTED,
            visionCapabilityOf(ProviderKind.OPENAI_COMPATIBLE, "deepseek-reasoner")
        )
    }

    @Test
    fun unknown_compatible_model_is_unknown_not_guessed() {
        assertEquals(
            "不能仅凭 OpenAI 兼容协议推断支持视觉",
            VisionCapability.UNKNOWN,
            visionCapabilityOf(ProviderKind.OPENAI_COMPATIBLE, "my-custom-endpoint-model")
        )
        assertEquals(
            VisionCapability.UNKNOWN,
            visionCapabilityOf(ProviderKind.OPENAI_COMPATIBLE, "  ")
        )
    }

    // ========================
    // 2. 不支持时不得静默丢弃
    // ========================

    @Test
    fun unsupported_model_sends_no_image_and_explains_why() {
        val plan = planVisionImages(
            capability = VisionCapability.UNSUPPORTED,
            images = listOf(image("img-1"), image("img-2", sha = "sha-2"))
        )

        assertTrue(plan.imagesToSend.isEmpty())
        assertEquals(2, plan.omittedImages.size)
        assertTrue(plan.note.contains("未确认支持图像输入"))
        assertTrue(plan.note.contains("未发送"))
    }

    @Test
    fun unknown_capability_also_sends_no_image() {
        val plan = planVisionImages(VisionCapability.UNKNOWN, listOf(image("img-1")))

        assertTrue(plan.imagesToSend.isEmpty())
        assertTrue(plan.note.contains("尚未确认"))
    }

    @Test
    fun supported_capability_without_images_reports_the_fact() {
        val plan = planVisionImages(VisionCapability.SUPPORTED, emptyList())

        assertTrue(plan.imagesToSend.isEmpty())
        assertTrue(plan.note.contains("没有可用的图像证据"))
    }

    // ========================
    // 3. 去重 / 最新优先 / 数量上限
    // ========================

    @Test
    fun duplicate_sha256_images_are_sent_once() {
        val plan = planVisionImages(
            VisionCapability.SUPPORTED,
            listOf(
                image("img-old", sha = "same", capturedAt = 1_000L),
                image("img-new", sha = "same", capturedAt = 5_000L)
            )
        )

        assertEquals(1, plan.imagesToSend.size)
        assertEquals("同一图像只保留最新一次采集", "img-new", plan.imagesToSend.single().imageId)
    }

    @Test
    fun images_are_ordered_by_capture_time_descending() {
        val plan = planVisionImages(
            VisionCapability.SUPPORTED,
            listOf(
                image("img-1", sha = "sha-1", capturedAt = 1_000L),
                image("img-2", sha = "sha-2", capturedAt = 3_000L),
                image("img-3", sha = "sha-3", capturedAt = 2_000L)
            )
        )

        assertEquals(listOf("img-2", "img-3", "img-1"), plan.imagesToSend.map { it.imageId })
    }

    @Test
    fun images_beyond_the_limit_are_reported_as_omitted() {
        val images = (1..5).map { image("img-$it", sha = "sha-$it", capturedAt = it * 1_000L) }

        val plan = planVisionImages(VisionCapability.SUPPORTED, images, maxImages = 3)

        assertEquals(3, plan.imagesToSend.size)
        assertEquals(listOf("img-5", "img-4", "img-3"), plan.imagesToSend.map { it.imageId })
        assertEquals(2, plan.omittedImages.size)
        assertTrue(plan.note.contains("较早图像未发送"))
    }

    @Test
    fun unavailable_images_are_never_selected() {
        val plan = planVisionImages(
            VisionCapability.SUPPORTED,
            listOf(
                image("img-missing", sha = "sha-m", available = false),
                image("img-ok", sha = "sha-ok")
            )
        )

        assertEquals(1, plan.imagesToSend.size)
        assertEquals("img-ok", plan.imagesToSend.single().imageId)
    }
}
