package `fun`.kirari.hanako.screencontext

import kotlin.math.abs

/**
 * 本地 OCR 返回的一行文字及其位图坐标（纯数据，不含任何 Android 依赖，可直接单测）。
 *
 * 坐标单位为像素，原点为本次送入 OCR 的那张位图的左上角；`right`/`bottom` 为开区间外沿。
 */
internal data class OcrTextLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    /** 行高（至少为 1，避免零高盒子导致除零或阈值失效）。 */
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * 一次本地 OCR 的完整结果。
 *
 * - [rawText]：引擎自身拼接出来的原始文本。**原样保留**，仅用于诊断对比，不参与注入，
 *   也不做任何排序或改写，便于核对「整理过程有没有擅自增删文字」。
 * - [lines]：带坐标的行列表（可能取自 `Line`，也可能取自更细的 `Element`），
 *   结构化整理只使用它；顺序不做任何假设。
 */
internal data class OcrResult(
    val rawText: String,
    val lines: List<OcrTextLine> = emptyList()
)

/** 选项标签与正文的关联可信度。 */
internal enum class OcrOptionAssociation {
    /** 未检出任何选项标签。 */
    NONE,

    /** 检出标签，且按屏幕位置形成了完整、连续的选项序列，每项都有正文。 */
    COMPLETE,

    /** 检出标签，但数量、顺序或正文关联存在不确定，**不得当作可靠选项**。 */
    PARTIAL
}

/** 结构化整理结果（纯数据，便于单元测试）。 */
internal data class OcrOrganization(
    val text: String,
    val rowCount: Int,
    val optionLabels: List<String>,
    val optionMarkerCount: Int,
    val association: OcrOptionAssociation,
    val note: String?,
    /**
     * 参与整理的原始文字行（已剔除空白行），**保持引擎返回顺序**，用于核对来源。
     *
     * 无坐标时为空列表 —— 调用方必须据此走降级路径，不得虚构坐标。
     */
    val lines: List<OcrTextLine> = emptyList(),
    /**
     * 按阅读顺序分组后的视觉行：外层为自上而下的行，内层已按 `left` 从左到右排序。
     *
     * 供多视口合并层复用坐标信息；无坐标时为空列表。
     */
    val rows: List<List<OcrTextLine>> = emptyList()
)

/**
 * 把本地 OCR 的「行 + 坐标」整理成阅读顺序正确、选项可关联的文本。
 *
 * 为什么需要它（对应真机证据）：`recognize()` 原先直接把 `TextBlock.text` 按引擎返回顺序拼接，
 * 既不使用 ML Kit 提供的 `TextBlock → Line → Element` 层级，也不使用任何坐标。
 * 一旦引擎把「A、B、C、D」标签与选项正文拆成不同行/不同块返回，
 * 拼接结果就会出现「标签先集中出现、选项正文分散在后」「选项前半段与后半段分离」，
 * 并且按行匹配的 [countOptionMarkers] 会因为标签行不再带标点而统计为 0。
 *
 * 整理规则（全部只依赖坐标，不依赖引擎返回顺序，也不补写任何缺失文字）：
 * 1. **行分组**：先把所有行按纵向重叠度聚成「视觉行」，避免只按单个块的顶部坐标排序；
 *    纵向重叠达到较小者一半高度即视为同一行，从而把并排的标签与正文归到同一行。
 * 2. **行内排序**：同一视觉行内按 `left` 从左到右排序，恢复「标签在前、正文在后」的自然顺序，
 *    并明确**不**采用「全文按 x 排序」或「全文按 y 排序」，否则会打散多行段落与选项关联。
 * 3. **视觉行之间**：按 `top` 从上到下输出，保留原有换行与段落关系。
 * 4. **选项识别**：支持带标点标签（`A.` `B、` `C．` `D）`）、整行单独出现的字母、圆圈选项
 *    （`Ⓐ` `①`）、括号标签（`(A)` `（B）`）；**只**认「带标点 / 本身就是选项符号 / 整行只有该字母」
 *    三种形态，避免把「A 股市场」这类普通文本误判成选项。
 * 5. **续行归属**：某行若没有任何标签，且紧贴在某个已识别选项下方、且位于该选项正文列右侧，
 *    则并入该选项（同一行带内左右并排的选项用正文列的横向距离区分），
 *    既不错归到相邻选项，也不会把题干预设为某个选项的续行。
 * 6. **不确定即标注**：标签数量不足、顺序不连续/重复、或某标签未关联到正文时，
 *    [OcrOrganization.association] 返回 [OcrOptionAssociation.PARTIAL]，并把原因写入 [OcrOrganization.note]。
 *
 * 若引擎没有返回任何行级坐标，则如实退回 [OcrResult.rawText]，并在 [OcrOrganization.note] 中
 * 说明「无法核对阅读顺序与选项关联」，不假装整理成功。
 */
