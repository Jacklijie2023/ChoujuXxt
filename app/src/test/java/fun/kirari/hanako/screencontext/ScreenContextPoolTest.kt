package `fun`.kirari.hanako.screencontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多视口屏幕上下文池的纯逻辑单元测试（**不联网、不依赖实时截图、不接触无障碍服务或 UI**）。
 *
 * 数据一律使用「真实多行题干 + 四个选项 + 跨屏重叠」的形态，避免只用几个互不相关的短字符串，
 * 以便同时验证「阅读顺序保持」与「边界去重」两件事。
 */
class ScreenContextPoolTest {

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

    private val screenA = (stem + options.take(2)).joinToString("\n")

    /** 第二屏（向下滚动）：最后一小段题干 + 前两个选项（重叠）+ 后两个选项（新增）。 */
    private val screenB = (listOf(stem.last()) + options).joinToString("\n")
    private val screenC = options.takeLast(2).joinToString("\n")
    private val otherQuestion = listOf(
        "第二部分 判断推理 第 1/10 题",
        "下列结论中不能由题干推出的是：",
        "A. 丙的身高最矮",
        "B. 甲的身高最高"
    ).joinToString("\n")

    /** 真机形态的**短无障碍种子**：按控件换行、只有三行（题号横幅 + 题干 + 长题干行），且无位图坐标。 */
    private val shortSeedLines = listOf(
        "言语理解与表达 第 11/15 题",
        "下列填入横线处最恰当的一项是：",
        "随着数字经济的快速发展，数据要素已成为推动经济增长的重要力量。然而，________"
    )
    private val shortSeedText = shortSeedLines.joinToString("\n")

    /**
     * 同一道题的**完整 OCR 视口**：把种子末行切成两行，并在中间插入一行 OCR 碎片，
     * 使种子的去空白全文既不是它的连续子串、行级规则也全部落空，但每个字都按原顺序在场。
     */
    private val interleavedOcrText = (
        listOf(
            "言语理解与表达 第 11/15 题",
            "下列填入横线处最恰当的一项是：",
            "随着数字经济的快速发展，数据要素已成为",
            "识别到的一行额外 OCR 碎片",
            "推动经济增长的重要力量。然而，________"
        ) + options
        ).joinToString("\n")

    // ========================
    // 测试工具
    // ========================

    /** 每行单独成一个视觉行、且间距足够大（不会触发续行归属）的整理结果。 */
    private fun organizationOf(text: String): OcrOrganization {
        val trimmed = text.trim()
        val lines = trimmed.lines().mapIndexed { index, raw ->
            OcrTextLine(
                text = raw,
                left = 20,
                top = 100 + index * 100,
                right = 500,
                bottom = 130 + index * 100
            )
        }
        return organizeOcrText(OcrResult(rawText = trimmed, lines = lines))
    }

    private fun fragment(
        id: String,
        text: String,
        atMillis: Long,
        windowKey: String? = "com.qbank.app"
    ): ViewportFragment = viewportFragmentOf(
        id = id,
        organization = organizationOf(text),
        rawOcrText = text,
        capturedAtMillis = atMillis,
        windowKey = windowKey
    )

    /** 行距紧贴，使「选项续行」在单视口整理阶段被并入自己的选项。 */
    private fun tightFragment(id: String, text: String, atMillis: Long): ViewportFragment {
        val trimmed = text.trim()
        val lines = trimmed.lines().mapIndexed { index, raw ->
            OcrTextLine(
                text = raw,
                left = 20,
                top = 300 + index * 40,
                right = 500,
                bottom = 330 + index * 40
            )
        }
        return viewportFragmentOf(
            id = id,
            organization = organizeOcrText(OcrResult(rawText = trimmed, lines = lines)),
            rawOcrText = trimmed,
            capturedAtMillis = atMillis,
            windowKey = "com.qbank.app"
        )
    }

    /** 无任何坐标的片段：整理退回原始文本，且不可比对。 */
    private fun noCoordinateFragment(id: String, text: String, atMillis: Long): ViewportFragment =
        viewportFragmentOf(
            id = id,
            organization = organizeOcrText(OcrResult(rawText = text, lines = emptyList())),
            rawOcrText = text,
            capturedAtMillis = atMillis,
            windowKey = "com.qbank.app"
        )

