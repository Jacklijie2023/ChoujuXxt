package `fun`.kirari.hanako.screencontext

/**
 * 一个视口片段与**当前题目上下文**的关联状态（R8.6 跨滚动视口累计的核心判据）。
 *
 * 它回答「这个片段是否已被证明属于当前这道题、是否已并入连续正文」，直接决定该片段在
 * 用户提问时**是否进入最终请求**：
 * - [MERGED]：与既有内容存在**可证明的**相邻重叠 / 整体包含，已去重并入连续正文（[ViewportMerger] 的产物）。
 * - [SEPARATE_CONFIRMED]：无法证明与既有正文连续，但存在**同题证据**（共享内容达到阈值、或双方题号一致），
 *   因此作为**独立片段**保留并允许注入 —— 注入时必须带明确的分段标记，**绝不**与既有正文拼成伪连续原文。
 * - [UNPROVEN_EXCLUDED]：证据不足，无法确认是否同题。**保留**该片段以供核查与诊断，
 *   但**不进入最终请求**（不得伪装成已确认的同题正文）。
 */
internal enum class FragmentQuestionRelation {
    MERGED,
    SEPARATE_CONFIRMED,
    UNPROVEN_EXCLUDED
}

/**
 * 一次「视口采集」的结果（纯数据，无 Bitmap / Activity / Service / UI 依赖，可直接单测）。
 *
 * 术语：**视口** = 某个时刻屏幕上实际看到的那一屏内容。同一道题持续滚动时会产生多个视口，
 * 本模型是 [ScreenContextPool] 保存、[ViewportMerger] 合并的最小单位。
 *
 * 字段说明：
 * - [id]：本次采集的唯一标识，只用于核查来源，不参与任何合并判定。
 * - [questionKey]：所属**题目上下文**标识；由 [ScreenContextPool] 在建立新题目上下文时统一分配，
 *   同一题目下的所有片段共享该标识，用于把「同一题的多次视口」与「不同题」区分开。
 * - [text]：结构化整理后的完整文本（即 `OcrOrganization.text`），**行序即阅读顺序**，
 *   选项标签与其正文的归属已在这一层确定。
 * - [lines] / [rows]：整理时参与排序的原始行与视觉行分组；**无坐标时为空列表**，
 *   调用方必须据此走降级路径（见 [comparable]），不得虚构坐标。
 * - [rawOcrText]：OCR 引擎自身拼接的原始文本，仅用于核对「合并过程有没有补写文字」。
 * - [fingerprint]：整理后文本的指纹（SHA-256），用于判定同一视口是否被重复识别。
 * - [optionMarkerCount] / [optionAssociation]：选项标记数量与可信度，随片段一起保留以供核查。
 * - [comparable]：本次是否具备**可靠的重叠比对条件**（有坐标行且整理后文本非空）。
 *   为 false 时[ScreenChangeDetector] 不得基于行关系强行下结论。
 */
internal data class ViewportFragment(
    val id: String,
    val questionKey: String,
    val text: String,
    val rawOcrText: String,
    val capturedAtMillis: Long,
    val fingerprint: String,
    val lines: List<OcrTextLine> = emptyList(),
    val rows: List<List<OcrTextLine>> = emptyList(),
    val windowKey: String? = null,
    val optionMarkerCount: Int = 0,
    val optionAssociation: OcrOptionAssociation = OcrOptionAssociation.NONE,
    val comparable: Boolean = false,
    /**
     * R8.6：本片段与当前题目上下文的关联状态。默认 [FragmentQuestionRelation.MERGED] 以兼容既有构造点；
     * 由 [ScreenContextPool] 在分类 / 合并时改写为权威值（独立同题片段 / 证据不足而被排除）。
     */
    val questionRelation: FragmentQuestionRelation = FragmentQuestionRelation.MERGED
) {
    /** 阅读顺序的行文本 —— 合并层真正的工作单位。 */
    val readingOrderLines: List<String> get() = ocrLines(text)
}

/**
 * 由一次单视口整理结果构造视口片段。
 *
 * [fingerprint] 与 [comparable] 都由此处统一推导，避免各调用点各自为政造成判定口径不一致。
 * [questionKey] 允许留空：调用方尚不知道该片段归入哪个题目上下文时可先留空，
 * 由 [ScreenContextPool] 在建立/沿用题目上下文时补写权威标识。
 */
internal fun viewportFragmentOf(
    id: String,
    organization: OcrOrganization,
    rawOcrText: String,
    capturedAtMillis: Long,
    questionKey: String = "",
    windowKey: String? = null
): ViewportFragment = ViewportFragment(
    id = id,
    questionKey = questionKey,
    text = organization.text,
    rawOcrText = rawOcrText,
    capturedAtMillis = capturedAtMillis,
    fingerprint = fingerprintOf(organization.text),
    lines = organization.lines,
    rows = organization.rows,
    windowKey = windowKey,
    optionMarkerCount = organization.optionMarkerCount,
    optionAssociation = organization.association,
    // 有坐标行 + 文本非空 + 坐标盒子自洽，才具备可靠的行级重叠比对条件。
    // 坐标缺失或异常（right < left / bottom < top）时降级为不可比对：宁可返回「不确定」，
    // 也不基于不可信的几何信息做合并判定。
    comparable = organization.lines.isNotEmpty() &&
        organization.text.isNotBlank() &&
        organization.lines.all { it.right >= it.left && it.bottom >= it.top }
)

/**
 * 由**无障碍节点树文本**构造视口片段（阶段 2 持续采集主路径）。
 *
 * 与 [viewportFragmentOf] 的差异（都必须保留，不得混用）：
 * - 无障碍文本**没有位图坐标**，因此 [ViewportFragment.lines] / [ViewportFragment.rows] 保持空列表，
 *   **不虚构坐标**；[ViewportFragment.rawOcrText] 即原始节点树文本（仅用于核对来源）。
 * - 节点树文本本身按行给出，足以做「相邻边界连续重叠」的行级比对，因此
 *   [ViewportFragment.comparable] 依据「是否存在非空行」判定，而不是像 OCR 片段那样要求坐标盒子自洽。
 * - 无障碍文本没有「标签与正文按坐标关联」的证据，[ViewportFragment.optionAssociation] 保守标记为
 *   [OcrOptionAssociation.NONE]，把「选项结构是否完整」的判定留给后续真正带坐标的 OCR 片段。
 */
internal fun accessibilityViewportFragmentOf(
    id: String,
    text: String,
    capturedAtMillis: Long,
    questionKey: String = "",
    windowKey: String? = null
): ViewportFragment {
    val raw = text.trim()
    val lines = ocrLines(raw)
    return ViewportFragment(
        id = id,
        questionKey = questionKey,
        text = raw,
        rawOcrText = raw,
        capturedAtMillis = capturedAtMillis,
        fingerprint = fingerprintOf(raw),
        lines = emptyList(),
        rows = emptyList(),
        windowKey = windowKey,
        optionMarkerCount = countOptionMarkers(raw),
        optionAssociation = OcrOptionAssociation.NONE,
        comparable = lines.isNotEmpty()
    )
}
