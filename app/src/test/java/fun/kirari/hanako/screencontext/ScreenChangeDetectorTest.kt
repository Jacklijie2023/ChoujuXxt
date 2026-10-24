package `fun`.kirari.hanako.screencontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕变化分类器的纯逻辑单元测试（**不联网、不接入无障碍服务、不触发任何截图**）。
 *
 * 覆盖用户要求的判定：无实质变化 / 同题内容扩展 / 回到已采集视口 / 疑似新题 / 画面不稳定 / OCR 失败，
 * 以及「不得只依赖文本相似度切题」与「拿不准就返回不确定」两条硬约束。
 */
class ScreenChangeDetectorTest {

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

    private fun obs(
        lines: List<String>,
        atMillis: Long,
        windowKey: String? = "com.qbank.app",
        questionNumber: Int? = null,
        comparable: Boolean = true
    ) = ScreenObservation(
        text = lines.joinToString("\n"),
        capturedAtMillis = atMillis,
        windowKey = windowKey,
        questionNumber = questionNumber,
        comparable = comparable
    )

    // ========================
    // 无实质变化
    // ========================

    @Test
    fun identical_content_is_no_change_even_without_time_gap() {
        val text = stem + options
        val decision = ScreenChangeDetector.classify(obs(text, 1_000), obs(text, 1_000))

        assertEquals(ScreenChangeKind.NO_CHANGE, decision.kind)
    }

    @Test
    fun whitespace_only_difference_is_still_no_change() {
        val decision = ScreenChangeDetector.classify(
            obs(listOf("A.选项一内容", "B.选项二内容"), 1_000),
            obs(listOf("A. 选项一内容", "B. 选项二内容"), 5_000)
        )

        assertEquals(ScreenChangeKind.NO_CHANGE, decision.kind)
    }

    // ========================
    // 同题内容扩展
    // ========================

    @Test
    fun adjacent_overlapping_scroll_is_content_extended() {
        val decision = ScreenChangeDetector.classify(
            obs(stem + options.take(2), 1_000),
            obs(listOf(stem.last()) + options, 2_000)
        )

        assertEquals(ScreenChangeKind.CONTENT_EXTENDED, decision.kind)
    }

    @Test
    fun non_contiguous_shared_lines_in_original_order_are_content_extended() {
        // 边界没有连续重叠（新视口中间多了一行 OCR 碎片），既有内容也不是新视口的连续子串，
        // 但既有内容的行**按原顺序**全部出现在新视口中 —— 这是 R3 的行级证据，必须判为同题内容扩展。
        val decision = ScreenChangeDetector.classify(
            obs(listOf("题干第一行文字", "A. 选项一内容", "B. 选项二内容", "C. 选项三内容"), 1_000),
            obs(
                listOf(
                    "题干第一行文字",
                    "中间多出的一行OCR碎片",
                    "A. 选项一内容",
                    "B. 选项二内容",
                    "C. 选项三内容"
                ),
                2_000
            )
        )

        assertEquals(ScreenChangeKind.CONTENT_EXTENDED, decision.kind)
        assertTrue(decision.note?.contains("按原顺序全部出现在本次视口中") == true)
    }

    @Test
    fun high_overlap_with_a_missing_existing_line_becomes_a_separate_segment() {
        // 共享连续字符很长（题干 + 前两个选项 ~23 字），但既有内容有一行在本次视口中找不到 ——
        // 行序无法完整证明，可能被遮挡、跨题或共用材料导致歧义，因此**不得**并入既有正文；
        // R8.6 起改为按同题证据保留为**独立片段**（不拼接、也不丢弃），这正是需求 §4.1 的
        // NEW_SEGMENT_SAME_QUESTION 分支。
        val decision = ScreenChangeDetector.classify(
            obs(listOf("题干第一行文字", "A. 选项一内容", "B. 选项二内容", "C. 选项三内容"), 1_000),
            obs(listOf("题干第一行文字", "A. 选项一内容", "B. 选项二内容", "D. 选项四内容"), 2_000)
        )

        assertEquals(ScreenChangeKind.NEW_SEGMENT, decision.kind)
        assertTrue(decision.note?.contains("独立片段") == true)
        assertTrue("必须明确不拼接既有正文", decision.note?.contains("不拼接既有正文") == true)
        // 诊断必须给出双方行数与去空白字数，便于真机定位「为什么没能合并」。
        assertTrue(decision.note?.contains("既有 4 行 / 本次 4 行") == true)
    }

