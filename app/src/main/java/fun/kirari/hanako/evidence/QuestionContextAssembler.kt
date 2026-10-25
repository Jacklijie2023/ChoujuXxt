package `fun`.kirari.hanako.evidence

import `fun`.kirari.hanako.screencontext.MergeOutcome
import `fun`.kirari.hanako.screencontext.MergeSide
import `fun`.kirari.hanako.screencontext.ScreenContextPool
import `fun`.kirari.hanako.screencontext.ViewportMerger
import `fun`.kirari.hanako.screencontext.ocrLines

/**
 * 题目上下文的完整性 / 新鲜度状态（需求 §5.2）。
 *
 * 必须与「是否读到 A/B/C/D 四个选项」解耦：选项齐全不等于材料完整。
 */
internal enum class QuestionContextStatus {
    /** 可供正常使用：有已确认正文，未发现明确缺失。 */
    READY,

    /** 有可用内容，但存在明确缺失或待确认部分。 */
    PARTIAL,

    /** 观察或题目上下文正在更新（在途采集 / 重新过滤）。 */
    UPDATING,

    /** 快照已过期，或无法证明仍对应当前屏幕。 */
    STALE,

    /** 没有任何可用的有效上下文。 */
    UNAVAILABLE
}

/** 上下文中引用的图像证据（保留 imageId / evidenceId，供请求追踪核对）。 */
internal data class ContextImageRef(
    val imageId: String,
    val evidenceId: String,
    val questionId: String?,
    val originalRef: String,
    val previewRef: String?,
    val available: Boolean,
    val capturedAtMillis: Long,
    val sha256: String
)

/**
 * 权威题目上下文快照（**不可变**）。
 *
 * 与 [ClassifiedBlock] / [EvidenceRecord] 的关系：
 * - [text] 是可以注入模型的派生文本，可随时由原始证据重建；
 * - [evidenceIds] / [blocks] / [images] 是可追溯引用，重建不会修改它们；
 * - 已发出的快照不随后续池更新而变化（纯数据副本）。
 */
internal data class AssembledQuestionContext(
    val questionId: String?,
    val version: Int,
    val sourceApp: String?,
    val capturedAtMillis: Long?,
    val updatedAtMillis: Long,
    val status: QuestionContextStatus,
    /** 最终注入文本（已确认正文 + 显式分段 + 显式待确认区）。 */
    val text: String,
    /** 仅 KEEP 的派生文本（测试中心对照用）。 */
    val keepText: String,
    /** 仅 UNCERTAIN 的待确认文本（测试中心对照用）。 */
    val uncertainText: String,
    val blocks: List<ClassifiedBlock>,
    val images: List<ContextImageRef>,
    /** 明确缺失 / 无法确认的项（面向用户与诊断，不含题目正文）。 */
    val missing: List<String>,
    val excludedCount: Int,
    val uncertainCount: Int,
    val evidenceIds: List<String>,
    val freshnessMillis: Long,
    val note: String?
) {
    val isUsable: Boolean
        get() = status == QuestionContextStatus.READY || status == QuestionContextStatus.PARTIAL

    val availableImages: List<ContextImageRef> get() = images.filter { it.available }

    val unavailableImageCount: Int get() = images.count { !it.available }
}

/**
 * 由**原始证据 + 派生分类 + 图像资产**重建当前题目上下文。
 *
 * 这是系统中「唯一题目上下文」的组装点：迷你聊天、测试中心、请求构造都只读取它的输出，
 * 不再各自维护一份可注入文本。
 *
 * 设计约束：
 * - **不销毁任何原文**：过滤/合并只影响 [text]，[blocks] 与原始证据保持只读引用；
 * - **不伪装连续**：无法证明相邻的片段之间插入 [ScreenContextPool.SEGMENT_BREAK_MARK]；
 * - **不掩盖缺失**：缺失项写入 [AssembledQuestionContext.missing]，状态降为 PARTIAL/STALE；
 * - **可重跑**：同一批证据重新过滤后再次调用即可得到新版本上下文。
 */
internal object QuestionContextAssembler {

    /** 超过该时长没有新证据即视为过期（不拿旧缓存冒充当前屏幕）。 */
    const val STALE_AFTER_MILLIS = 25_000L

