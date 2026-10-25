package `fun`.kirari.hanako.evidence

import java.security.MessageDigest

/**
 * 原始证据库抽象。
 *
 * 实现必须满足（需求 §3）：
 * - **只追加**：写入后的 [EvidenceRecord] 不允许被过滤 / 合并 / 切题 / 重建上下文改写；
 * - 允许更新的只有**可用性**（如图像文件丢失 → [EvidenceAvailability.IMAGE_MISSING]）；
 * - 存储失败必须返回 false / 记录 [EvidenceAvailability.STORAGE_FAILED]，绝不伪造成成功；
 * - 提供「取回原文 → 重新过滤 → 重建上下文」的读取能力。
 *
 * 抽成接口的目的：JVM 单测可用内存实现验证过滤与组装语义，不必启动 Android。
 */
internal interface EvidenceStore {

    /** 写入一条原始证据；失败返回 false（调用方必须记录可见错误）。 */
    fun recordCapture(record: EvidenceRecord): Boolean

    /** 更新可用性（不触碰任何原文）。 */
    fun markAvailability(evidenceId: String, availability: EvidenceAvailability, reason: String?)

    fun get(evidenceId: String): EvidenceRecord?

    fun recent(limit: Int = 50): List<EvidenceRecord>

    fun recentForQuestion(questionId: String, limit: Int = 50): List<EvidenceRecord>

    /** 保存一次过滤结果（同一 evidenceId + ruleVersion + lineIndex 覆盖旧结果，便于重跑对照）。 */
    fun saveClassifications(blocks: List<ClassifiedBlock>): Boolean

    fun classificationsOf(evidenceId: String): List<ClassifiedBlock>

    fun latestClassifications(limit: Int = 200): List<ClassifiedBlock>

    fun saveImage(asset: ImageAssetRecord): Boolean

    fun imagesForQuestion(questionId: String): List<ImageAssetRecord>

    fun recentImages(limit: Int = 20): List<ImageAssetRecord>

    fun markImageUnavailable(imageId: String, reason: String)

    fun saveTrace(trace: RequestTraceRecord): Boolean

    fun recentTraces(limit: Int = 20): List<RequestTraceRecord>

    fun getTrace(traceId: String): RequestTraceRecord?

    /**
     * 有界清理：仅删除**超过保留时长**且**不属于活动题目**的证据与图像元数据。
     *
     * 活动题目由 [activeQuestionId] 指定：其证据永远不会被本方法删除。
     * 返回删除的证据条数。
     */
    fun prune(maxAgeMillis: Long, activeQuestionId: String?): Int

    fun stats(): EvidenceStats

    fun close()
}

/** 计算 SHA-256 十六进制摘要（内容指纹 / 图像去重键）。 */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/** 稳定内容指纹（去掉空白差异，避免同一内容因排版不同被当成不同证据）。 */
internal fun contentFingerprint(text: String): String =
    sha256Hex(text.filter { !it.isWhitespace() })