    @Test
    fun high_shared_lines_with_a_different_question_number_is_still_new_question() {
        // 共享行比例极高（除题号行外整屏一致），但两侧题号不同 —— 题号是更强的正向证据，
        // 必须按既有规则判为新题，不得因行级证据而跨题合并。
        val shared = listOf(stem[1], stem[2], stem[3]) + options
        val decision = ScreenChangeDetector.classify(
            obs(listOf("言语理解与表达 第 11/15 题") + shared, 1_000),
            obs(listOf("言语理解与表达 第 12/15 题") + shared, 2_000)
        )

        assertEquals(ScreenChangeKind.NEW_QUESTION, decision.kind)
        assertTrue(decision.note?.contains("题号") == true)
    }

    // ========================
    // 跨来源行切分口径不同时的「字符级整体包含」兜底证据
    // ========================

    @Test
    fun contained_accessibility_text_is_content_extended_even_when_line_split_differs() {
        // 无障碍节点树按**控件**换行：整段题干挤在同一个节点里（一行）；
        // OCR 按**视觉行**换行：同一段题干被切成两行。两侧的行比对键因此完全对不上
        // （⑨/⑩/⑪ 全部落空），但既有内容去空白后**整体**出现在新视口中 —— 这是可证明的同题证据，
        // 必须能合并；否则文本池会一直停在无障碍读到的那几十个字上（R1 故障现象）。
        val accessibility = obs(listOf("随着数字经济的快速发展，数据要素已成为推动经济增长的重要力量。然而，________"), 1_000)
        val ocrViewport = obs(listOf(stem[0], stem[1], stem[2], stem[3]) + options, 2_000)

        val decision = ScreenChangeDetector.classify(accessibility, ocrViewport)

        assertEquals(ScreenChangeKind.CONTENT_EXTENDED, decision.kind)
        assertTrue(decision.note?.contains("整体出现在本次视口中") == true)
    }

    @Test
    fun contained_text_shorter_than_threshold_is_association_unknown() {
        // 既有内容与本次视口共享一段文字，但共享长度不足 [MIN_SEGMENT_SHARED_CHARS]（此处仅 9 字），
        // 既不能并入既有正文、也不能据此认定同题 —— 保守判为关联未知（保留既有内容、本片段不进入请求）。
        val tooShort = obs(listOf("数据要素市场化配置"), 1_000)
        val ocrViewport = obs(
            listOf(
                "下列填入横线处最恰当的一项是：",
                "数据要素市场化配置亟需制度保障",
                "A. 数据安全与隐私保护面临新的挑战",
                "B. 传统产业数字化转型步伐明显加快"
            ),
            2_000
        )

        val decision = ScreenChangeDetector.classify(tooShort, ocrViewport)

        assertEquals(ScreenChangeKind.ASSOCIATION_UNKNOWN, decision.kind)
        assertTrue(decision.note?.contains("无法确定是滚动还是换题") == true)
    }

    // ========================
    // R4：短无障碍种子 vs 完整 OCR 视口（字符级**有序**子序列兜底证据）
    // ========================