    /** 坐标异常（right < left）的片段：坐标不可信，必须降级为不可比对。 */
    private fun invertedBoxFragment(id: String, text: String, atMillis: Long): ViewportFragment {
        val trimmed = text.trim()
        val lines = trimmed.lines().mapIndexed { index, raw ->
            OcrTextLine(
                text = raw,
                left = 500,
                top = 100 + index * 100,
                right = 20,
                bottom = 130 + index * 100
            )
        }
        return viewportFragmentOf(
            id = id,
            organization = organizeOcrText(OcrResult(rawText = trimmed, lines = lines)),
            rawOcrText = trimmed,
            capturedAtMillis = atMillis,
            windowKey = "com.qbank.app"
        )
    }

    /**
     * 无障碍节点树里「整段题干挤在同一个控件里」的那一行。
     *
     * 由题干的两个 OCR 视觉行**直接拼接**而成，与 OCR 的行切分口径完全不同，
     * 但去空白后整体出现在 OCR 视口文本中（用于验证字符级包含兜底证据）。
     */
    private val accessibilityStemLine = stem[2] + stem[3]

    /** 无障碍节点树片段：有非空行（可比对），但没有位图坐标，坐标行保持为空。 */
    private fun accessibilityFragment(id: String, text: String, atMillis: Long): ViewportFragment =
        accessibilityViewportFragmentOf(
            id = id,
            text = text,
            capturedAtMillis = atMillis,
            windowKey = "com.qbank.app"
        )

    private fun pool(maxFragments: Int = ScreenContextPool.DEFAULT_MAX_FRAGMENTS) =
        ScreenContextPool(maxFragments = maxFragments, clock = { 0L })

    private fun nonWhitespace(text: String): String = text.filter { !it.isWhitespace() }

    // ========================
    // ①③④ 相邻重叠合并：不重复输出重叠内容，且阅读顺序保持
    // ========================

    @Test
    fun adjacent_viewports_merge_without_duplicating_the_overlap() {
        val pool = pool()

        assertEquals(PoolUpdateResult.NEW_QUESTION, pool.observe(fragment("v1", screenA, 1_000)))
        assertEquals(PoolUpdateResult.OVERLAP_MERGED, pool.observe(fragment("v2", screenB, 2_000)))

        val snapshot = pool.currentQuestion()!!
        val expected = (stem + options).joinToString("\n")
        assertEquals(expected, snapshot.text)
        assertEquals("合并后不得出现重复行", snapshot.lines.size, snapshot.lines.distinct().size)
        assertEquals(2, snapshot.fragments.size)
    }

    @Test
    fun merged_content_keeps_stem_then_abcd_in_reading_order() {
        val pool = pool()
        pool.observe(fragment("v1", screenA, 1_000))
        pool.observe(fragment("v2", screenB, 2_000))

        val lines = pool.currentQuestion()!!.lines

        assertEquals(stem, lines.take(stem.size))
        assertEquals(options, lines.drop(stem.size))
        assertEquals(QuestionCompleteness.COMPLETE, pool.currentQuestion()!!.completeness)
    }

    // ========================
    // ②⑨ 无可证明的重叠关系：不盲目删除相似句子，返回不确定
    // ========================

    @Test
    fun screen_without_provable_overlap_is_unproven_and_keeps_existing_text() {
        val pool = pool()
        pool.observe(fragment("v1", screenA, 1_000))
        val before = pool.currentQuestion()!!.text

        val result = pool.observe(
            fragment(
                "v-other",
                listOf(
                    "以下结论中不能由题干推出的是：",
                    "甲的身高高于乙，乙的身高高于丙，则",
                    "A. 丙的身高最矮",
                    "B. 甲的身高最高"
                ).joinToString("\n"),
                2_000
            )
        )

        // R8.6：既无连续重叠、也无足够同题证据 → 关联未知：既有内容原样保留，本片段只留档、不进入请求。
        assertEquals(PoolUpdateResult.UNPROVEN, result)
        assertEquals("关联未知时既有内容必须原样保留", before, pool.currentQuestion()!!.text)
        assertEquals(1, pool.fragmentCount())
        assertEquals(1, pool.unconfirmedFragmentCount())
        assertEquals(0, pool.separateSegmentCount())
    }