internal fun organizeOcrText(result: OcrResult): OcrOrganization {
    val lines = result.lines.filter { it.text.isNotBlank() }
    if (lines.isEmpty()) {
        val fallback = result.rawText.trim()
        val markers = countOptionMarkers(fallback)
        return OcrOrganization(
            text = fallback,
            rowCount = 0,
            optionLabels = emptyList(),
            optionMarkerCount = markers,
            association = if (markers > 0) OcrOptionAssociation.PARTIAL else OcrOptionAssociation.NONE,
            note = "OCR 未返回行级坐标，已按引擎原始顺序输出，无法核对阅读顺序与选项关联",
            // 无坐标时**不虚构**行/分组：保持空列表，调用方据此走降级路径。
            lines = emptyList(),
            rows = emptyList()
        )
    }

    val referenceHeight = medianLineHeight(lines)
    val tolerance = referenceHeight
    val rows = groupRows(lines)

    val emitted = ArrayList<EmittedEntry>()
    val options = ArrayList<OptionDraft>()

    for (row in rows) {
        val rowTop = row.minOf { it.top }
        val rowBottom = row.maxOf { it.bottom }
        val rowLeft = row.minOf { it.left }

        if (row.none { labelOf(it.text) != null }) {
            val owner = continuationOwner(options, rowTop, rowLeft, tolerance, referenceHeight)
            if (owner != null) {
                val joined = row.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                if (joined.isNotEmpty()) owner.extraContents += joined
                if (rowBottom > owner.lastBottom) owner.lastBottom = rowBottom
                continue
            }
        }

        // 行内从左到右：标签开启一个新选项，其后（同一行内、标签之前）的非标签文字仍按普通文本单独成行。
        var activeOption: OptionDraft? = null
        val freeBuffer = ArrayList<String>()
        for (item in row) {
            val label = labelOf(item.text)
            if (label == null) {
                val target = activeOption
                if (target != null) {
                    val text = item.text.trim()
                    if (text.isNotEmpty()) target.extraContents += text
                    if (item.bottom > target.lastBottom) target.lastBottom = item.bottom
                } else {
                    val text = item.text.trim()
                    if (text.isNotEmpty()) freeBuffer += text
                }
            } else {
                if (freeBuffer.isNotEmpty()) {
                    emitted += EmittedEntry.Free(freeBuffer.joinToString(" "))
                    freeBuffer.clear()
                }
                val draft = OptionDraft(
                    order = label.order,
                    rawText = item.text.trim(),
                    inlineContentLength = label.content.length,
                    contentStartX = if (label.content.isNotEmpty()) item.left + item.height else item.right,
                    lastBottom = item.bottom
                )
                options += draft
                emitted += EmittedEntry.Option(draft)
                activeOption = draft
            }
        }
        if (freeBuffer.isNotEmpty()) emitted += EmittedEntry.Free(freeBuffer.joinToString(" "))
    }

    val text = emitted
        .joinToString("\n") { entry ->
            when (entry) {
                is EmittedEntry.Free -> entry.text
                is EmittedEntry.Option -> entry.draft.render()
            }
        }
        .trim()

    val note = optionAssociationNote(options)
    return OcrOrganization(
        text = text,
        rowCount = rows.size,
        optionLabels = options.map { it.rawText },
        optionMarkerCount = options.size,
        association = when {
            options.isEmpty() -> OcrOptionAssociation.NONE
            note == null -> OcrOptionAssociation.COMPLETE
            else -> OcrOptionAssociation.PARTIAL
        },
        note = note,
        // 暴露原始行（引擎返回顺序）与分组后的视觉行（阅读顺序），供多视口合并层复用坐标；
        // 两者都是本次识别结果的**只读副本**，不参与也不改变上面 text 的生成语义。
        lines = lines.toList(),
        rows = rows.map { row -> row.toList() }
    )
}