    @Test
    fun accessibility_seed_with_interleaved_ocr_fragments_is_content_extended() {
        // 真机复现：文本池先被一段短的无障碍种子（按控件换行、只有两三行）初始化，
        // 随后同一视口被 OCR 完整识别。OCR 把种子的某一行**切成两行**，还在中间插入了一行碎片，
        // 于是「行级有序子序列」与「字符级整体连续包含」双双落空 ——
        // 但既有内容的每个字都按原顺序在场，必须判为同题内容扩展（否则池会一直停在种子那几十字上）。
        val seed = obs(
            listOf(
                "言语理解与表达 第 11/15 题",
                "下列填入横线处最恰当的一项是：",
                "随着数字经济的快速发展，数据要素已成为推动经济增长的重要力量。然而，________"
            ),
            1_000,
            windowKey = "com.qbank.app"
        )
        val ocrViewport = obs(
            listOf(
                "言语理解与表达 第 11/15 题",
                "下列填入横线处最恰当的一项是：",
                "随着数字经济的快速发展，数据要素已成为",
                "识别到的一行额外 OCR 碎片",
                "推动经济增长的重要力量。然而，________",
                "A. 数据安全与隐私保护面临新的挑战",
                "B. 传统产业数字化转型步伐明显加快"
            ),
            2_000,
            windowKey = "com.qbank.app"
        )

        val decision = ScreenChangeDetector.classify(seed, ocrViewport)

        assertEquals(ScreenChangeKind.CONTENT_EXTENDED, decision.kind)
        assertTrue(decision.note?.contains("按原顺序逐字出现在本次视口中") == true)
    }

    @Test
    fun reordered_seed_characters_are_conservatively_association_unknown() {
        // 既有内容的字都在本次视口中，但顺序被 OCR 的视觉分组打乱 —— 顺序不可证明，
        // 且共享的连续字符只有 6 字（不足阈值）。保守判为关联未知（不合并、不认同题、不进入请求）。
        val decision = ScreenChangeDetector.classify(
            obs(listOf("数字经济推动增长方式转型"), 1_000),
            obs(listOf("转型推动增长方式数字经济", "A. 甲乙丙丁", "B. 戊己庚辛"), 2_000)
        )

        assertEquals(ScreenChangeKind.ASSOCIATION_UNKNOWN, decision.kind)
    }

    @Test
    fun segment_note_reports_a_sanitized_match_summary() {
        // 判定现场的 note 必须给出**脱敏**摘要（只有计数与下标），便于真机定位「为什么没能合并」，
        // 同时绝不泄漏题目正文、选项全文或聊天内容。
        val decision = ScreenChangeDetector.classify(
            obs(listOf("题干第一行文字", "A. 选项一内容", "B. 选项二内容", "C. 选项三内容"), 1_000),
            obs(listOf("题干第一行文字", "A. 选项一内容", "B. 选项二内容", "D. 选项四内容"), 2_000)
        )

        assertEquals(ScreenChangeKind.NEW_SEGMENT, decision.kind)
        val note = decision.note!!
        assertTrue(note.contains("既有行命中 3/4"))
        assertTrue(note.contains("首个未命中第 4 行"))
        assertTrue(note.contains("最长连续公共段"))
        assertTrue(note.contains("既有来源="))
        assertTrue("诊断摘要不得包含题目正文", !note.contains("选项"))
    }

