package `fun`.kirari.hanako.screencontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多视口文本合并的纯逻辑单元测试（**不联网、不依赖实时截图、不接触任何运行时状态**）。
 *
 * 测试数据刻意使用「真实多行题干 + 四个选项 + 跨屏重叠」的形态，
 * 而不是几个互不相关的短字符串，用来验证合并层在真实刷题场景下的行为。
 */
class ViewportMergerTest {

    private val stem = listOf(
        "言语理解与表达 第 11/15 题",
        "下列填入横线处最恰当的一项是：",
        "随着数字经济的快速发展，数据要素已成为",
        "推动经济增长的重要力量。然而，________"
    )

    private val options = listOf(
        "A. 数据安全与隐私保护面临新的挑战",
        "B. 传统产业数字化转型步伐明显加快",
        "C. 数字鸿沟可能进一步扩大",
        "D. 数据要素市场化配置亟需制度保障"
    )

    /** 第一屏：题干 + 前两个选项。 */
    private val screenA = MergeSide(stem + options.take(2))

    /** 第二屏（向下滚动）：最后一小段题干 + 前两个选项（重叠）+ 后两个选项（新增）。 */
    private val screenB = MergeSide(listOf(stem.last()) + options)

    private fun nonWhitespaceChars(text: String): List<Char> =
        text.filter { !it.isWhitespace() }.toList()

    // ========================
    // 1. 相邻两个视口存在连续重叠行 → 合并后不重复输出重叠内容
    // ========================

    @Test
    fun adjacent_viewports_merge_without_repeating_the_overlap() {
        val result = ViewportMerger.merge(screenA, screenB)

        assertEquals(MergeOutcome.OVERLAP_MERGED, result.outcome)
        assertEquals(stem + options, result.lines)
        // 元素级去重校验：题干与四个选项各出现一次。
        assertEquals((stem + options).size, result.lines!!.size)
        assertEquals(1, result.lines!!.count { it == options[1] })
    }

    // ========================
    // 2. 与旧视口没有可证明的重叠关系 → 不会盲目删除相似句子
    // ========================

    @Test
    fun disjoint_viewport_is_not_force_merged() {
        // 新视口与既有内容毫无连续重叠，只是「看起来都是题面文字」。
        val previous = MergeSide(stem + options.take(2))
        val incoming = MergeSide(
            listOf(
                "第二部分 判断推理 第 1/10 题",
                "下列结论中不能由题干推出的是：",
                "A. 甲比乙高",
                "B. 乙比丙高"
            )
        )

        val result = ViewportMerger.merge(previous, incoming)

        assertEquals(MergeOutcome.UNSTABLE, result.outcome)
        assertNull("不确定时不得给出合并结果，调用方必须保持原内容", result.lines)
        assertTrue(result.note?.contains("没有可证明的连续重叠") == true)
    }

    // ========================
    // 3. 向上滚动回到已经采集过的视口 → 不追加重复内容
    // ========================

    @Test
    fun scrolling_back_to_an_already_collected_viewport_does_not_append_duplicates() {
        val mergedEverything = MergeSide(stem + options)
        // 滚回中间一段：它整体出现在既有内容中，但不在「边界」上。
        val scrolledBack = MergeSide(options.take(2))

        val result = ViewportMerger.merge(mergedEverything, scrolledBack)

        assertEquals(MergeOutcome.DUPLICATE, result.outcome)
        assertEquals(stem + options, result.lines)
        assertTrue(result.note?.contains("回到已采集视口") == true)
    }

    @Test
    fun incoming_viewport_fully_inside_tail_overlap_is_duplicate() {
        // 新视口完全落在既有内容的尾部，没有任何新增行。
        val previous = MergeSide(stem + options.take(3))
        val incoming = MergeSide(options.take(3).takeLast(2))

        val result = ViewportMerger.merge(previous, incoming)

        assertEquals(MergeOutcome.DUPLICATE, result.outcome)
        assertEquals(stem + options.take(3), result.lines)
    }

    // ========================
    // 3b. R3：非连续共享行（既有内容按原顺序整体出现在新视口中）→ 安全合并，不丢字不错序
    // ========================