    @Test
    fun first_observation_without_coordinates_establishes_context_then_degrades_safely() {
        val pool = pool()

        // 无坐标：允许用原始文本建立上下文（保留降级路径），但不可比对。
        assertEquals(
            PoolUpdateResult.NEW_QUESTION,
            pool.observe(noCoordinateFragment("v1", screenA, 1_000))
        )
        assertEquals(screenA, pool.currentQuestion()!!.text)
        assertTrue(pool.currentQuestion()!!.fragments.single().lines.isEmpty())

        // 第二次仍然没有坐标：不得基于不可信的几何信息强行合并。
        val result = pool.observe(noCoordinateFragment("v2", screenB, 2_000))
        assertEquals(PoolUpdateResult.UNSTABLE, result)
        assertEquals(screenA, pool.currentQuestion()!!.text)
    }

    @Test
    fun abnormal_coordinates_degrade_to_unstable_instead_of_forced_merge() {
        val pool = pool()
        pool.observe(fragment("v1", screenA, 1_000))
        val before = pool.currentQuestion()!!.text

        val result = pool.observe(invertedBoxFragment("v2", screenB, 2_000))

        assertEquals(PoolUpdateResult.UNSTABLE, result)
        assertEquals(before, pool.currentQuestion()!!.text)
    }

    // ========================
    // ③ 向上滚回已采集视口：不无限追加重复内容
    // ========================

    @Test
    fun scrolling_back_up_does_not_append_duplicates() {
        val pool = pool()
        pool.observe(fragment("v1", screenA, 1_000))
        pool.observe(fragment("v2", screenB, 2_000))
        val merged = pool.currentQuestion()!!.text
        val fragmentsAfterTwoScreens = pool.fragmentCount()

        val result = pool.observe(fragment("v3", options.take(2).joinToString("\n"), 3_000))

        assertEquals(PoolUpdateResult.DUPLICATE, result)
        assertEquals(merged, pool.currentQuestion()!!.text)
        assertEquals("回到已采集视口不得追加片段", fragmentsAfterTwoScreens, pool.fragmentCount())
    }

    // ========================
    // ⑤ 选项正文跨行：合并后续行仍归属于自己的选项
    // ========================

    @Test
    fun multi_line_option_continuation_is_not_reassigned_by_merging() {
        val pool = pool()
        // 緊贴行距：续行在单视口整理阶段已被并入 A。
        assertEquals(
            PoolUpdateResult.NEW_QUESTION,
            pool.observe(
                tightFragment("v1", "A. 第一选项正文\n第一选项的续行文字\nB. 第二选项正文", 1_000)
            )
        )
        assertEquals(
            PoolUpdateResult.OVERLAP_MERGED,
            pool.observe(
                tightFragment("v2", "B. 第二选项正文\nC. 第三选项正文\nD. 第四选项正文", 2_000)
            )
        )

        val lines = pool.currentQuestion()!!.lines
        assertEquals(
            listOf(
                "A. 第一选项正文 第一选项的续行文字",
                "B. 第二选项正文",
                "C. 第三选项正文",
                "D. 第四选项正文"
            ),
            lines
        )
    }

    // ========================
    // ⑥⑭ 同一视口重复识别：池不增长，也不产生重复合并副作用
    // ========================

    @Test
    fun repeated_identical_viewport_does_not_grow_the_pool() {
        val pool = pool()

        assertEquals(PoolUpdateResult.NEW_QUESTION, pool.observe(fragment("v1", screenA, 1_000)))
        val textAfterFirst = pool.currentQuestion()!!.text

        for (at in listOf(2_000L, 3_000L, 4_000L, 5_000L)) {
            assertEquals(
                "重复识别同一视口必须返回无变化",
                PoolUpdateResult.NO_CHANGE,
                pool.observe(fragment("v1", screenA, at))
            )
        }

        assertEquals(1, pool.fragmentCount())
        assertEquals(textAfterFirst, pool.currentQuestion()!!.text)
    }