    @Test
    fun association_unknown_note_reports_rule_verdicts_and_first_line_fingerprint() {
        // 判定现场必须给出**逐规则真实结果**（V11/V12/V12b）与**首行脱敏指纹**，
        // 便于真机区分「动态非题目行 / OCR 漏读 / 文本顺序不一致」；
        // 同时**绝不**泄漏题干正文（只允许长度 / 类别位 / 短哈希 / 计数）。
        val decision = ScreenChangeDetector.classify(
            obs(
                listOf("既有首行甲文字", "A. 选项一内容", "B. 选项二内容"),
                1_000
            ),
            obs(
                listOf("本次首行乙文字", "A. 选项一内容", "B. 选项二内容", "C. 选项三内容"),
                2_000
            )
        )

        // 共享连续字符仅 16 字（不足阈值），判为关联未知。
        assertEquals(ScreenChangeKind.ASSOCIATION_UNKNOWN, decision.kind)
        val note = decision.note!!
        // 逐规则结果：三条规则都已被求值并记录真实布尔值（本夹具下均为 false）。
        assertTrue(note.contains("规则结果 V11="))
        assertTrue(note.contains("V12="))
        assertTrue(note.contains("V12b="))
        assertTrue(note.contains("分类结果=ASSOCIATION_UNKNOWN"))
        // 首行脱敏指纹与在场判定的字段必须出现。
        assertTrue(note.contains("既有首行=L"))
        assertTrue(note.contains("本次首行=L"))
        assertTrue(note.contains("既有首行字符在场="))
        assertTrue(note.contains("既有首行有序="))
        // 脱敏底线：不得出现任何题干 / 选项正文。
        assertTrue("诊断不得包含题干正文", !note.contains("既有首行甲文字"))
        assertTrue("诊断不得包含题干正文", !note.contains("本次首行乙文字"))
        assertTrue("诊断不得包含题干正文", !note.contains("选项"))
    }

    @Test
    fun adding_diagnostics_does_not_change_classification_results() {
        // 回归护栏：新增诊断字段**不得**改变任何既有分类结果。
        val seed = obs(listOf("题干第一行文字内容", "A. 选项一内容", "B. 选项二内容"), 1_000)
        val identical = obs(listOf("题干第一行文字内容", "A. 选项一内容", "B. 选项二内容"), 2_000)
        assertEquals(
            "无实质变化不得因诊断而改变",
            ScreenChangeKind.NO_CHANGE,
            ScreenChangeDetector.classify(seed, identical).kind
        )

        val extended = obs(
            listOf("题干第一行文字内容", "A. 选项一内容", "B. 选项二内容", "C. 选项三内容"),
            2_000
        )
        assertEquals(
            "同题扩展不得因诊断而改变",
            ScreenChangeKind.CONTENT_EXTENDED,
            ScreenChangeDetector.classify(seed, extended).kind
        )
    }

    // ========================
    // 回到已采集视口
    // ========================

    @Test
    fun sub_viewport_of_existing_content_is_repeated_viewport() {
        val decision = ScreenChangeDetector.classify(
            obs(stem + options, 1_000),
            obs(options.take(2), 2_000)
        )

        assertEquals(ScreenChangeKind.REPEATED_VIEWPORT, decision.kind)
    }

    // ========================
    // 疑似切换新题：必须基于正向证据，而非文本相似度
    // ========================

    @Test
    fun window_key_change_is_new_question() {
        val decision = ScreenChangeDetector.classify(
            obs(stem + options.take(2), 1_000, windowKey = "com.qbank.app"),
            obs(listOf(stem.last()) + options, 2_000, windowKey = "com.other.reader")
        )

        assertEquals(ScreenChangeKind.NEW_QUESTION, decision.kind)
        assertTrue(decision.note?.contains("目标窗口") == true)
    }

    @Test
    fun question_number_change_is_new_question() {
        val decision = ScreenChangeDetector.classify(
            obs(listOf("言语理解与表达 第 11/15 题", "题干甲", "题干乙"), 1_000),
            obs(listOf("言语理解与表达 第 12/15 题", "另一道题面甲", "另一道题面乙"), 2_000)
        )

        assertEquals(ScreenChangeKind.NEW_QUESTION, decision.kind)
    }

    @Test
    fun question_number_parsed_from_text_triggers_new_question() {
        // 调用方没有显式给出题号时，分类器必须自行从文本解析。
        val decision = ScreenChangeDetector.classify(
            obs(listOf("第 11 题", "题干甲第一行", "题干甲第二行"), 1_000),
            obs(listOf("第 12 题", "题干乙第一行", "题干乙第二行"), 2_000)
        )

        assertEquals(ScreenChangeKind.NEW_QUESTION, decision.kind)
    }