    @Test
    fun non_contiguous_shared_lines_merge_when_the_existing_lines_appear_in_order() {
        // 既有内容：题干首行 + A/B/C。新视口在**中间插入**了一行 OCR 碎片，并在末尾补上 D。
        // 边界既没有连续重叠，既有内容也不是新视口的连续子串（「碎片」插在中间），
        // 但既有内容每一行都按原顺序出现在新视口中 —— 这是可证明的同题证据，必须合并。
        val previous = MergeSide(
            listOf("题干第一行文字", "A. 选项一内容", "B. 选项二内容", "C. 选项三内容")
        )
        val incoming = MergeSide(
            listOf(
                "题干第一行文字",
                "中间多出的一行OCR碎片",
                "A. 选项一内容",
                "B. 选项二内容",
                "C. 选项三内容",
                "D. 选项四内容"
            )
        )

        val result = ViewportMerger.merge(previous, incoming)
        val lines = result.lines!!

        assertEquals(MergeOutcome.OVERLAP_MERGED, result.outcome)
        assertEquals("既有内容是新视口的有序子集，以新视口为准", incoming.lines, lines)
        assertTrue("既有内容一行不得丢失", previous.lines.all { it in lines })
        assertEquals("合并结果不得出现重复行", lines.size, lines.distinct().size)
        // 既有行的相对阅读顺序完全保留。
        assertEquals(previous.lines, lines.filter { it in previous.lines })
    }

    @Test
    fun ordered_subsequence_without_any_character_level_containment_is_merged() {
        // 既有内容的两行被「碎片行」隔开，去空白后的整体签名因此**不是**新视口的连续子串，
        // 字符级包含规则落空；但行序仍可证明 —— 这正是「分类器判定扩展、合并器却无对应规则」的错位场景。
        val previous = MergeSide(listOf("题干第一行文字", "A. 选项一内容"))
        val incoming = MergeSide(listOf("题干第一行文字", "碎片行", "A. 选项一内容", "B. 选项二内容"))

        val result = ViewportMerger.merge(previous, incoming)

        assertEquals(MergeOutcome.OVERLAP_MERGED, result.outcome)
        assertEquals(incoming.lines, result.lines)
    }

    @Test
    fun high_overlap_with_a_missing_existing_line_is_conservatively_unstable() {
        // 共享行比例很高，但既有内容有一行在新视口中找不到（被遮挡 / 跨题 / 共用材料歧义）。
        // 无法证明行序完整 —— 必须保守退回 UNSTABLE，绝不强行把既有内容拼进新视口（会丢字或错序）。
        val previous = MergeSide(listOf("题干第一行文字", "A. 选项一内容", "B. 选项二内容", "C. 选项三内容"))
        val incoming = MergeSide(listOf("题干第一行文字", "A. 选项一内容", "B. 选项二内容", "D. 选项四内容"))

        val result = ViewportMerger.merge(previous, incoming)

        assertEquals(MergeOutcome.UNSTABLE, result.outcome)
        assertNull("不确定时不得给出合并结果，调用方必须保持原内容", result.lines)
        assertTrue(result.note?.contains("没有可证明的连续重叠") == true)
    }

    @Test
    fun accessibility_line_split_difference_is_still_merged_by_character_containment() {
        // 由**单个控件**给出的整段题干（一行），与按视觉行切分的 OCR 视口行键完全对不上（行级规则全落空），
        // 但既有内容去空白后整体出现在新视口中 —— 这是 R1 的兜底证据，不得因本次改动而回归。
        val previous = MergeSide(
            listOf("随着数字经济的快速发展，数据要素已成为推动经济增长的重要力量。然而，________")
        )
        val incoming = MergeSide(stem + options)

        val result = ViewportMerger.merge(previous, incoming)

        assertEquals(MergeOutcome.OVERLAP_MERGED, result.outcome)
        assertEquals(stem + options, result.lines)
    }

    // ========================
    // 3c. R4：字符级**有序**子序列包含 —— 短种子被完整 OCR 视口安全补全
    // ========================

