package `fun`.kirari.hanako.evidence

import `fun`.kirari.hanako.data.ProviderKind

/**
 * 当前聊天 provider/model 对图像输入的能力判定。
 *
 * 需求 §6.3 明确要求：不能仅凭「OpenAI 兼容」推断支持视觉输入，必须给出明确能力状态，
 * 并在不支持/未确认时**如实说明图像未发送**，不得静默忽略。
 *
 * 判定策略（保守、可解释）：
 * - Anthropic / Anthropic Messages / Google Gemini / OpenAI Responses：协议与模型线支持图像 → [SUPPORTED]；
 * - OpenAI 兼容：只有模型名命中已知视觉模型线才判 [SUPPORTED]；
 * - 其余兼容端点：判 [UNKNOWN]（不做发送，上下文与测试中心写明显式说明）。
 */
internal enum class VisionCapability {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN
}

/** 已知视觉模型线的名称片段（小写匹配）。 */
private val VISION_MODEL_MARKERS = listOf(
    "gpt-4o", "gpt-4.1", "gpt-4-turbo", "gpt-4-vision", "chatgpt-4o",
    "claude", "gemini", "qwen-vl", "qwen2.5-vl", "qwen3-vl",
    "glm-4v", "glm-4.5v", "glm-4.6v", "doubao-vision", "doubao-1.5-vision",
    "internvl", "llava", "pixtral", "moonshot-v1-vision", "vision", "minicpm-v"
)

/** 明确不支持图像输入的模型线（即使走 OpenAI 兼容协议）。 */
private val TEXT_ONLY_MARKERS = listOf(
    "deepseek-chat", "deepseek-reasoner", "deepseek-v3", "deepseek-r1"
)

internal fun visionCapabilityOf(kind: ProviderKind, model: String): VisionCapability {
    val name = model.trim().lowercase()
    return when (kind) {
        ProviderKind.GOOGLE,
        ProviderKind.ANTHROPIC,
        ProviderKind.ANTHROPIC_MESSAGES,
        ProviderKind.OPENAI_RESPONSES -> VisionCapability.SUPPORTED

        ProviderKind.OPENAI_COMPATIBLE -> when {
            name.isEmpty() -> VisionCapability.UNKNOWN
            TEXT_ONLY_MARKERS.any { it in name } -> VisionCapability.UNSUPPORTED
            VISION_MODEL_MARKERS.any { it in name } -> VisionCapability.SUPPORTED
            else -> VisionCapability.UNKNOWN
        }
    }
}

/** 一次请求的视觉输入计划（决定了「实际发送多少张图」与「为什么没发」）。 */
internal data class VisionPlan(
    val capability: VisionCapability,
    val imagesToSend: List<ImageAssetRecord> = emptyList(),
    val omittedImages: List<ImageAssetRecord> = emptyList(),
    val note: String = ""
)

/**
 * 选取与当前题目相关的图像证据，并按能力决定是否发送。
 *
 * - 同一图像（sha256 相同）只保留一张；
 * - 优先保留**最新**的采集（更接近用户当前看到的内容）；
 * - 超出 [maxImages] 的旧图像被省略，并在 [VisionPlan.note] 中说明；
 * - 能力不是 [VisionCapability.SUPPORTED] 时，[VisionPlan.imagesToSend] 必须为空。
 */
internal fun planVisionImages(
    capability: VisionCapability,
    images: List<ImageAssetRecord>,
    maxImages: Int = 3
): VisionPlan {
    val available = images.filter { it.available }
            .sortedByDescending { it.capturedAtMillis }
    val deduped = buildList {
        val seen = mutableSetOf<String>()
        available.forEach { image ->
            if (seen.add(image.sha256)) add(image)
        }
    }
    if (available.isEmpty()) {
        return VisionPlan(capability, note = "当前题目没有可用的图像证据")
    }
    if (capability != VisionCapability.SUPPORTED) {
        val reason = when (capability) {
            VisionCapability.UNSUPPORTED -> "当前模型未确认支持图像输入"
            VisionCapability.UNKNOWN -> "当前端点是否为视觉模型尚未确认"
            VisionCapability.SUPPORTED -> ""
        }
        return VisionPlan(
            capability = capability,
            imagesToSend = emptyList(),
            omittedImages = deduped,
            note = "$reason，本次未发送 ${deduped.size} 张图像"
        )
    }
    val toSend = deduped.take(maxImages)
    val omitted = deduped.drop(maxImages)
    return VisionPlan(
        capability = capability,
        imagesToSend = toSend,
        omittedImages = omitted,
        note = if (omitted.isEmpty()) {
            "已选入 ${toSend.size} 张图像"
        } else {
            "已选入 ${toSend.size} 张图像，另有 ${omitted.size} 张较早图像未发送"
        }
    )
}