    // ========================
    // ⑦ 切换到明显不同的题目：建立新上下文，且不继承旧题文本
    // ========================

    @Test
    fun switching_to_a_different_question_creates_a_fresh_context() {
        val pool = pool()
        pool.observe(fragment("v1", screenA, 1_000))

        val result = pool.observe(fragment("v2", otherQuestion, 2_000))

        assertEquals(PoolUpdateResult.NEW_QUESTION, result)
        val snapshot = pool.currentQuestion()!!
        assertEquals(otherQuestion, snapshot.text)
        assertTrue("新题不得继承旧题文本", !snapshot.text.contains(options[0]))
        assertEquals(1, snapshot.fragments.size)
        assertEquals(1, snapshot.questionNumber)
    }

    // ========================
    // ⑧ OCR 失败 / 空文本：不覆盖既有有效上下文，也不假装完成更新
    // ========================

    @Test
    fun ocr_failure_does_not_overwrite_existing_context() {
        val pool = pool()
        pool.observe(fragment("v1", screenA, 1_000))
        val before = pool.currentQuestion()!!

        assertEquals(PoolUpdateResult.OCR_FAILED, pool.observeFailure())
        assertEquals(before.text, pool.currentQuestion()!!.text)
        assertEquals(before.updatedAtMillis, pool.currentQuestion()!!.updatedAtMillis)

        // 空文本片段同样不得被当成一次有效更新。
        assertEquals(PoolUpdateResult.OCR_FAILED, pool.observe(noCoordinateFragment("v2", "", 2_000)))
        assertEquals(before.text, pool.currentQuestion()!!.text)
        assertEquals(1, pool.fragmentCount())
    }

    @Test
    fun ocr_failure_on_an_empty_pool_does_not_create_a_context() {
        val pool = pool()

        assertEquals(PoolUpdateResult.OCR_FAILED, pool.observeFailure())
        assertNull(pool.currentQuestion())
    }

    // ========================
    // ⑩ 容量上限：按明确规则淘汰旧片段
    // ========================

    @Test
    fun fragment_capacity_is_bounded_and_evicts_the_oldest() {
        val pool = pool(maxFragments = 2)
        // 三屏各追加一段新内容，确保每次观察都会真正追加片段。
        val firstScreen = (stem + options.take(2)).joinToString("\n")
        val secondScreen = (listOf(stem.last()) + options.take(3)).joinToString("\n")
        val thirdScreen = options.takeLast(2).joinToString("\n")

        assertEquals(PoolUpdateResult.NEW_QUESTION, pool.observe(fragment("v1", firstScreen, 1_000)))
        assertEquals(PoolUpdateResult.OVERLAP_MERGED, pool.observe(fragment("v2", secondScreen, 2_000)))
        assertEquals(PoolUpdateResult.OVERLAP_MERGED, pool.observe(fragment("v3", thirdScreen, 3_000)))

        val snapshot = pool.currentQuestion()!!
        assertEquals(2, snapshot.fragments.size)
        assertEquals("最先采集的片段必须被淘汰", listOf("v2", "v3"), snapshot.fragments.map { it.id })
        // 合并文本仍然保留（受 maxMergedChars 约束），因此内容不因淘汰而丢失。
        assertEquals((stem + options).joinToString("\n"), snapshot.text)
    }

    // ========================
    // ⑪ 合并不得添加 OCR 中不存在的文字
    // ========================

    @Test
    fun merged_text_never_invents_characters() {
        val pool = pool()
        pool.observe(fragment("v1", screenA, 1_000))
        pool.observe(fragment("v2", screenB, 2_000))
        pool.observe(fragment("v3", screenC, 3_000))

        val snapshot = pool.currentQuestion()!!
        val inputChars = (screenA + screenB + screenC).filter { !it.isWhitespace() }.toSet()
        val invented = nonWhitespace(snapshot.text).filter { it !in inputChars }

        assertTrue("不得补写 OCR 未识别的字词，实际多出：$invented", invented.isEmpty())
    }