    @Test
    fun character_level_ordered_subsequence_without_contiguous_containment_is_merged() {
        // 既有内容（无障碍短种子）的去空白全文**不是**新视口的连续子串：OCR 在既有文字**中间**插入了碎片字词，
        // 行级规则（边界连续重叠 / 行级有序子序列）与字符级整体连续包含全部落空；
        // 但既有内容的每个字都按**原顺序**在场 —— 这是可证明的同题证据，必须安全合并。
        val previous = MergeSide(listOf("下列关于数字经济发展", "的说法正确的是"))
        val incoming = MergeSide(
            listOf(
                "下列关于数字经济",
                "发展的XX说法",
                "正确的是",
                "A. 选项一内容",
                "B. 选项二内容"
            )
        )

        val result = ViewportMerger.merge(previous, incoming)

        assertEquals(MergeOutcome.OVERLAP_MERGED, result.outcome)
        assertEquals("以新视口为准，既有内容的字一字不丢", incoming.lines, result.lines)
        assertTrue(result.note?.contains("按原顺序逐字出现在新视口中") == true)
    }

    @Test
    fun reordered_existing_characters_are_conservatively_unstable() {
        // 既有内容的字都在新视口中出现，但**顺序被 OCR 的视觉分组打乱** —— 顺序信息不可证明。
        // 必须保守返回 UNSTABLE，绝不因为「字都在」就强行合并（会造成错序）。
        val previous = MergeSide(listOf("数字经济推动增长方式转型"))
        val incoming = MergeSide(listOf("转型推动增长方式数字经济", "A. 甲乙丙丁", "B. 戊己庚辛"))

        val result = ViewportMerger.merge(previous, incoming)

        assertEquals(MergeOutcome.UNSTABLE, result.outcome)
        assertNull("不确定时不得给出合并结果，调用方必须保持原内容", result.lines)
    }

    @Test
    fun different_questions_sharing_boilerplate_are_not_merged_by_char_level_evidence() {
        // 两道不同的题共享同一句模板文字，但既有内容的字**没有**全部按原顺序出现在新视口中 ——
        // 不得因为「文字看着相似」就误判为同题（防止新增证据造成跨题污染）。
        val previous = MergeSide(
            listOf(
                "下列填入横线处最恰当的一项是：",
                "随着数字经济的快速发展，数据要素已成为推动经济增长的重要力量"
            )
        )
        val incoming = MergeSide(
            listOf(
                "下列填入横线处最恰当的一项是：",
                "随着人工智能技术的快速迭代，算法治理已成为公共讨论的重要议题",
                "A. 数据安全与隐私保护面临新的挑战",
                "B. 传统产业数字化转型步伐明显加快"
            )
        )

        val result = ViewportMerger.merge(previous, incoming)

        assertEquals(MergeOutcome.UNSTABLE, result.outcome)
        assertNull(result.lines)
    }

    // ========================
    // 4. 题干与 A/B/C/D 选项的自然阅读顺序得以保留
    // ========================

    @Test
    fun merged_content_keeps_stem_then_options_in_reading_order() {
        val result = ViewportMerger.merge(MergeSide(stem + options.take(2)), MergeSide(listOf(stem.last()) + options))

        val lines = result.lines!!
        assertEquals(stem, lines.take(stem.size))
        assertEquals(options, lines.drop(stem.size))
    }

    // ========================
    // 5. 选项正文跨行 → 合并不会让续行脱离自己的选项
    // ========================

    @Test
    fun multi_line_option_continuation_stays_with_its_own_option_after_merge() {
        // 上一屏：A 的正文跨行（续行已由单视口整理并入 A）。
        val previous = MergeSide(
            listOf(
                "A. 第一选项正文 第一选项的续行文字",
                "B. 第二选项正文"
            )
        )
        // 新一屏：从 B 开始，新增 C、D。
        val incoming = MergeSide(
            listOf(
                "B. 第二选项正文",
                "C. 第三选项正文",
                "D. 第四选项正文"
            )
        )

        val result = ViewportMerger.merge(previous, incoming)
        val lines = result.lines!!

        assertEquals(MergeOutcome.OVERLAP_MERGED, result.outcome)
        assertEquals(
            listOf(
                "A. 第一选项正文 第一选项的续行文字",
                "B. 第二选项正文",
                "C. 第三选项正文",
                "D. 第四选项正文"
            ),
            lines
        )
        // 续行仍然紧跟在 A 内部，没有被挪到相邻选项下。
        assertTrue(lines[0].contains("第一选项的续行文字"))
    }

    // ========================
    // 6. 行内空白波动不应破坏重叠识别（比较键去空白，输出仍用原文）
    // ========================

