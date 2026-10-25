package `fun`.kirari.hanako.evidence

import kotlinx.serialization.Serializable

/**
 * 原始证据的采集方式。
 *
 * 这是「原始证据层」的唯一分类维度：它描述**数据是怎么来的**，不描述它被如何过滤。
 * 过滤/合并/排序结果一律记录在 [ClassifiedBlock] 这一派生层，绝不改写原始记录。
 */
internal enum class EvidenceSourceType {
    /** 无障碍节点树读取（无位图坐标，原文即节点文本）。 */
    ACCESSIBILITY,

    /** 本地 OCR（截图 + 识别，带行坐标）。 */
    OCR,

    /** 仅保存截图、尚未识别的原始图像证据。 */
    SCREENSHOT,

    /** 从截图中裁剪出的图像区域（图表 / 图形 / 公式区域）。 */
    IMAGE_REGION,

    /** 采集失败记录（截图失败 / OCR 失败 / 存储失败），不得伪装成有效证据。 */
    FAILURE
}

/** 证据当前可用性（文件丢失、数据库失败等必须显式反映在这里）。 */
internal enum class EvidenceAvailability {
    STORED,
    IMAGE_MISSING,
    STORAGE_FAILED,
    FILTER_FAILED
}

/** 一条带坐标的 OCR 行（原始识别结果，派生整理不得回写）。 */
@Serializable
internal data class EvidenceOcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/** 一条无障碍节点（尽量保留结构，不强行拍平成字符串）。 */
@Serializable
internal data class EvidenceNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewId: String? = null,
    val left: Int? = null,
    val top: Int? = null,
    val right: Int? = null,
    val bottom: Int? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val depth: Int = 0
)

/**
 * 一次采集的原始证据记录（**不可变**；持久化后只允许追加新记录或改可用性，不允许改写原文）。
 *
 * 字段与需求 §3.2 对齐：
 * - 身份：evidenceId / eventId / sessionId / sequence；
 * - 来源：sourceApp / windowId / windowType / viewportId / questionId；
 * - 原文：rawText / rawNodes / ocrRawText / ocrLines；
 * - 图像：imageRef（应用私有目录文件名）/ imageHash / imageRegion；
 * - 质量：contentHash / confidence / availability / error。
 */
internal data class EvidenceRecord(
    val evidenceId: String,
    val eventId: String,
    val sessionId: String,
    val sequence: Long,
    val capturedAtMillis: Long,
    val sourceApp: String?,
    val windowId: String?,
    val windowType: String?,
    val viewportId: String?,
    val questionId: String?,
    val sourceType: EvidenceSourceType,
    /** 原始文本（无障碍节点文本 / OCR 原始拼接文本 / 失败说明）。 */
    val rawText: String,
    /** 结构化节点（无障碍采集时尽量填充；OCR 采集为空）。 */
    val rawNodes: List<EvidenceNode> = emptyList(),
    /** OCR 引擎自身拼接的原始文本（与 [rawText] 可同源，但语义上独立保留）。 */
    val ocrRawText: String? = null,
    val ocrLines: List<EvidenceOcrLine> = emptyList(),
    /** 整理后的派生文本（可重建；绝不覆盖 [rawText]）。 */
    val organizedText: String? = null,
    /** 原始图像文件名（应用私有目录，不含路径）。 */
    val imageRef: String? = null,
    /** 供模型输入使用的派生图像文件名（缩放/裁剪产物，缺失时回退原图）。 */
    val imagePreviewRef: String? = null,
    val imageHash: String? = null,
    val imageRegion: String? = null,
    val contentHash: String,
    val confidence: Float? = null,
    val availability: EvidenceAvailability = EvidenceAvailability.STORED,
    val errorStage: String? = null,
    val errorMessage: String? = null,
    val processingSource: String? = null
)

/** 过滤结论：只有这三种，不得「过滤掉了就永久丢弃原文」。 */
internal enum class BlockVerdict {
    KEEP,
    EXCLUDE,
    UNCERTAIN
}