    // ========================
    // ⑫ 已冻结的请求快照不因后续池更新而改变
    // ========================

    @Test
    fun frozen_snapshot_is_not_mutated_by_later_updates() {
        val pool = pool()
        pool.observe(fragment("v1", screenA, 1_000))
        val frozen = pool.currentQuestion()!!

        pool.observe(fragment("v2", screenB, 2_000))

        assertEquals("已发出的快照文本不得被后续更新改写", screenA, frozen.text)
        assertEquals(1, frozen.fragments.size)
        assertEquals("v1", frozen.fragments.single().id)
        assertNotEquals(frozen.text, pool.currentQuestion()!!.text)
    }

    // ========================
    // R1：无障碍短文本 + 同一道题的 OCR 超集视口 —— 必须能合并
    // ========================

    @Test
    fun accessibility_short_text_is_extended_when_the_ocr_viewport_contains_it_wholesale() {
        val pool = pool()

        // ① 无障碍先建立上下文：只有一段题干（40 余字），且整段挤在一个控件里（一行）。
        assertEquals(
            PoolUpdateResult.NEW_QUESTION,
            pool.observe(accessibilityFragment("a11y-1", accessibilityStemLine, 1_000))
        )
        assertEquals(accessibilityStemLine, pool.currentQuestion()!!.text)

        // ② 同一道题的 OCR 视口：行切分与无障碍完全不同，但把这段题干去空白后**整体**包含在内。
        //    修复前这里会返回 UNSTABLE（行比对键对不上），文本池永远停在无障碍那几十个字上。
        val result = pool.observe(fragment("ocr-1", (stem + options).joinToString("\n"), 2_000))

        assertEquals(PoolUpdateResult.OVERLAP_MERGED, result)
        assertEquals((stem + options).joinToString("\n"), pool.currentQuestion()!!.text)
        assertEquals(2, pool.fragmentCount())
        assertTrue(pool.lastDecisionNote()?.contains("整体出现在本次视口中") == true)
    }

    @Test
    fun repeated_ocr_after_extension_does_not_accumulate_duplicates() {
        val pool = pool()
        pool.observe(accessibilityFragment("a11y-1", accessibilityStemLine, 1_000))
        pool.observe(fragment("ocr-1", (stem + options).joinToString("\n"), 2_000))
        val afterMerge = pool.currentQuestion()!!.text
        val fragmentsAfterMerge = pool.fragmentCount()

        // 同一道题重复 OCR：不得重复累加相同内容，也不得新增片段。
        for (at in listOf(3_000L, 4_000L)) {
            assertEquals(
                PoolUpdateResult.NO_CHANGE,
                pool.observe(fragment("ocr-1-again", (stem + options).joinToString("\n"), at))
            )
        }

        assertEquals(afterMerge, pool.currentQuestion()!!.text)
        assertEquals(fragmentsAfterMerge, pool.fragmentCount())
    }

    @Test
    fun unrelated_ocr_viewport_keeps_unproven_after_accessibility_context() {
        val pool = pool()
        pool.observe(accessibilityFragment("a11y-1", accessibilityStemLine, 1_000))
        val before = pool.currentQuestion()!!.text

        // 缺少可靠重叠证据与同题证据的另一道题：必须保守判为关联未知，不得误合并、也不得认作同题。
        val result = pool.observe(fragment("ocr-other", otherQuestion, 2_000))

        assertEquals(PoolUpdateResult.UNPROVEN, result)
        assertEquals("关联未知时既有内容必须原样保留", before, pool.currentQuestion()!!.text)
        assertEquals(1, pool.fragmentCount())
        assertTrue(pool.lastDecisionNote()?.contains("无法确定是滚动还是换题") == true)
    }