    @Test
    fun whitespace_fluctuation_does_not_break_overlap_matching() {
        val previous = MergeSide(listOf("A.选项一内容", "B.选项二内容"))
        val incoming = MergeSide(listOf("B. 选项二内容", "C. 选项三内容"))

        val result = ViewportMerger.merge(previous, incoming)

        assertEquals(MergeOutcome.OVERLAP_MERGED, result.outcome)
        // 输出沿用上一屏的原始行文本，不会被改写成新一屏的空白形态。
        assertEquals(listOf("A.选项一内容", "B.选项二内容", "C. 选项三内容"), result.lines)
    }

    // ========================
    // 7. 合并结果不得出现 OCR 未识别的文字
    // ========================

    @Test
    fun merge_never_invents_characters_absent_from_the_ocr_input() {
        val result = ViewportMerger.merge(screenA, screenB)
        val merged = result.lines!!.joinToString("\n")

        val inputChars = (screenA.lines + screenB.lines).joinToString("\n")
            .filter { !it.isWhitespace() }
            .toSet()

        val invented = nonWhitespaceChars(merged).filter { it !in inputChars }
        assertTrue("合并不得补写 OCR 未识别的字词，实际多出：$invented", invented.isEmpty())
    }

    // ========================
    // 8. 空视口 / 首次采集 / 空既有内容
    // ========================

    @Test
    fun empty_incoming_viewport_is_unstable_and_produces_no_lines() {
        val result = ViewportMerger.merge(screenA, MergeSide(emptyList()))

        assertEquals(MergeOutcome.UNSTABLE, result.outcome)
        assertNull(result.lines)
    }

    @Test
    fun first_observation_establishes_new_content() {
        val result = ViewportMerger.merge(null, screenA)

        assertEquals(MergeOutcome.NEW_QUESTION, result.outcome)
        assertEquals(screenA.lines, result.lines)
    }

    @Test
    fun empty_previous_content_is_appended_directly() {
        val result = ViewportMerger.merge(MergeSide(emptyList()), screenA)

        assertEquals(MergeOutcome.APPENDED, result.outcome)
        assertEquals(screenA.lines, result.lines)
    }

    // ========================
    // 9. 工具函数（供分类器/池共用，必须口径一致）
    // ========================

    @Test
    fun overlap_helpers_use_whitespace_insensitive_keys() {
        val prevKeys = listOf("题干", "A.选项一", "B.选项二").map(::ocrLineKey)
        val incKeys = listOf("B. 选项二", "C. 选项三").map(::ocrLineKey)

        assertEquals(1, longestSuffixPrefixOverlap(prevKeys, incKeys))
        assertEquals(1, indexOfSubList(prevKeys, listOf("A.选项一", "B. 选项二").map(::ocrLineKey)))
        assertEquals(-1, indexOfSubList(prevKeys, listOf("C.选项三").map(::ocrLineKey)))
    }

    @Test
    fun ordered_subsequence_helper_requires_every_line_in_the_original_order() {
        val needle = listOf("题干", "A.选项一", "B.选项二").map(::ocrLineKey)
        val haystack = listOf("题干", "碎片行", "A. 选项一", "B. 选项二", "C. 选项三").map(::ocrLineKey)

        assertTrue("不连续但按原顺序出现即成立", isOrderedSubsequenceOf(needle, haystack))
        assertTrue(
            "少了一行既有内容即不成立（必须保守）",
            !isOrderedSubsequenceOf(needle + ocrLineKey("D.选项四"), haystack)
        )
        assertTrue(
            "顺序被打乱即不成立",
            !isOrderedSubsequenceOf(listOf("B.选项二", "A.选项一").map(::ocrLineKey), haystack)
        )
        assertTrue("空既有内容不成立", !isOrderedSubsequenceOf(emptyList(), haystack))
    }

    @Test
    fun ordered_char_subsequence_helper_requires_every_character_in_the_original_order() {
        assertTrue("允许中间插入其它文字（不要求连续）", isOrderedCharSubsequenceOf("数字转型", "数字X转Y型"))
        assertTrue(isOrderedCharSubsequenceOf("数据要素市场化", "数据要素市场化配置"))
        assertTrue(
            "少了一个既有字即不成立（必须保守）",
            !isOrderedCharSubsequenceOf("数字转型", "数字翻页内容")
        )
        assertTrue(
            "顺序被打乱即不成立",
            !isOrderedCharSubsequenceOf("转型", "数字型转")
        )
        assertTrue("空既有内容不成立", !isOrderedCharSubsequenceOf("", "任何文字"))
    }
}