    /** 待确认内容在注入文本中的显式分段标记。 */
    const val UNCERTAIN_SECTION_MARK = "【以下内容尚未确认为题目正文（待确认）】"

    fun build(
        questionId: String?,
        version: Int,
        records: List<EvidenceRecord>,
        classifications: List<ClassifiedBlock>,
        images: List<ImageAssetRecord>,
        nowMillis: Long,
        updating: Boolean = false,
        /** 最新一次视口是否已被证明覆盖（false 时状态不得为 READY）。 */
        liveViewportCovered: Boolean = true,
        /** 过滤结果缺失的原始证据 id（用于如实说明，而非静默使用原文）。 */
        filterFailures: List<String> = emptyList()
    ): AssembledQuestionContext {
        val ordered = records.sortedBy { it.capturedAtMillis }
        if (ordered.isEmpty()) {
            return AssembledQuestionContext(
                questionId = questionId,
                version = version,
                sourceApp = null,
                capturedAtMillis = null,
                updatedAtMillis = nowMillis,
                status = QuestionContextStatus.UNAVAILABLE,
                text = "",
                keepText = "",
                uncertainText = "",
                blocks = emptyList(),
                images = emptyList(),
                missing = listOf("当前没有可用的屏幕采集证据"),
                excludedCount = 0,
                uncertainCount = 0,
                evidenceIds = emptyList(),
                freshnessMillis = Long.MAX_VALUE,
                note = "尚未采集到任何证据"
            )
        }

        val blocksByEvidence = classifications
            .groupBy { it.evidenceId }
            // 同一 evidence 可能有多轮规则结果：只保留「处理时间最新」的那一轮，
            // 否则旧规则的 EXCLUDE 会污染新规则的重跑结果。
            .mapValues { (_, list) -> latestRound(list) }

        var accumulated: List<String> = emptyList()
        val usedBlocks = mutableListOf<ClassifiedBlock>()
        val usedEvidenceIds = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var hadSegmentBreak = false
        var noClassification = 0

        ordered.forEach { record ->
            val text = record.organizedText?.takeIf { it.isNotBlank() } ?: record.rawText
            if (text.isBlank()) return@forEach
            usedEvidenceIds += record.evidenceId
            val classified = blocksByEvidence[record.evidenceId].orEmpty()
            val lines: List<String> = if (classified.isEmpty()) {
                noClassification += 1
                ocrLines(text)
            } else {
                usedBlocks += classified
                classified.filter { it.verdict != BlockVerdict.EXCLUDE }
                    .sortedBy { it.lineIndex }
                    .map { it.text.trim() }
                    .filter { it.isNotBlank() }
            }
            if (lines.isEmpty()) return@forEach

            val merged = ViewportMerger.merge(
                previous = accumulated.takeIf { it.isNotEmpty() }?.let { MergeSide(it) },
                incoming = MergeSide(lines)
            )
            when (merged.outcome) {
                MergeOutcome.NEW_QUESTION,
                MergeOutcome.APPENDED,
                MergeOutcome.OVERLAP_MERGED -> accumulated = merged.lines.orEmpty()

                MergeOutcome.DUPLICATE -> Unit

                MergeOutcome.UNSTABLE -> {
                    // 无法证明与上文连续：作为**独立段落**保留，绝不做伪连续拼接。
                    if (accumulated.isNotEmpty()) {
                        accumulated = accumulated + ScreenContextPool.SEGMENT_BREAK_MARK
                        hadSegmentBreak = true
                    }
                    accumulated = accumulated + lines
                }
            }
            if (record.availability == EvidenceAvailability.IMAGE_MISSING) {
                missing += "证据 ${record.evidenceId} 对应的图像文件已缺失"
            }
        }

        if (noClassification > 0) {
            missing += "有 $noClassification 条证据尚未生成过滤结果，已暂按原始整理文本组装"
        }
        filterFailures.forEach { id ->
            missing += "证据 $id 的过滤执行失败，原文仍可回查"
        }

        val kept = usedBlocks.filter { it.verdict == BlockVerdict.KEEP }
        val uncertain = usedBlocks.filter { it.verdict == BlockVerdict.UNCERTAIN }
        val excluded = usedBlocks.count { it.verdict == BlockVerdict.EXCLUDE }

        val keepLines = kept.map { it.text.trim() }.filter { it.isNotBlank() }
        val uncertainLines = uncertain.map { it.text.trim() }
            .filter { it.isNotBlank() && it !in keepLines }
            .distinct()

        val textParts = mutableListOf<String>()
        if (accumulated.isNotEmpty()) textParts += accumulated
        if (uncertainLines.isNotEmpty()) {
            textParts += UNCERTAIN_SECTION_MARK
            textParts += uncertainLines
            missing += "有 ${uncertainLines.size} 行内容尚未确认为题目正文"
        }

        val text = textParts.joinToString("\n")
        val imageRefs = images.map { image ->
            ContextImageRef(
                imageId = image.imageId,
                evidenceId = image.evidenceId,
                questionId = image.questionId,
                originalRef = image.originalRef,
                previewRef = image.previewRef,
                available = image.available,
                capturedAtMillis = image.capturedAtMillis,
                sha256 = image.sha256
            )
        }
        val imageMissing = imageRefs.count { !it.available }
        if (imageMissing > 0) missing += "有 $imageMissing 张图像证据文件缺失，无法送入模型"

        // 结构完整性：只依据「已确认块」推断，绝不以选项数量直接判定完整。
        val hasOption = kept.any { it.category == BlockCategory.OPTION }
        val hasStem = kept.any {
            it.category == BlockCategory.QUESTION_STEM || it.category == BlockCategory.MATERIAL
        }
        if (text.isNotBlank() && !hasOption) missing += "尚未采集到已确认的选项"
        if (text.isNotBlank() && !hasStem) missing += "尚未确认题干或材料"

        val hasVisualStructure = kept.any {
            it.category == BlockCategory.TABLE ||
                it.category == BlockCategory.FIGURE ||
                it.category == BlockCategory.FORMULA ||
                it.category == BlockCategory.IMAGE_TEXT
        }
        if (hasVisualStructure && imageRefs.isEmpty()) {
            missing += "检测到表格/图表/公式相关内容，但尚未保存对应图像证据"
        }

        val latestCapture = ordered.maxOfOrNull { it.capturedAtMillis } ?: nowMillis
        val freshness = (nowMillis - latestCapture).coerceAtLeast(0L)

        val status = when {
            updating -> QuestionContextStatus.UPDATING
            text.isBlank() -> QuestionContextStatus.UNAVAILABLE
            freshness > STALE_AFTER_MILLIS -> QuestionContextStatus.STALE
            missing.isEmpty() && liveViewportCovered && uncertainLines.isEmpty() ->
                QuestionContextStatus.READY
            else -> QuestionContextStatus.PARTIAL
        }
        val note = buildString {
            append("证据 ${ordered.size} 条")
            if (hadSegmentBreak) append("；含无法证明连续的独立段落")
            if (excluded > 0) append("；已排除 $excluded 行界面噪声（原文保留）")
            if (status == QuestionContextStatus.STALE) {
                append("；最近一次采集已 ${freshness / 1000} 秒，无法证明仍对应当前屏幕")
            }
            if (!liveViewportCovered) append("；当前视口未被证明覆盖")
        }

        return AssembledQuestionContext(
            questionId = questionId,
            version = version,
            sourceApp = ordered.lastOrNull { it.sourceApp != null }?.sourceApp,
            capturedAtMillis = latestCapture,
            updatedAtMillis = nowMillis,
            status = status,
            text = text,
            keepText = keepLines.joinToString("\n"),
            uncertainText = uncertainLines.joinToString("\n"),
            blocks = usedBlocks.sortedWith(compareBy({ it.evidenceId }, { it.lineIndex })),
            images = imageRefs,
            missing = missing.distinct(),
            excludedCount = excluded,
            uncertainCount = uncertainLines.size,
            evidenceIds = usedEvidenceIds,
            freshnessMillis = freshness,
            note = note
        )
    }

    /** 同一证据的多轮分类：按 processedAt 取最新一轮（相同 processedAt 视为同一轮）。 */
    private fun latestRound(blocks: List<ClassifiedBlock>): List<ClassifiedBlock> {
        val latest = blocks.maxOfOrNull { it.processedAtMillis } ?: return emptyList()
        return blocks.filter { it.processedAtMillis == latest }
    }
}
