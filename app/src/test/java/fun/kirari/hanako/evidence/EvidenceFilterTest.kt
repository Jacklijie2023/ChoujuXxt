package `fun`.kirari.hanako.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原始证据过滤器的纯 JVM 单测。
 *
 * 关注点（对应需求 §4）：
 * - 只有**确定是界面噪声**才 EXCLUDE；
 * - 把握不足一律 UNCERTAIN（仍保留、可回查）；
 * - 选项 / 数字 / 单位 / 比例 / 表格结构不得被当作噪声删除；
 * - 过滤是派生层：原始证据文本一字不改。
 */
class EvidenceFilterTest {

    private val ownPackage = "fun.kirari.hanako"
    private val targetPackage = "com.exam.app"

    private fun record(
        sourceApp: String? = targetPackage,
        rawText: String = "",
        rawNodes: List<EvidenceNode> = emptyList(),
        ocrLines: List<EvidenceOcrLine> = emptyList(),
        confidence: Float? = null,
        evidenceId: String = "ev-1"
    ) = EvidenceRecord(
        evidenceId = evidenceId,
        eventId = "event-1",
        sessionId = "session-1",
        sequence = 1L,
        capturedAtMillis = 1_000L,
        sourceApp = sourceApp,
        windowId = "win-1",
        windowType = "application",
        viewportId = "viewport-1",
        questionId = "q-1",
        sourceType = if (ocrLines.isNotEmpty()) EvidenceSourceType.OCR else EvidenceSourceType.ACCESSIBILITY,
        rawText = rawText,
        rawNodes = rawNodes,
        ocrLines = ocrLines,
        contentHash = "hash",
        confidence = confidence
    )

    private fun context(
        sourceApp: String? = targetPackage,
        boundTargetPackage: String? = targetPackage,
        screenHeight: Int? = null
    ) = FilterContext(
        sourceApp = sourceApp,
        ownPackage = ownPackage,
        windowType = "application",
        screenWidth = 1080,
        screenHeight = screenHeight,
        boundTargetPackage = boundTargetPackage
    )

    private fun classify(
        evidence: EvidenceRecord,
        ctx: FilterContext = context(evidence.sourceApp)
    ): List<ClassifiedBlock> = EvidenceFilter.classify(evidence, ctx, processedAtMillis = 2_000L)

    // ========================
    // 1. 确定性噪声：自身浮层 / 系统界面 / 键盘 / 固定按钮
    // ========================

    @Test
    fun own_overlay_text_is_excluded_with_reason() {
        val blocks = classify(record(sourceApp = ownPackage, rawText = "小题讲解"))

        assertEquals(BlockVerdict.EXCLUDE, blocks.single().verdict)
        assertEquals(BlockCategory.SELF_OVERLAY, blocks.single().category)
    }

    @Test
    fun system_ui_package_text_is_excluded() {
        val blocks = classify(
            record(sourceApp = "com.android.systemui", rawText = "18:30"),
            context(sourceApp = "com.android.systemui")
        )

        assertEquals(BlockVerdict.EXCLUDE, blocks.single().verdict)
        assertEquals(BlockCategory.SYSTEM_UI, blocks.single().category)
    }

    @Test
    fun keyboard_node_class_is_excluded() {
        val blocks = classify(
            record(
                rawNodes = listOf(
                    EvidenceNode(text = "q", className = "android.inputmethodservice.KeyboardView")
                )
            )
        )

        assertEquals(BlockVerdict.EXCLUDE, blocks.single().verdict)
        assertEquals(BlockCategory.KEYBOARD, blocks.single().category)
    }

    @Test
    fun editable_input_content_is_excluded() {
        val blocks = classify(
            record(rawNodes = listOf(EvidenceNode(text = "我输入的答案", editable = true)))
        )

        assertEquals(BlockVerdict.EXCLUDE, blocks.single().verdict)
        assertEquals(BlockCategory.KEYBOARD, blocks.single().category)
    }

    @Test
    fun fixed_ui_button_label_is_excluded_but_same_word_in_stem_is_kept() {
        val button = classify(record(rawText = "下一题"))
        assertEquals(BlockVerdict.EXCLUDE, button.single().verdict)

        // 同名词出现在题干里（整行不等于按钮文案）不得被删除。
        val stem = classify(record(rawText = "关于“下一题”的按钮样式，下列说法正确的是"))
        assertNotEquals(BlockVerdict.EXCLUDE, stem.single().verdict)
    }

    @Test
    fun status_bar_and_navigation_bar_bounds_are_excluded() {
        val statusBar = classify(
            record(ocrLines = listOf(EvidenceOcrLine("18:30", 10, 0, 80, 40))),
            context(screenHeight = 2000)
        )
        assertEquals(BlockVerdict.EXCLUDE, statusBar.single().verdict)
        assertEquals(BlockCategory.SYSTEM_UI, statusBar.single().category)

        val navBar = classify(
            record(ocrLines = listOf(EvidenceOcrLine("底部导航提示", 10, 1900, 80, 1980))),
            context(screenHeight = 2000)
        )
        assertEquals(BlockVerdict.EXCLUDE, navBar.single().verdict)
        assertEquals(BlockCategory.SYSTEM_UI, navBar.single().category)
    }

    // ========================
    // 2. 有效内容必须保留（选项 / 数字 / 单位 / 比例 / 表格 / 图表 / 公式）
    // ========================