    @Test
    fun ocr_with_a_different_question_number_starts_a_new_question_after_accessibility_context() {
        val pool = pool()
        pool.observe(
            accessibilityFragment("a11y-1", "言语理解与表达 第 11/15 题\n$accessibilityStemLine", 1_000)
        )

        // 新题的 OCR 带**不同题号**：题号冲突是比「字符级包含」更强的正向证据，必须优先判新题，
        // 且新上下文不得继承旧题的片段。
        val nextQuestionScreen = (
            listOf(
                "言语理解与表达 第 12/15 题",
                "下列填入横线处最恰当的一项是：",
                stem[2],
                stem[3]
            ) + options
            ).joinToString("\n")
        val result = pool.observe(fragment("ocr-12", nextQuestionScreen, 2_000))

        assertEquals(PoolUpdateResult.NEW_QUESTION, result)
        val snapshot = pool.currentQuestion()!!
        assertEquals(nextQuestionScreen, snapshot.text)
        assertEquals(12, snapshot.questionNumber)
        assertEquals("新题上下文不得继承旧题片段", listOf("ocr-12"), snapshot.fragments.map { it.id })
    }

    // ========================
    // R3：分类器与合并器规则对齐 —— 非连续共享行必须能真正落池
    // ========================

    @Test
    fun non_contiguous_shared_lines_extend_the_pool_when_the_existing_lines_appear_in_order() {
        // 核心复现：既有（无障碍）短文本仅 3 行；同一道题的 OCR 视口在既有行**中间插入**了一行识别碎片，
        // 再补上后续题干与四个选项。边界不连续、也不构成字符级整体包含，
        // 但既有行按原顺序全部出现在 OCR 视口中 —— 属于可证明的同题证据，文本池必须能安全扩展
        // （修复前这里会因「分类说扩展、合并器无对应规则」而返回 UNSTABLE，池一直停在那几十个字上）。
        val pool = pool()
        val accessibilityLines = listOf(stem[0], stem[1], stem[2])
        val accessibilityText = accessibilityLines.joinToString("\n")

        assertEquals(
            PoolUpdateResult.NEW_QUESTION,
            pool.observe(accessibilityFragment("a11y-1", accessibilityText, 1_000))
        )
        assertEquals(accessibilityText, pool.currentQuestion()!!.text)

        val ocrFragment = fragment(
            "ocr-1",
            (listOf(stem[0], stem[1], "识别到的一行额外OCR碎片", stem[2], stem[3]) + options)
                .joinToString("\n"),
            2_000
        )
        val result = pool.observe(ocrFragment)

        assertEquals(PoolUpdateResult.OVERLAP_MERGED, result)
        val snapshot = pool.currentQuestion()!!
        assertEquals("以新视口为准，既有内容不得丢失", ocrFragment.text, snapshot.text)
        assertTrue("既有内容每一行都必须保留", accessibilityLines.all { it in snapshot.lines })
        assertEquals("合并结果不得出现重复行", snapshot.lines.size, snapshot.lines.distinct().size)
        assertEquals(2, pool.fragmentCount())
    }

    @Test
    fun repeated_ocr_viewport_after_a_non_contiguous_merge_does_not_accumulate() {
        val pool = pool()
        val accessibilityText = listOf(stem[0], stem[1], stem[2]).joinToString("\n")
        pool.observe(accessibilityFragment("a11y-1", accessibilityText, 1_000))
        val ocrText = (listOf(stem[0], stem[1], "识别到的一行额外OCR碎片", stem[2], stem[3]) + options)
            .joinToString("\n")
        pool.observe(fragment("ocr-1", ocrText, 2_000))
        val afterMerge = pool.currentQuestion()!!.text
        val fragmentsAfterMerge = pool.fragmentCount()

        // 同一视口重复识别：不得重复累加相同内容，也不得新增片段。
        assertEquals(PoolUpdateResult.NO_CHANGE, pool.observe(fragment("ocr-1-again", ocrText, 3_000)))

        assertEquals(afterMerge, pool.currentQuestion()!!.text)
        assertEquals(fragmentsAfterMerge, pool.fragmentCount())
    }

