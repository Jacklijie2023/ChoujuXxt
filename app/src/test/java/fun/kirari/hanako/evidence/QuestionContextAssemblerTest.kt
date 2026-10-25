package `fun`.kirari.hanako.evidence

import `fun`.kirari.hanako.screencontext.ScreenContextPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题目上下文组装的纯 JVM 单测。
 *
 * 关注点（对应需求 §5）：
 * - 状态与「是否看到 A/B/C/D」解耦：缺失项必须如实写入 [AssembledQuestionContext.missing]；
 * - 跨屏合并复用 [fun.kirari.hanako.screencontext.ViewportMerger]，无法证明连续时插入分段标记；
 * - 过期快照必须标 STALE，不得冒充当前屏幕；
 * - 组装结果只引用原始证据，不销毁任何原文。
 */
class QuestionContextAssemblerTest {

    private val target = "com.exam.app"
    private val now = 100_000L

    private fun record(
        evidenceId: String,
        capturedAt: Long,
        rawText: String,
        sourceApp: String? = target,
        availability: EvidenceAvailability = EvidenceAvailability.STORED
    ) = EvidenceRecord(
        evidenceId = evidenceId,
        eventId = "event-$evidenceId",
        sessionId = "session-1",
        sequence = 1L,
        capturedAtMillis = capturedAt,
        sourceApp = sourceApp,
        windowId = "win-1",
        windowType = "application",
        viewportId = "viewport-$evidenceId",
        questionId = "q-1",
        sourceType = EvidenceSourceType.OCR,
        rawText = rawText,
        contentHash = "hash-$evidenceId",
        availability = availability
    )

    private fun block(
        evidenceId: String,
        lineIndex: Int,
        text: String,
        verdict: BlockVerdict,
        category: BlockCategory,
        processedAt: Long = 2_000L
    ) = ClassifiedBlock(
        evidenceId = evidenceId,
        lineIndex = lineIndex,
        text = text,
        verdict = verdict,
        category = category,
        reason = "test",
        ruleVersion = EvidenceFilter.RULE_VERSION,
        confidence = 0.9f,
        processedAtMillis = processedAt
    )

