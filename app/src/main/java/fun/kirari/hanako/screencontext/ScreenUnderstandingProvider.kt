package `fun`.kirari.hanako.screencontext

/**
 * 屏幕理解来源。为后续接入本地视觉语言模型（MiniCPM-V / Qwen3-VL）与远程视觉模型预留。
 *
 * 本轮**只预留抽象**：现有 [ACCESSIBILITY] / [LOCAL_OCR] 是既有实现，
 * [LOCAL_VLM] / [REMOTE_VLM] 尚无实现，且不嵌入任何模型权重、不写入 APK。
 */
internal enum class ScreenUnderstandingKind {
    ACCESSIBILITY,
    LOCAL_OCR,
    LOCAL_VLM,
    REMOTE_VLM
}

/**
 * 对「当前有效屏幕帧」的一次结构化题目观察。
 *
 * 字段设计目标：无论来源是无障碍、OCR 还是视觉模型，都产出**同构**的结构化观察，
 * 以便统一走身份 / 时间戳 / 会话边界验证后再决定是否写池。
 * 不包含任何图片数据本身（帧句柄由调用方持有）。
 *
 * 命名：与既有 [ScreenObservation]（画面变化探测的结果）区分，避免语义混淆。
 */
internal data class ScreenUnderstandingObservation(
    val kind: ScreenUnderstandingKind,
    /** 观察到该内容的目标窗口包名（身份证据）。 */
    val windowKey: String?,
    val capturedAtMillis: Long,
    /** 题干文本（不含选项）。 */
    val questionText: String,
    val options: List<String> = emptyList(),
    /** 图表 / 图形描述（视觉模型可提供；OCR/无障碍通常为空）。 */
    val figureDescription: String? = null,
    /** 识别置信度（0..1）；无法给出时为 null。 */
    val confidence: Float? = null,
    /** 明确缺失的字段（如 question / optionC / figure），供「缺失原因可定位」。 */
    val missingFields: List<String> = emptyList()
)

/**
 * 一次观察请求所需的会话上下文（不携带图片本身）。
 *
 * [sessionEpoch] 用于把观察绑定到「同一绑定会话」：[AppContainer.applyBinding] 建立新会话边界时
 * 会重置该 epoch，旧 epoch 的输出不得被采纳。
 */
internal data class ScreenUnderstandingRequest(
    val boundPackage: String?,
    val sessionEpoch: Long,
    val capturedAtMillis: Long
)

/**
 * 屏幕理解提供者抽象。
 *
 * 后续可分别实现：无障碍文本提取、本地 OCR、本地视觉语言模型、必要的远程视觉模型。
 * 输入为「当前有效屏幕帧」（泛型 `F`，真机为 `Bitmap`，此处不引入 Android 依赖）+ 会话上下文；
 * 输出为结构化 [ScreenUnderstandingObservation]。
 *
 * 重要边界：**任何来源的输出都必须先通过 [canAdoptObservation]**（目标身份 + 时间戳 + 会话边界
 * 验证）才允许写入文本池，绝不能因为「视觉模型返回了文本」就直接写池。
 */
internal interface ScreenUnderstandingProvider<F> {
    val kind: ScreenUnderstandingKind

    fun observe(frame: F, request: ScreenUnderstandingRequest): ScreenUnderstandingObservation?
}

/**
 * 视觉 / 文本观察的**统一采纳门控**：语义与写池/注入两侧一致，防止绕过 R7 安全约束。
 *
 * 返回 true 表示允许采纳（写入文本池）。任一不满足即拒绝：
 * 1. 观察非空且题干非空白；
 * 2. 已绑定目标应用（[boundPackage] 非空非空白）；
 * 3. 观察身份 [ScreenUnderstandingObservation.windowKey] == 已绑定目标；
 * 4. 当前前台窗口 == 已绑定目标；
 * 5. 会话 epoch 一致（[sessionEpochMatches]）；
 * 6. 观察时间戳有效且未过期。
 */
internal fun canAdoptObservation(
    observation: ScreenUnderstandingObservation?,
    boundPackage: String?,
    currentWindowPackage: String?,
    sessionEpochMatches: Boolean,
    nowMillis: Long,
    maxAgeMillis: Long
): Boolean {
    val obs = observation ?: return false
    if (obs.questionText.isBlank()) return false
    val bound = boundPackage?.trim()?.ifBlank { null } ?: return false
    if (obs.windowKey.isNullOrBlank() || obs.windowKey != bound) return false
    if (currentWindowPackage?.trim() != bound) return false
    if (!sessionEpochMatches) return false
    val age = nowMillis - obs.capturedAtMillis
    if (obs.capturedAtMillis <= 0L || age < 0 || age > maxAgeMillis) return false
    return true
}