    @Test
    fun high_overlap_with_a_missing_existing_line_becomes_a_separate_segment() {
        // 共享行比例很高（6 行里 4 行一致，共享连续材料 ≥ 阈值），但既有内容有一行在本次视口中找不到 ——
        // 无法证明行序完整（可能被遮挡 / 跨题 / 共用阅读材料导致歧义），因此**不得**并入既有正文。
        // R8.6 起按同题证据保留为**独立片段**：连续正文原样保留，新片段以分段标记附加，绝不拼接改写。
        val pool = pool()
        pool.observe(
            fragment("v1", (listOf(stem[1], stem[2], stem[3]) + options.take(3)).joinToString("\n"), 1_000)
        )
        val before = pool.currentQuestion()!!.text

        val result = pool.observe(
            fragment("v2", (listOf(stem[1], stem[2], stem[3]) + options[3]).joinToString("\n"), 2_000)
        )

        assertEquals(PoolUpdateResult.NEW_SEGMENT, result)
        val snapshot = pool.currentQuestion()!!
        assertTrue("连续正文必须原样保留且位于最前", snapshot.text.startsWith(before))
        assertTrue("新片段必须以分段标记附加，不得与既有正文拼成伪连续原文",
            snapshot.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK))
        assertTrue("新片段内容必须真正进入可注入文本", snapshot.text.contains(options[3]))
        assertTrue("差异行必须出现在分段标记之后（未插入既有正文中间）",
            snapshot.text.indexOf(options[3]) > snapshot.text.indexOf(ScreenContextPool.SEGMENT_BREAK_MARK))
        assertEquals(1, pool.separateSegmentCount())
        assertEquals(2, pool.fragmentCount())
        assertTrue(pool.lastDecisionNote()?.contains("独立片段") == true)
    }

    // ========================
    // R4：短无障碍种子 vs 完整 OCR 文本池 —— 安全「种子补全」且不破坏切题 / 滚动判定
    // ========================

    @Test
    fun short_accessibility_seed_is_completed_by_a_full_ocr_viewport_of_the_same_screen() {
        val pool = pool()

        // ① 无障碍先建立上下文：短种子（三行、无位图坐标）。
        assertEquals(
            PoolUpdateResult.NEW_QUESTION,
            pool.observe(accessibilityFragment("a11y-1", shortSeedText, 1_000))
        )
        assertEquals(shortSeedText, pool.currentQuestion()!!.text)
        assertTrue(pool.currentQuestion()!!.fragments.single().lines.isEmpty())

        // ② 同一道题的完整 OCR 视口：行切分不同，还在中间插入了碎片行。
        //    修复前这里会落到 ⑬ 兜底 UNSTABLE，文本池永远停在种子那几十个字上（R4 故障现象）。
        val ocrFragment = fragment("ocr-1", interleavedOcrText, 2_000)
        val result = pool.observe(ocrFragment)

        assertEquals(PoolUpdateResult.OVERLAP_MERGED, result)
        val snapshot = pool.currentQuestion()!!
        assertEquals("以新视口为准，完整 OCR 文本必须真正进入池", ocrFragment.text, snapshot.text)
        assertTrue(pool.lastDecisionNote()?.contains("按原顺序逐字出现在本次视口中") == true)

        // 既有内容的字一个都不能丢，也不得出现重复行。
        assertTrue("既有内容的字不得丢失", nonWhitespace(snapshot.text).toSet().containsAll(nonWhitespace(shortSeedText).toSet()))
        assertEquals("合并结果不得出现重复行", snapshot.lines.size, snapshot.lines.distinct().size)
        assertEquals(2, pool.fragmentCount())
        assertEquals(QuestionCompleteness.COMPLETE, snapshot.completeness)
    }

    @Test
    fun repeated_ocr_viewport_after_seed_completion_does_not_accumulate() {
        val pool = pool()
        pool.observe(accessibilityFragment("a11y-1", shortSeedText, 1_000))
        pool.observe(fragment("ocr-1", interleavedOcrText, 2_000))
        val afterCompletion = pool.currentQuestion()!!.text
        val fragmentsAfterCompletion = pool.fragmentCount()

        for (at in listOf(3_000L, 4_000L)) {
            assertEquals(
                "重复识别同一视口必须返回无变化，不得无限追加",
                PoolUpdateResult.NO_CHANGE,
                pool.observe(fragment("ocr-1-again", interleavedOcrText, at))
            )
        }

        assertEquals(afterCompletion, pool.currentQuestion()!!.text)
        assertEquals(fragmentsAfterCompletion, pool.fragmentCount())
        val lines = pool.currentQuestion()!!.lines
        assertEquals("重复识别不得产生重复行", lines.size, lines.distinct().size)
    }

    @Test
    fun a_genuinely_new_question_is_not_merged_into_the_seed_context() {
        val pool = pool()
        pool.observe(accessibilityFragment("a11y-1", shortSeedText, 1_000))
        val before = pool.currentQuestion()!!.text

        // 同一 App、双方都解析不出题号、文字看着都像题面，但既没有连续重叠、也无足够长的共享材料 ——
        // 必须保守判为关联未知：绝不把新题并入上一题，也绝不把该片段伪装成已确认的同题正文。
        val result = pool.observe(
            fragment(
                "ocr-other",
                listOf(
                    "下列结论中不能由题干推出的是：",
                    "甲的身高高于乙，乙的身高高于丙，则",
                    "A. 丙的身高最矮",
                    "B. 甲的身高最高"
                ).joinToString("\n"),
                2_000
            )
        )

        assertEquals(PoolUpdateResult.UNPROVEN, result)
        assertEquals("关联未知时既有内容必须原样保留", before, pool.currentQuestion()!!.text)
        assertEquals(1, pool.fragmentCount())
        assertEquals(0, pool.separateSegmentCount())
    }

    @Test
    fun incomplete_or_failed_ocr_does_not_overwrite_the_established_seed() {
        val pool = pool()
        pool.observe(accessibilityFragment("a11y-1", shortSeedText, 1_000))
        val before = pool.currentQuestion()!!

        // OCR 只识别出既有内容里的一小段（不完整识别）：不得覆盖、不得追加。
        assertEquals(
            PoolUpdateResult.DUPLICATE,
            pool.observe(fragment("ocr-partial", shortSeedLines[1], 2_000))
        )
        assertEquals(before.text, pool.currentQuestion()!!.text)

        // 读取失败 / 空文本：同样不得覆盖可靠既有文本。
        assertEquals(PoolUpdateResult.OCR_FAILED, pool.observeFailure())
        assertEquals(before.text, pool.currentQuestion()!!.text)
        assertEquals(1, pool.fragmentCount())
    }

    @Test
    fun detector_and_merger_agree_on_the_interleaved_ocr_viewport() {
        // 分类器与合并器必须对同一输入给出一致结论：分类说「同题内容扩展」，合并就必须能真正完成。
        val decision = ScreenChangeDetector.classify(
            ScreenObservation(
                text = shortSeedText,
                capturedAtMillis = 1_000,
                windowKey = "com.qbank.app",
                hasCoordinates = false
            ),
            ScreenObservation(
                text = interleavedOcrText,
                capturedAtMillis = 2_000,
                windowKey = "com.qbank.app",
                hasCoordinates = true
            )
        )
        assertEquals(ScreenChangeKind.CONTENT_EXTENDED, decision.kind)

        val merged = ViewportMerger.merge(MergeSide(ocrLines(shortSeedText)), MergeSide(ocrLines(interleavedOcrText)))
        assertEquals(MergeOutcome.OVERLAP_MERGED, merged.outcome)
        assertEquals(ocrLines(interleavedOcrText), merged.lines)
    }

    // ========================
    // 显式建立新题目 / 清空
    // ========================

    @Test
    fun explicit_new_question_starts_an_empty_context_with_its_own_key() {
        val pool = pool()
        pool.observe(fragment("v1", screenA, 1_000))
        val firstKey = pool.currentQuestion()!!.questionKey

        val snapshot = pool.startNewQuestion(questionKey = "custom-key", atMillis = 2_000)

        assertEquals("custom-key", snapshot.questionKey)
        assertEquals("", snapshot.text)
        assertEquals(0, snapshot.fragments.size)
        assertEquals(QuestionCompleteness.UNKNOWN, snapshot.completeness)
        assertNotEquals(firstKey, snapshot.questionKey)
    }

    @Test
    fun clear_removes_all_question_state() {
        val pool = pool()
        pool.observe(fragment("v1", screenA, 1_000))

        pool.clear()

        assertNull(pool.currentQuestion())
        assertEquals(0, pool.fragmentCount())
    }
}