// ============================== 行分组 ==============================

/**
 * 把所有行按纵向重叠度聚成视觉行，再在行内按 `left` 从左到右排序、行间按 `top` 从上到下输出。
 *
 * 关键点：**先分组再排序**，而不是对全部文字直接按 x 或 y 排序——
 * 前者能同时保住「多行段落」和「并排的标签 + 正文」，后者一定会破坏其中之一。
 */
private fun groupRows(lines: List<OcrTextLine>): List<List<OcrTextLine>> {
    val ordered = lines.sortedWith(compareBy({ it.top }, { it.left }))
    val rows = ArrayList<MutableList<OcrTextLine>>()
    for (line in ordered) {
        val current = rows.lastOrNull()
        if (current != null && sharesVisualRow(current, line)) {
            current.add(line)
        } else {
            rows.add(arrayListOf(line))
        }
    }
    return rows.map { row -> row.sortedBy { it.left } }
}

/** 纵向重叠达到较小一方高度的一半即视为同一视觉行（容差用于吸收上下几百微米的基线偏移）。 */
private fun sharesVisualRow(row: List<OcrTextLine>, line: OcrTextLine): Boolean {
    val rowTop = row.minOf { it.top }
    val rowBottom = row.maxOf { it.bottom }
    val overlap = minOf(rowBottom, line.bottom) - maxOf(rowTop, line.top)
    if (overlap <= 0) return false
    return overlap * 2 >= minOf(rowBottom - rowTop, line.height)
}

private fun medianLineHeight(lines: List<OcrTextLine>): Int {
    val sorted = lines.map { it.height }.sorted()
    return sorted[sorted.size / 2].coerceAtLeast(1)
}

// ============================== 续行归属 ==============================

/**
 * 判断一行（不含任何标签）应并入哪个选项。
 *
 * 三个条件必须同时满足，缺一不可：
 * 1. 该行位于选项最后一行下方（允许一个行高的容差，吸收基线偏移）；
 * 2. 与选项最后一行的间距不超过一个行高 —— 只有「紧贴的换行」才算续行，段落间距更大则视为新段落；
 * 3. 该行没有回到标签左边（`left` 不小于该选项正文列起点减一个行高）——
 *    否则它更可能是左侧对齐的题干段落，绝不能并入选项。
 *
 * 命中多个候选时取间距最小者；间距相同时（同一行带内左右并排的选项，例如 A、B 分列两栏）
 * 用「正文列起点与该行左沿的水平距离」区分，保证续行不会错误归到相邻选项。
 */
private fun continuationOwner(
    options: List<OptionDraft>,
    rowTop: Int,
    rowLeft: Int,
    tolerance: Int,
    referenceHeight: Int
): OptionDraft? {
    if (options.isEmpty()) return null
    val candidates = options.filter { option ->
        rowTop >= option.lastBottom - tolerance &&
            rowTop - option.lastBottom <= referenceHeight &&
            rowLeft >= option.contentStartX - tolerance
    }
    if (candidates.isEmpty()) return null
    val minGap = candidates.minOf { rowTop - it.lastBottom }
    val closest = candidates.filter { rowTop - it.lastBottom == minGap }
    if (closest.size == 1) return closest.first()
    return closest.minByOrNull { abs(rowLeft - it.contentStartX) }
}

// ============================== 标签识别 ==============================

/** 一次标签匹配：[order] 为选项序号（0=A/Ⓐ/①，1=B…），[content] 为同行紧随其后的正文（可能为空）。 */
private class LabelMatch(val order: Int, val content: String)

/** 圆圈字母选项（Ⓐ…Ⓓ）。 */
private const val CIRCLED_OPTION_CHARS = "ⒶⒷⒸⒹ"

/** 圈号选项（①…④）。 */
private const val NUMBERED_OPTION_CHARS = "①②③④"

/** 带标点的行内前缀标签：`A.` `A、` `A．` `A:` `A：` `A)` `A）`，以及括号 / 圆圈 / 圈号标签。 */
private val LABEL_PREFIX = Regex("^([A-Da-d][.、．:：)）]|[（(][A-Da-d][）)]|[ⒶⒷⒸⒹ①②③④])")