    /** 题干 + 选项的完整分类（用于构造 READY 基线）。 */
    private fun completeBlocks(evidenceId: String) = listOf(
        block(evidenceId, 0, "1. 下列说法正确的是：", BlockVerdict.KEEP, BlockCategory.QUESTION_STEM),
        block(evidenceId, 1, "A. 选项一内容", BlockVerdict.KEEP, BlockCategory.OPTION),
        block(evidenceId, 2, "B. 选项二内容", BlockVerdict.KEEP, BlockCategory.OPTION)
    )

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
        available = available,
        unavailableReason = if (available) null else "文件缺失"
    )

    // ========================
    // 1. 状态判定
    // ========================

    @Test
    fun complete_fresh_single_viewport_is_ready() {
        val evidence = record("ev-1", now - 1_000L, "1. 下列说法正确的是：\nA. 选项一内容\nB. 选项二内容")
        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = listOf(evidence),
            classifications = completeBlocks("ev-1"),
            images = emptyList(),
            nowMillis = now
        )

        assertEquals(QuestionContextStatus.READY, context.status)
        assertTrue(context.isUsable)
        assertTrue(context.missing.isEmpty())
        assertTrue(context.text.contains("A. 选项一内容"))
    }

    @Test
    fun uncertain_content_degrades_to_partial_and_keeps_the_text() {
        val evidence = record("ev-1", now - 1_000L, "1. 下列说法正确的是：\nA. 选项一内容\n剩余时间 00:45")
        val classifications = completeBlocks("ev-1") + block(
            "ev-1", 2, "剩余时间 00:45", BlockVerdict.UNCERTAIN, BlockCategory.UNKNOWN
        )

        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = listOf(evidence),
            classifications = classifications,
            images = emptyList(),
            nowMillis = now
        )

        assertEquals(QuestionContextStatus.PARTIAL, context.status)
        assertEquals(1, context.uncertainCount)
        assertTrue(context.uncertainText.contains("剩余时间 00:45"))
        assertTrue(context.text.contains(QuestionContextAssembler.UNCERTAIN_SECTION_MARK))
        assertTrue(context.missing.any { it.contains("尚未确认为题目正文") })
    }

    @Test
    fun stale_snapshot_is_flagged_as_stale() {
        val evidence = record("ev-1", now - 30_000L, "1. 下列说法正确的是：\nA. 选项一内容\nB. 选项二内容")
        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = listOf(evidence),
            classifications = completeBlocks("ev-1"),
            images = emptyList(),
            nowMillis = now
        )

        assertEquals(QuestionContextStatus.STALE, context.status)
        assertFalse("过期快照不得当作可用上下文", context.isUsable)
        assertEquals(30_000L, context.freshnessMillis)
        assertTrue(context.note?.contains("无法证明仍对应当前屏幕") == true)
    }

    @Test
    fun updating_flag_takes_precedence() {
        val evidence = record("ev-1", now - 1_000L, "1. 下列说法正确的是：\nA. 选项一内容\nB. 选项二内容")
        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = listOf(evidence),
            classifications = completeBlocks("ev-1"),
            images = emptyList(),
            nowMillis = now,
            updating = true
        )

        assertEquals(QuestionContextStatus.UPDATING, context.status)
        assertFalse(context.isUsable)
    }

    @Test
    fun no_evidence_is_unavailable_with_explicit_reason() {
        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = emptyList(),
            classifications = emptyList(),
            images = emptyList(),
            nowMillis = now
        )

        assertEquals(QuestionContextStatus.UNAVAILABLE, context.status)
        assertFalse(context.isUsable)
        assertTrue(context.missing.any { it.contains("没有可用的屏幕采集证据") })
    }

    // ========================
    // 2. 跨屏合并 / 无法连续时分段
    // ========================

    @Test
    fun cross_screen_overlap_is_merged_without_duplicates() {
        val first = record("ev-1", now - 3_000L, "题干第一行文字\nA. 选项一内容")
        val second = record("ev-2", now - 1_000L, "A. 选项一内容\nB. 选项二内容")
        val classifications = listOf(
            block("ev-1", 0, "题干第一行文字", BlockVerdict.KEEP, BlockCategory.QUESTION_STEM),
            block("ev-1", 1, "A. 选项一内容", BlockVerdict.KEEP, BlockCategory.OPTION),
            block("ev-2", 0, "A. 选项一内容", BlockVerdict.KEEP, BlockCategory.OPTION),
            block("ev-2", 1, "B. 选项二内容", BlockVerdict.KEEP, BlockCategory.OPTION)
        )

        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 2,
            records = listOf(first, second),
            classifications = classifications,
            images = emptyList(),
            nowMillis = now
        )

        val lines = context.text.lines().filter { it.isNotBlank() }
        assertEquals(listOf("题干第一行文字", "A. 选项一内容", "B. 选项二内容"), lines)
        assertEquals("不得出现重复行", lines.size, lines.distinct().size)
        assertFalse("可证明连续时不得插入分段标记", context.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK))
        assertEquals(listOf("ev-1", "ev-2"), context.evidenceIds)
    }

    @Test
    fun non_continuous_viewport_is_kept_as_separate_segment() {
        val first = record("ev-1", now - 3_000L, "第一部分 常识判断 第 1/20 题\nA. 甲")
        val second = record("ev-2", now - 1_000L, "第二部分 言语理解 第 2/15 题\nB. 乙")
        val classifications = listOf(
            block("ev-1", 0, "第一部分 常识判断 第 1/20 题", BlockVerdict.KEEP, BlockCategory.QUESTION_STEM),
            block("ev-1", 1, "A. 甲", BlockVerdict.KEEP, BlockCategory.OPTION),
            block("ev-2", 0, "第二部分 言语理解 第 2/15 题", BlockVerdict.KEEP, BlockCategory.QUESTION_STEM),
            block("ev-2", 1, "B. 乙", BlockVerdict.KEEP, BlockCategory.OPTION)
        )

        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 2,
            records = listOf(first, second),
            classifications = classifications,
            images = emptyList(),
            nowMillis = now
        )

        assertTrue(
            "无法证明连续时必须插入分段标记，不得伪装成一段连续原文",
            context.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK)
        )
        assertTrue(context.text.contains("第二部分 言语理解 第 2/15 题"))
        assertTrue(context.note?.contains("独立段落") == true)
    }

    // ========================
    // 3. 缺失项 / 图像 / 未过滤证据必须如实呈现
    // ========================

    @Test
    fun unavailable_image_is_reported_as_missing_but_kept_as_reference() {
        val evidence = record("ev-1", now - 1_000L, "1. 下列说法正确的是：\nA. 选项一内容\nB. 选项二内容")
        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = listOf(evidence),
            classifications = completeBlocks("ev-1"),
            images = listOf(image("img-1", available = false)),
            nowMillis = now
        )

        assertEquals(1, context.unavailableImageCount)
        assertTrue(context.availableImages.isEmpty())
        assertTrue(context.missing.any { it.contains("图像证据文件缺失") })
    }

    @Test
    fun available_image_is_exposed_for_request_use() {
        val evidence = record("ev-1", now - 1_000L, "1. 下列说法正确的是：\nA. 选项一内容\nB. 选项二内容")
        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = listOf(evidence),
            classifications = completeBlocks("ev-1"),
            images = listOf(image("img-1")),
            nowMillis = now
        )

        assertEquals(1, context.availableImages.size)
        assertEquals("img-1.png", context.availableImages.single().originalRef)
    }

    @Test
    fun evidence_without_classification_is_not_silently_dropped() {
        val evidence = record("ev-1", now - 1_000L, "1. 下列说法正确的是：\nA. 选项一内容")

        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = listOf(evidence),
            classifications = emptyList(),
            images = emptyList(),
            nowMillis = now
        )

        assertTrue("未生成过滤结果时仍应组装原始整理文本", context.text.contains("A. 选项一内容"))
        assertTrue(context.missing.any { it.contains("尚未生成过滤结果") })
        assertEquals(QuestionContextStatus.PARTIAL, context.status)
    }

    @Test
    fun excluded_blocks_stay_in_derived_layer_and_original_text_remains_traceable() {
        val raw = "1. 下列说法正确的是：\nA. 选项一内容\n下一题"
        val evidence = record("ev-1", now - 1_000L, raw)
        val classifications = listOf(
            block("ev-1", 0, "1. 下列说法正确的是：", BlockVerdict.KEEP, BlockCategory.QUESTION_STEM),
            block("ev-1", 1, "A. 选项一内容", BlockVerdict.KEEP, BlockCategory.OPTION),
            block("ev-1", 2, "下一题", BlockVerdict.EXCLUDE, BlockCategory.UI_NOISE)
        )

        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = listOf(evidence),
            classifications = classifications,
            images = emptyList(),
            nowMillis = now
        )

        assertEquals(1, context.excludedCount)
        assertTrue("派生层必须保留被排除记录以供对照", context.blocks.any { it.verdict == BlockVerdict.EXCLUDE })
        assertFalse("被排除的噪声不得进入注入文本", context.text.contains("下一题"))
        assertEquals("原始证据不得被改写", raw, evidence.rawText)
        assertTrue(context.note?.contains("原文保留") == true)
    }

    @Test
    fun structural_hints_are_reported_when_no_image_evidence_exists() {
        val evidence = record("ev-1", now - 1_000L, "1. 下列说法正确的是：\n| 年份 | 产量 |")
        val classifications = listOf(
            block("ev-1", 0, "1. 下列说法正确的是：", BlockVerdict.KEEP, BlockCategory.QUESTION_STEM),
            block("ev-1", 1, "| 年份 | 产量 |", BlockVerdict.KEEP, BlockCategory.TABLE)
        )

        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = listOf(evidence),
            classifications = classifications,
            images = emptyList(),
            nowMillis = now
        )

        assertTrue(context.missing.any { it.contains("尚未保存对应图像证据") })
        assertEquals(QuestionContextStatus.PARTIAL, context.status)
    }

    @Test
    fun same_evidence_multiple_filter_rounds_use_only_the_latest_result() {
        val evidence = record("ev-1", now - 1_000L, "1. 下列说法正确的是：\nA. 选项一内容")
        val oldRound = listOf(
            block("ev-1", 0, "1. 下列说法正确的是：", BlockVerdict.EXCLUDE, BlockCategory.UI_NOISE, processedAt = 1_000L),
            block("ev-1", 1, "A. 选项一内容", BlockVerdict.EXCLUDE, BlockCategory.UI_NOISE, processedAt = 1_000L)
        )
        val newRound = completeBlocks("ev-1").map { it.copy(processedAtMillis = 5_000L) }

        val context = QuestionContextAssembler.build(
            questionId = "q-1",
            version = 1,
            records = listOf(evidence),
            classifications = oldRound + newRound,
            images = emptyList(),
            nowMillis = now
        )

        assertEquals("旧规则结果不得污染重跑结果", 0, context.excludedCount)
        assertTrue(context.text.contains("A. 选项一内容"))
    }
}