    @Test
    fun first_observation_is_new_question() {
        val decision = ScreenChangeDetector.classify(null, obs(stem + options, 1_000))

        assertEquals(ScreenChangeKind.NEW_QUESTION, decision.kind)
    }

    // ========================
    // 拿不准 → 不确定（不得强行判定切题）
    // ========================

    @Test
    fun no_provable_relation_is_association_unknown_not_new_question() {
        // 两屏完全不像、也没有足够长的共享连续材料，但没有任何正向切题证据（窗口相同、双方都解析不出题号）
        // —— 不得判新题，也不得把新片段伪装成已确认的同题正文：判为关联未知（保守）。
        val decision = ScreenChangeDetector.classify(
            obs(stem + options.take(2), 1_000),
            obs(
                listOf(
                    "以下结论中不能由题干推出的是：",
                    "甲的身高高于乙，乙的身高高于丙，则",
                    "A. 丙的身高最矮",
                    "B. 甲的身高最高"
                ),
                2_000
            )
        )

        assertEquals(ScreenChangeKind.ASSOCIATION_UNKNOWN, decision.kind)
        assertTrue(decision.note?.contains("无法确定是滚动还是换题") == true)
    }

    @Test
    fun observation_without_comparable_lines_is_unstable() {
        val decision = ScreenChangeDetector.classify(
            obs(stem + options.take(2), 1_000),
            obs(listOf("毫无坐标可言的另一屏文字", "第二行文字"), 2_000, comparable = false)
        )

        assertEquals(ScreenChangeKind.UNSTABLE, decision.kind)
        assertTrue(decision.note?.contains("缺少可比对的结构化文字行") == true)
    }

    // ========================
    // 画面不稳定 / OCR 失败
    // ========================

    @Test
    fun too_short_text_is_unstable() {
        val decision = ScreenChangeDetector.classify(obs(stem + options, 1_000), obs(listOf("A"), 2_000))

        assertEquals(ScreenChangeKind.UNSTABLE, decision.kind)
        assertTrue(decision.note?.contains("过短") == true)
    }

    @Test
    fun too_close_observation_is_unstable() {
        val decision = ScreenChangeDetector.classify(
            obs(stem + options.take(2), 1_000),
            obs(listOf(stem.last()) + options, 1_100)
        )

        assertEquals(ScreenChangeKind.UNSTABLE, decision.kind)
        assertTrue(decision.note?.contains("画面可能正在变化") == true)
    }

    @Test
    fun blank_text_is_ocr_failed() {
        val decision = ScreenChangeDetector.classify(obs(stem + options, 1_000), obs(listOf("   ", ""), 2_000))

        assertEquals(ScreenChangeKind.OCR_FAILED, decision.kind)
    }

    @Test
    fun blank_text_is_ocr_failed_even_on_the_very_first_observation() {
        val decision = ScreenChangeDetector.classify(null, obs(emptyList(), 1_000))

        assertEquals(ScreenChangeKind.OCR_FAILED, decision.kind)
        assertNull("首次观察也不得对空文本建立新题", decision.note?.takeIf { it.contains("建立新的题目上下文") })
    }

    // ========================
    // 题号解析
    // ========================

    @Test
    fun question_number_parsing_prefers_the_explicit_range_form() {
        assertEquals(11, questionNumberOf("言语理解与表达 第 11/15 题"))
        assertEquals(11, questionNumberOf("言语理解与表达 第11／15题"))
        assertEquals(3, questionNumberOf("第 3 题"))
        assertEquals(12, questionNumberOf("12/15"))
        assertEquals(12, questionNumberOf("12/15\n题干第二行文字"))
    }

    @Test
    fun question_number_parsing_returns_null_when_there_is_no_reliable_signal() {
        assertNull(questionNumberOf("下列填入横线处最恰当的一项是："))
        // 首个非空行里的分数不能被误认为题号（横幅正则要求整行匹配）。
        assertNull(questionNumberOf("增长率为 3/4\n题干第二行文字"))
        assertNull(questionNumberOf(""))
    }
}