/** 整行就是一个括号标签。 */
private val PAREN_LABEL_ONLY = Regex("^[（(]([A-Da-d])[）)]$")

/**
 * 识别一个「行内条目」是否为选项标签。
 *
 * 刻意**不**接受「字母 + 空格 + 正文」这种无标点形态（例如 `A 股市场`），
 * 以免把普通文本误判成选项；整行只有单个字母的情况由 [singleLabelOrder] 单独处理。
 */
private fun labelOf(rawText: String): LabelMatch? {
    val text = rawText.trim()
    if (text.isEmpty()) return null
    singleLabelOrder(text)?.let { return LabelMatch(it, "") }
    val match = LABEL_PREFIX.find(text) ?: return null
    val order = orderOfLabelText(match.value)
    if (order < 0) return null
    return LabelMatch(order, text.substring(match.range.last + 1).trim())
}

/** 整行只有标签：单个字母、圆圈选项、圈号选项，或整行括号标签。 */
private fun singleLabelOrder(text: String): Int? {
    if (text.length == 1) {
        val char = text[0]
        val upper = char.uppercaseChar()
        if (upper in 'A'..'D') return upper - 'A'
        CIRCLED_OPTION_CHARS.indexOf(char).let { if (it >= 0) return it }
        NUMBERED_OPTION_CHARS.indexOf(char).let { if (it >= 0) return it }
        return null
    }
    val paren = PAREN_LABEL_ONLY.find(text)?.takeIf { it.range.first == 0 && it.range.last == text.lastIndex }
        ?: return null
    return paren.groupValues[1].first().uppercaseChar() - 'A'
}

private fun orderOfLabelText(label: String): Int {
    for (char in label) {
        val upper = char.uppercaseChar()
        if (upper in 'A'..'D') return upper - 'A'
        CIRCLED_OPTION_CHARS.indexOf(char).let { if (it >= 0) return it }
        NUMBERED_OPTION_CHARS.indexOf(char).let { if (it >= 0) return it }
    }
    return -1
}

// ============================== 内部数据结构 ==============================

private sealed class EmittedEntry {
    class Free(val text: String) : EmittedEntry()
    class Option(val draft: OptionDraft) : EmittedEntry()
}

/**
 * 一个被识别出来的选项。
 *
 * [rawText] 保持该条目在 OCR 原文里的原样（**不**改写成 `A.` 之类），
 * 续行与同行后续文字只以空格追加，因此整理过程不会凭空产生标点或文字。
 */
private class OptionDraft(
    val order: Int,
    val rawText: String,
    private val inlineContentLength: Int,
    val contentStartX: Int,
    var lastBottom: Int
) {
    val extraContents = ArrayList<String>()

    fun hasContent(): Boolean = inlineContentLength > 0 || extraContents.any { it.isNotBlank() }

    fun render(): String {
        val parts = ArrayList<String>(1 + extraContents.size)
        if (rawText.isNotBlank()) parts += rawText
        parts += extraContents.filter { it.isNotBlank() }
        return parts.joinToString(" ")
    }
}

/**
 * 选项关联可信度的可读原因；返回 null 表示关联可信（此时才是 [OcrOptionAssociation.COMPLETE]）。
 *
 * 仅凭「全文里出现了 A、B、C、D」不算数：必须满足标签数量 ≥ 2、序号严格等于 `0..n-1`
 * （即连续、不重复、从 A 开始），并且每个标签都关联到了正文。
 */
private fun optionAssociationNote(options: List<OptionDraft>): String? {
    if (options.isEmpty()) return "未检出任何选项标签"
    val orders = options.map { it.order }
    if (orders.size < 2) return "仅检出 ${orders.size} 个选项标签，不足以断定选项结构"
    if (orders != orders.indices.toList()) {
        return "选项标签顺序不连续或重复（检出 ${options.joinToString("") { it.rawText }}）"
    }
    val contentless = options.filter { !it.hasContent() }
    if (contentless.isNotEmpty()) {
        return "选项 ${contentless.joinToString("") { it.rawText }} 未关联到正文"
    }
    return null
}