/** 语义类别（在确定性噪声过滤之后才尽力推断；无法判断时归入 [UNKNOWN]）。 */
internal enum class BlockCategory {
    QUESTION_STEM,
    MATERIAL,
    OPTION,
    TABLE,
    FIGURE,
    FORMULA,
    IMAGE_TEXT,
    QUESTION_AUXILIARY,
    UI_NOISE,
    SYSTEM_UI,
    KEYBOARD,
    SELF_OVERLAY,
    UNKNOWN
}

/**
 * 一条派生分类记录：永远引用原始 [evidenceId] + 原始行号，可重复执行、可对照。
 *
 * [ruleVersion] 让「规则变化后重跑」具有可解释性；[confidence] 只表达规则把握程度。
 */
internal data class ClassifiedBlock(
    val evidenceId: String,
    val lineIndex: Int,
    val text: String,
    val verdict: BlockVerdict,
    val category: BlockCategory,
    val reason: String,
    val ruleVersion: String,
    val confidence: Float,
    val processedAtMillis: Long
)

/** 过滤输入：一段可被独立分类的文本单元（来自原始证据）。 */
internal data class FilterInputBlock(
    val lineIndex: Int,
    val text: String,
    /** 无障碍节点信息（可空）。 */
    val node: EvidenceNode? = null,
    /** OCR 行坐标（可空）。 */
    val bounds: EvidenceOcrLine? = null,
    val confidence: Float? = null
)

/** 过滤上下文：确定性信号（来源、窗口、坐标、节点类型），不参与昂贵的语义调用。 */
internal data class FilterContext(
    val sourceApp: String?,
    val ownPackage: String,
    val windowType: String? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val boundTargetPackage: String? = null
)

/** 图像资产：原始文件 + 派生文件 + 可用性，全部指向应用私有目录。 */
internal data class ImageAssetRecord(
    val imageId: String,
    val evidenceId: String,
    val questionId: String?,
    val originalRef: String,
    val previewRef: String?,
    val sha256: String,
    val capturedAtMillis: Long,
    val width: Int,
    val height: Int,
    val available: Boolean = true,
    val unavailableReason: String? = null
)

/** 实际发出的一次模型请求的追踪记录（用于测试中心核对「图文是否真的进了请求」）。 */
internal data class RequestTraceRecord(
    val traceId: String,
    val questionId: String?,
    val contextVersion: Int,
    val contextStatus: String,
    val textChars: Int,
    val textFingerprint: String,
    val imageIds: List<String>,
    val imageCount: Int,
    val visionStatus: String,
    val sent: Boolean,
    val failStage: String? = null,
    val errorMessage: String? = null,
    val createdAtMillis: Long
)

/** 证据库统计（用户可见的存储状态，不做「固定片段数」硬上限）。 */
internal data class EvidenceStats(
    val evidenceCount: Int,
    val imageCount: Int,
    val imageMissingCount: Int,
    val databaseBytes: Long,
    val imageBytes: Long,
    val lastCaptureAtMillis: Long?
)

/** 原始证据 → 可过滤的文本单元（保留行号与来源结构）。 */
internal fun EvidenceRecord.filterBlocks(): List<FilterInputBlock> {
    if (ocrLines.isNotEmpty()) {
        return ocrLines.mapIndexed { index, line ->
            FilterInputBlock(
                lineIndex = index,
                text = line.text,
                bounds = line,
                confidence = confidence
            )
        }
    }
    if (rawNodes.isNotEmpty()) {
        return rawNodes.mapIndexed { index, node ->
            FilterInputBlock(
                lineIndex = index,
                text = node.text ?: node.contentDescription.orEmpty(),
                node = node,
                confidence = confidence
            )
        }.filter { it.text.isNotBlank() }
    }
    return rawText.lineSequence()
        .mapIndexed { index, line -> FilterInputBlock(index, line) }
        .filter { it.text.isNotBlank() }
        .toList()
}