    @Test
    fun option_label_with_numbers_and_units_is_kept_as_option() {
        val blocks = classify(
            record(ocrLines = listOf(EvidenceOcrLine("A. 增长率为 12.5%", 20, 500, 900, 560)))
        )

        assertEquals(BlockVerdict.KEEP, blocks.single().verdict)
        assertEquals(BlockCategory.OPTION, blocks.single().category)
        assertTrue("选项正文中的数字与单位必须保留", blocks.single().text.contains("12.5%"))
    }

    @Test
    fun circled_option_markers_are_kept() {
        val blocks = classify(record(rawText = "①数据安全 ②隐私保护"))

        assertEquals(BlockVerdict.KEEP, blocks.single().verdict)
        assertEquals(BlockCategory.OPTION, blocks.single().category)
    }

    @Test
    fun question_number_and_material_hints_are_kept() {
        val numbered = classify(record(rawText = "1. 下列说法正确的是："))
        assertEquals(BlockVerdict.KEEP, numbered.single().verdict)
        assertEquals(BlockCategory.QUESTION_STEM, numbered.single().category)

        val chineseNumbered = classify(record(rawText = "第 12 题 根据以下材料回答"))
        assertEquals(BlockVerdict.KEEP, chineseNumbered.single().verdict)

        val material = classify(record(rawText = "阅读以下材料，回答问题"))
        assertEquals(BlockCategory.MATERIAL, material.single().category)
    }

    @Test
    fun fraction_and_special_symbols_are_kept_as_formula() {
        val fraction = classify(record(rawText = "3/4"))
        assertEquals(BlockVerdict.KEEP, fraction.single().verdict)
        assertEquals(BlockCategory.FORMULA, fraction.single().category)

        val symbol = classify(record(rawText = "a ≥ b"))
        assertEquals(BlockCategory.FORMULA, symbol.single().category)
    }

    @Test
    fun table_like_structure_is_kept() {
        val blocks = classify(record(rawText = "| 年份 | 产量 |"))

        assertEquals(BlockVerdict.KEEP, blocks.single().verdict)
        assertEquals(BlockCategory.TABLE, blocks.single().category)
    }

    @Test
    fun figure_hint_is_kept() {
        val blocks = classify(record(rawText = "如图 1 所示，柱状图反映"))

        assertEquals(BlockVerdict.KEEP, blocks.single().verdict)
        assertEquals(BlockCategory.FIGURE, blocks.single().category)
    }

    // ========================
    // 3. 不确定内容：保留为 UNCERTAIN，不得删除
    // ========================

    @Test
    fun low_confidence_text_becomes_uncertain_not_excluded() {
        val blocks = classify(
            record(
                ocrLines = listOf(EvidenceOcrLine("可能模糊的文字", 20, 500, 900, 560)),
                confidence = 0.1f
            )
        )

        assertEquals(BlockVerdict.UNCERTAIN, blocks.single().verdict)
        assertTrue(blocks.single().reason.contains("置信度"))
    }

    @Test
    fun unconfirmed_source_becomes_uncertain_not_excluded() {
        val blocks = classify(
            record(sourceApp = null, rawText = "数字化转型的路径选择"),
            context(sourceApp = null, boundTargetPackage = null)
        )

        assertEquals(BlockVerdict.UNCERTAIN, blocks.single().verdict)
        assertTrue(blocks.single().reason.contains("来源"))
    }

    @Test
    fun ordinary_text_from_bound_target_app_is_kept_as_auxiliary() {
        val blocks = classify(record(rawText = "数字化转型的路径选择"))

        assertEquals(BlockVerdict.KEEP, blocks.single().verdict)
        assertEquals(BlockCategory.QUESTION_AUXILIARY, blocks.single().category)
    }

    @Test
    fun blank_ocr_line_is_excluded_without_affecting_other_lines() {
        val blocks = classify(
            record(
                ocrLines = listOf(
                    EvidenceOcrLine("题干第一行", 20, 300, 900, 360),
                    EvidenceOcrLine("   ", 20, 370, 900, 400),
                    EvidenceOcrLine("A. 选项一", 20, 410, 900, 470)
                )
            )
        )

        assertEquals(3, blocks.size)
        assertEquals(BlockVerdict.EXCLUDE, blocks[1].verdict)
        assertEquals(BlockVerdict.KEEP, blocks[2].verdict)
        assertEquals("A. 选项一", blocks[2].text)
    }

    // ========================
    // 4. 派生层约束：原文不改、行号一一对应、规则版本明确
    // ========================

    @Test
    fun original_evidence_text_is_never_modified_by_filtering() {
        val original = record(rawText = "1. 题干\n下一题\nA. 选项一\n18:30")
        val before = original.copy()

        val blocks = EvidenceFilter.classify(original, context(sourceApp = targetPackage), 2_000L)

        assertEquals("原始记录必须是不可变值对象", before, original)
        assertEquals(4, blocks.size)
        assertEquals(
            "派生记录必须逐行引用原始行号",
            listOf(0, 1, 2, 3),
            blocks.map { it.lineIndex }
        )
        assertTrue(blocks.all { it.ruleVersion == EvidenceFilter.RULE_VERSION })
        assertTrue(blocks.all { it.evidenceId == original.evidenceId })
    }

    @Test
    fun ocr_lines_take_priority_and_keep_their_bounds() {
        val evidence = record(
            rawText = "被替换的整段文本",
            ocrLines = listOf(
                EvidenceOcrLine("A. 选项一", 20, 400, 900, 460),
                EvidenceOcrLine("B. 选项二", 20, 470, 900, 530)
            )
        )

        val blocks = classify(evidence)

        assertEquals(2, blocks.size)
        assertEquals(
            "派生分类不得改写 OCR 行原文",
            listOf("A. 选项一", "B. 选项二"),
            blocks.map { it.text }
        )
    }
}
