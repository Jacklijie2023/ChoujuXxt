package `fun`.kirari.hanako.screencontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地 OCR 结构化整理的纯逻辑单元测试（**不联网、不依赖实时截图**）。
 *
 * 覆盖真机暴露的四类问题：
 * 1. 题干被引擎拆成多行、且返回顺序与屏幕阅读顺序不一致；
 * 2. A/B/C/D 标签与选项正文被拆成不同的「行」返回，导致标签集中、正文分散；
 * 3. 选项正文跨多行，续行必须归入正确选项而不是相邻选项；
 * 4. 选项标记缺失或标签无法与正文可靠对应时，必须标记为「不完整」而不是当成功。
 *
 * 核心约束：整理过程**只按坐标排序与关联**，绝不补写、猜测或删除任何字符 ——
 * 因此每个用例都可以用「整理前后的非空白字符多重集完全相等」来校验。
 */
class OcrTextOrganizationTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrTextLine(text = text, left = left, top = top, right = right, bottom = bottom)

    /** 非空白字符的多重集：用于校验「整理过程没有擅自增删文字」。 */
    private fun nonWhitespaceChars(text: String): List<Char> =
        text.filter { !it.isWhitespace() }.toList().sorted()

    // ========================
    // 1. 同一段题干被分割成多个文字行（且返回顺序与阅读顺序不一致）
    // ========================

    @Test
    fun segmented_stem_is_ordered_by_position_and_keeps_paragraph_breaks() {
        // 引擎返回顺序是乱的（第三行在最前），整理必须按坐标恢复为自上而下的阅读顺序。
        val result = OcrResult(
            rawText = "第三行题干\n第一行题干\n第二行题干",
            lines = listOf(
                line("第三行题干", 20, 180, 300, 210),
                line("第一行题干", 20, 100, 300, 130),
                line("第二行题干", 20, 140, 300, 170)
            )
        )

        val organization = organizeOcrText(result)

        assertEquals("第一行题干\n第二行题干\n第三行题干", organization.text)
        assertEquals("三行必须被识别为三个视觉行", 3, organization.rowCount)
        assertEquals(0, organization.optionMarkerCount)
        assertEquals(OcrOptionAssociation.NONE, organization.association)
        assertEquals("未检出任何选项标签", organization.note)
    }

    // ========================
    // 2. A/B/C/D 标签与选项正文分离（同一水平行的左右两列，或多栏并排）
    // ========================

    @Test
    fun labels_split_from_option_bodies_in_the_same_row_are_recombined() {
        // 模拟真机：引擎把「A」「B」「C」「D」与各自正文拆成不同条目返回，且先后顺序散布。
        val lines = listOf(
            line("选项四", 280, 350, 420, 380),
            line("D", 240, 350, 265, 380),
            line("A", 20, 300, 45, 330),
            line("选项一", 60, 300, 220, 330),
            line("C", 20, 350, 45, 380),
            line("选项三", 60, 350, 220, 380),
            line("B", 240, 300, 265, 330),
            line("选项二", 280, 300, 420, 330)
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        // 标签在前、正文紧随其后，并排的 A/B 与 C/D 不会互相串位。
        assertEquals("A 选项一\nB 选项二\nC 选项三\nD 选项四", organization.text)
        assertEquals(4, organization.optionMarkerCount)
        assertEquals(listOf("A", "B", "C", "D"), organization.optionLabels)
        assertEquals(OcrOptionAssociation.COMPLETE, organization.association)
        assertNull("标签与正文可靠对应时不应给出不完整提示", organization.note)
    }

    @Test
    fun labels_alone_on_their_own_line_are_still_recognized() {
        // 真机 `选项标记=0` 的直接场景：标签整行只有字母、且不带任何标点。
        val lines = listOf(
            line("A", 20, 300, 45, 330),
            line("选项一", 20, 340, 300, 370),
            line("B", 20, 400, 45, 430),
            line("选项二", 20, 440, 300, 470)
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        assertEquals("A 选项一\nB 选项二", organization.text)
        assertEquals("单独成行的字母标签必须被计入选项标记", 2, organization.optionMarkerCount)
        assertEquals(OcrOptionAssociation.COMPLETE, organization.association)
    }

    @Test
    fun circled_option_labels_are_recognized() {
        val lines = listOf(
            line("Ⓐ", 20, 300, 60, 330),
            line("选项一", 70, 300, 300, 330),
            line("Ⓑ", 20, 350, 60, 380),
            line("选项二", 70, 350, 300, 380)
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        assertEquals("Ⓐ 选项一\nⒷ 选项二", organization.text)
        assertEquals(2, organization.optionMarkerCount)
        assertEquals(OcrOptionAssociation.COMPLETE, organization.association)
    }

    @Test
    fun inline_punctuated_labels_with_bodies_are_complete() {
        val lines = listOf(
            line("A. 选项一", 20, 300, 300, 330),
            line("B、选项二", 20, 350, 300, 380),
            line("C．选项三", 20, 400, 300, 430),
            line("D）选项四", 20, 450, 300, 480)
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        assertEquals("A. 选项一\nB、选项二\nC．选项三\nD）选项四", organization.text)
        assertEquals(4, organization.optionMarkerCount)
        assertEquals(OcrOptionAssociation.COMPLETE, organization.association)
    }

    // ========================
    // 3. 选项正文跨多行：续行必须归入正确选项，不能错归相邻选项
    // ========================

    @Test
    fun multi_line_option_body_is_attached_to_its_own_option() {
        val lines = listOf(
            line("A. 第一选项正文", 20, 300, 300, 330),
            line("第一选项的续行文字", 60, 340, 300, 370),
            line("B. 第二选项正文", 20, 420, 300, 450)
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        assertEquals(
            "A. 第一选项正文 第一选项的续行文字\nB. 第二选项正文",
            organization.text
        )
        assertEquals(2, organization.optionMarkerCount)
        assertEquals(OcrOptionAssociation.COMPLETE, organization.association)
    }

    @Test
    fun option_continuation_is_not_misassigned_to_the_neighbouring_option() {
        // 续行紧贴 A、且在 B 上方：只能并入 A，绝不能被 B「抢走」。
        val lines = listOf(
            line("A. 第一选项正文", 20, 300, 300, 330),
            line("A 的续行", 60, 340, 300, 370),
            line("B. 第二选项正文", 20, 380, 300, 410)
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        assertEquals("A. 第一选项正文 A 的续行\nB. 第二选项正文", organization.text)
        assertEquals(OcrOptionAssociation.COMPLETE, organization.association)
    }

    // ========================
    // 4. 多个文字块/条目返回顺序与屏幕自然阅读顺序不一致
    // ========================

    @Test
    fun interleaved_block_order_is_restored_to_natural_reading_order() {
        val lines = listOf(
            line("B. 选项二正文", 20, 400, 300, 430),
            line("下列填入横线最恰当的一项是：", 20, 200, 500, 230),
            line("A. 选项一正文", 20, 350, 300, 380),
            line("言语理解与表达 第 11 题", 20, 160, 400, 190)
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        assertEquals(
            "言语理解与表达 第 11 题\n下列填入横线最恰当的一项是：\nA. 选项一正文\nB. 选项二正文",
            organization.text
        )
        assertEquals(OcrOptionAssociation.COMPLETE, organization.association)
    }

    // ========================
    // 5. 题干、选项与屏幕顶部状态栏文字混杂
    // ========================

    @Test
    fun status_bar_text_is_kept_and_stays_ahead_of_the_stem() {
        val lines = listOf(
            line("A", 20, 300, 45, 330),
            line("选项一", 60, 300, 220, 330),
            line("计时 12:30", 30, 20, 200, 50),
            line("题干：下列填入横线最恰当的一项是", 20, 150, 500, 180),
            line("选项二", 60, 350, 220, 380),
            line("B", 20, 350, 45, 380)
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        // 状态栏在最上方，按读书顺序排在最前；它不参与选项关联。
        assertEquals(
            "计时 12:30\n题干：下列填入横线最恰当的一项是\nA 选项一\nB 选项二",
            organization.text
        )
        assertEquals(2, organization.optionMarkerCount)
        assertEquals(OcrOptionAssociation.COMPLETE, organization.association)
    }

    // ========================
    // 6. 选项标记缺失、标签无法与正文可靠对应 → 必须标记不完整
    // ========================

    @Test
    fun no_option_labels_at_all_is_marked_as_none() {
        val lines = listOf(
            line("A 股市场今天上涨", 20, 200, 400, 230),
            line("这里提到了 B 方案与 C 方案", 20, 240, 500, 270),
            line("但都不构成选项结构", 20, 280, 500, 310)
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        // 全文出现 A/B/C 的普通文本不算选项：无标点、非整行单字母。
        assertEquals(0, organization.optionMarkerCount)
        assertEquals(OcrOptionAssociation.NONE, organization.association)
        assertEquals("未检出任何选项标签", organization.note)
    }

    @Test
    fun label_without_reliable_body_is_marked_partial() {
        val lines = listOf(
            line("A", 20, 300, 45, 330),
            line("选项一", 60, 300, 220, 330),
            line("B", 20, 350, 45, 380) // B 没有关联到任何正文
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        assertEquals(OcrOptionAssociation.PARTIAL, organization.association)
        assertTrue(
            "必须明确说明哪个标签未关联到正文",
            organization.note?.contains("未关联到正文") == true
        )
    }

    @Test
    fun out_of_order_labels_are_marked_partial() {
        val lines = listOf(
            line("B", 20, 300, 45, 330),
            line("选项二", 60, 300, 220, 330),
            line("A", 20, 350, 45, 380),
            line("选项一", 60, 350, 220, 380)
        )
        val result = OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)

        val organization = organizeOcrText(result)

        assertEquals(OcrOptionAssociation.PARTIAL, organization.association)
        assertTrue(
            "顺序不连续必须被指出",
            organization.note?.contains("顺序不连续") == true
        )
    }

    @Test
    fun single_label_is_not_enough_to_claim_an_option_structure() {
        val lines = listOf(
            line("A. 只有一个标签", 20, 300, 300, 330)
        )
        val organization = organizeOcrText(OcrResult("A. 只有一个标签", lines))

        assertEquals(OcrOptionAssociation.PARTIAL, organization.association)
        assertTrue(organization.note?.contains("不足以断定选项结构") == true)
    }

    @Test
    fun missing_line_coordinates_fall_back_to_raw_text_and_report_the_limitation() {
        // 引擎没有返回任何行级坐标时，如实退回原始文本并说明「无法核对」，不假装整理成功。
        val organization = organizeOcrText(
            OcrResult(rawText = "A. 甲\nB. 乙\n随意一句", lines = emptyList())
        )

        assertEquals("A. 甲\nB. 乙\n随意一句", organization.text)
        assertEquals(0, organization.rowCount)
        assertEquals(2, organization.optionMarkerCount)
        assertEquals(OcrOptionAssociation.PARTIAL, organization.association)
        assertTrue(organization.note?.contains("未返回行级坐标") == true)
    }

    // ========================
    // 7. 原始 OCR 文字与整理后文本的一致性（整理过程不得擅自增删文字）
    // ========================

    @Test
    fun organized_text_never_invents_or_drops_characters() {
        val lines = listOf(
            line("题干第一行文字", 20, 100, 300, 130),
            line("题干第二行文字", 20, 140, 300, 170),
            line("A", 20, 300, 45, 330),
            line("选项一内容", 60, 300, 220, 330),
            line("选项一续行", 60, 340, 220, 370),
            line("B. 选项二内容", 20, 400, 300, 430),
            line("C．选项三内容", 20, 450, 300, 480),
            line("D）选项四内容", 20, 500, 300, 530)
        )
        val rawText = lines.joinToString("\n") { it.text }

        val organization = organizeOcrText(OcrResult(rawText = rawText, lines = lines))

        assertEquals(
            "整理前后的非空白字符多重集必须完全一致：既不能补写文字，也不能丢字",
            nonWhitespaceChars(rawText),
            nonWhitespaceChars(organization.text)
        )
        assertEquals(OcrOptionAssociation.COMPLETE, organization.association)
        assertTrue("A 的续行必须被并入 A", organization.text.contains("选项一内容 选项一续行"))
    }

    @Test
    fun organized_text_preserves_characters_for_unreliable_option_structures_too() {
        // 即使选项关联不可靠，整理结果也只能是原文的「重排」，不得为了让结构好看而加字。
        val lines = listOf(
            line("题干只有一句话", 20, 100, 300, 130),
            line("B", 20, 300, 45, 330),
            line("选项二内容", 60, 300, 220, 330)
        )
        val rawText = lines.joinToString("\n") { it.text }

        val organization = organizeOcrText(OcrResult(rawText = rawText, lines = lines))

        assertEquals(OcrOptionAssociation.PARTIAL, organization.association)
        assertEquals(nonWhitespaceChars(rawText), nonWhitespaceChars(organization.text))
    }

    // ========================
    // 8. 暴露给多视口合并层的行级 / 分组结果（阶段 1 新增）
    // ========================

    @Test
    fun organized_result_exposes_engine_ordered_lines_and_reading_order_rows() {
        val lines = listOf(
            line("题干第一行", 20, 100, 300, 130),
            line("题干第二行", 20, 140, 300, 170),
            line("A", 20, 300, 45, 330),
            line("选项一", 60, 300, 220, 330)
        )
        val organization = organizeOcrText(
            OcrResult(rawText = lines.joinToString("\n") { it.text }, lines = lines)
        )

        // lines 必须原样保留引擎返回顺序（供合并层核对来源），rows 才是按阅读顺序分组的结果。
        assertEquals(lines, organization.lines)
        assertEquals(3, organization.rows.size)
        assertEquals(listOf("A", "选项一"), organization.rows[2].map { it.text })
        // 既有输出语义不受新增字段影响。
        assertEquals("题干第一行\n题干第二行\nA 选项一", organization.text)
    }

    @Test
    fun organized_result_reports_empty_lines_and_rows_when_coordinates_are_missing() {
        val organization = organizeOcrText(OcrResult(rawText = "A. 甲\nB. 乙", lines = emptyList()))

        // 无坐标时不虚构行与分组：调用方必须据此走降级路径。
        assertTrue(organization.lines.isEmpty())
        assertTrue(organization.rows.isEmpty())
        assertEquals("A. 甲\nB. 乙", organization.text)
    }
}
