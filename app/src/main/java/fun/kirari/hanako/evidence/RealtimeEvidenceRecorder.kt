package `fun`.kirari.hanako.evidence

import android.content.Context
import android.graphics.Bitmap
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.screencontext.OcrOrganization
import `fun`.kirari.hanako.screencontext.OcrTextLine
import java.util.UUID

/**
 * 实时观察 / 请求补救 / 测试中心共用的**唯一证据写入入口**。
 *
 * 职责边界：
 * - 采集侧（无障碍、后台 OCR、请求补救、手动测试采集）只调用本类写入证据；
 * - 过滤在本类内部完成（[EvidenceFilter]），分类结果与原始证据分开保存；
 * - 题目上下文由 [QuestionContextAssembler] 从证据重建，本类只负责版本号与缓存；
 * - 本类**不**发起任何模型请求，也不决定是否注入（由发送链路决定）。
 *
 * 所有方法都可能访问 SQLite / 文件系统，调用方应确保在 IO 线程执行。
 */
internal class RealtimeEvidenceRecorder(
    context: Context,
    private val store: EvidenceStore,
    private val images: EvidenceImageFiles,
    private val ownPackage: String,
    /**
     * 已绑定的目标应用包名（写入证据时用于确认来源身份）。
     *
     * 缺少它时，目标应用里的普通题干行会被 [EvidenceFilter] 判为「来源未确认」的 UNCERTAIN，
     * 整份上下文因此长期停留在 PARTIAL —— 这不是保守，而是把已确认来源当成未确认。
     */
    private val boundTargetPackageProvider: () -> String? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val tag = "realtime_evidence"

    private val lock = Any()
    private var sessionId = "session-" + UUID.randomUUID().toString().take(8)
    private var sequence = 0L

    /** 当前题目上下文的版本号（同一题目内容发生变化时 +1）。 */
    private var contextVersion = 0
    private var lastQuestionId: String? = null
    private var lastContentKey: String? = null
    private var cachedContext: AssembledQuestionContext? = null
    private var lastCaptureAtMillis: Long? = null
    private var lastFailureStage: String? = null
    private var lastFailureMessage: String? = null

    /** 实时模式关闭 / 应用切题边界：开启新会话序号段，但**不删除**任何历史证据。 */
    fun onSessionBoundary() = synchronized(lock) {
        sessionId = "session-" + UUID.randomUUID().toString().take(8)
        sequence = 0L
        cachedContext = null
        contextVersion = 0
        lastQuestionId = null
    }

    /**
     * 记录一次无障碍文本采集。
     *
     * 与 OCR 路径一致：先写原始证据，再执行派生过滤；任一步失败都记录显式错误。
     */
    fun recordAccessibility(
        text: String,
        sourceApp: String?,
        windowId: String?,
        windowType: String?,
        questionId: String?,
        viewportId: String,
        eventId: String,
        confidence: Float? = null
    ): EvidenceRecord? {
        val now = clock()
        val seq = nextSequence()
        val record = EvidenceRecord(
            evidenceId = "ev-" + UUID.randomUUID().toString().take(12),
            eventId = eventId,
            sessionId = sessionId,
            sequence = seq,
            capturedAtMillis = now,
            sourceApp = sourceApp,
            windowId = windowId,
            windowType = windowType,
            viewportId = viewportId,
            questionId = questionId,
            sourceType = EvidenceSourceType.ACCESSIBILITY,
            rawText = text,
            contentHash = contentFingerprint(text),
            confidence = confidence,
            processingSource = "accessibility"
        )
        return persistAndFilter(record, text, sourceApp, windowType)
    }

    /**
     * 记录一次截图 + 本地 OCR 采集。
     *
     * 顺序严格为：**先保存原始图像** → 再写证据 → 再执行过滤。
     * OCR 失败（[organization] 为空 / [rawText] 为空）时仍必须保存图像与失败状态。
     */
    fun recordOcr(
        bitmap: Bitmap?,
        rawText: String?,
        organization: OcrOrganization?,
        sourceApp: String?,
        windowId: String?,
        windowType: String?,
        questionId: String?,
        viewportId: String,
        eventId: String,
        errorStage: String? = null,
        errorMessage: String? = null
    ): EvidenceRecord? {
        val now = clock()
        val seq = nextSequence()
        val confidence = null
        val saved = bitmap?.let { images.saveCapture(it) }
        if (bitmap != null && saved == null) {
            lastFailureStage = "image_store"
            lastFailureMessage = "原始截图保存失败"
            AppDebugLogStore.e(tag, "image_store_failed event=$eventId", null)
        }

        val organized = organization?.text?.takeIf { it.isNotBlank() }
        val lines = organization?.lines.orEmpty().map { line ->
            EvidenceOcrLine(line.text, line.left, line.top, line.right, line.bottom)
        }
        val effectiveText = organized ?: rawText.orEmpty()
        val record = EvidenceRecord(
            evidenceId = "ev-" + UUID.randomUUID().toString().take(12),
            eventId = eventId,
            sessionId = sessionId,
            sequence = seq,
            capturedAtMillis = now,
            sourceApp = sourceApp,
            windowId = windowId,
            windowType = windowType,
            viewportId = viewportId,
            questionId = questionId,
            sourceType = if (effectiveText.isBlank()) EvidenceSourceType.FAILURE else EvidenceSourceType.OCR,
            rawText = rawText.orEmpty(),
            ocrRawText = rawText,
            ocrLines = lines,
            organizedText = organized,
            imageRef = saved?.originalRef,
            imagePreviewRef = saved?.previewRef,
            imageHash = saved?.sha256,
            contentHash = contentFingerprint(effectiveText),
            confidence = confidence,
            availability = when {
                bitmap != null && saved == null -> EvidenceAvailability.STORAGE_FAILED
                bitmap != null && !images.exists(saved?.originalRef) -> EvidenceAvailability.IMAGE_MISSING
                else -> EvidenceAvailability.STORED
            },
            errorStage = errorStage ?: when {
                bitmap == null -> "capture"
                effectiveText.isBlank() -> "ocr"
                else -> null
            },
            errorMessage = errorMessage ?: when {
                bitmap == null -> "截图不可用"
                effectiveText.isBlank() -> "OCR 未识别到有效文字"
                else -> null
            },
            processingSource = "local_ocr"
        )
        val persisted = persistAndFilter(record, effectiveText, sourceApp, windowType)
        if (saved != null && persisted != null) {
            store.saveImage(
                ImageAssetRecord(
                    imageId = "img-" + saved.sha256.take(12),
                    evidenceId = persisted.evidenceId,
                    questionId = questionId,
                    originalRef = saved.originalRef,
                    previewRef = saved.previewRef,
                    sha256 = saved.sha256,
                    capturedAtMillis = now,
                    width = saved.width,
                    height = saved.height,
                    available = images.exists(saved.originalRef)
                )
            )
        }
        return persisted
    }

    /** 记录一次采集/处理失败（没有可用图像与文本时也必须可查，不得伪造成功）。 */
    fun recordFailure(
        stage: String,
        message: String,
        sourceApp: String?,
        questionId: String?,
        viewportId: String,
        eventId: String
    ) {
        val seq = nextSequence()
        val now = clock()
        lastFailureStage = stage
        lastFailureMessage = message
        store.recordCapture(
            EvidenceRecord(
                evidenceId = "ev-" + UUID.randomUUID().toString().take(12),
                eventId = eventId,
                sessionId = sessionId,
                sequence = seq,
                capturedAtMillis = now,
                sourceApp = sourceApp,
                windowId = null,
                windowType = null,
                viewportId = viewportId,
                questionId = questionId,
                sourceType = EvidenceSourceType.FAILURE,
                rawText = "",
                contentHash = contentFingerprint("$stage:$message"),
                availability = EvidenceAvailability.STORAGE_FAILED,
                errorStage = stage,
                errorMessage = message,
                processingSource = "failure"
            )
        )
        AppDebugLogStore.i(tag, "failure stage=$stage msg=$message event=$eventId")
    }

    /**
     * 由证据重建当前题目上下文（**唯一**上下文来源）。
     *
     * [updating] 为 true 时状态为 UPDATING；[liveViewportCovered] 为 false 时不得判 READY。
     * 题目变化时自动分配新 questionId 版本段。
     */
    fun rebuildContext(
        questionId: String?,
        updating: Boolean = false,
        liveViewportCovered: Boolean = true,
        boundTargetPackage: String? = null
    ): AssembledQuestionContext = synchronized(lock) {
        val records = if (questionId.isNullOrBlank()) {
            store.recent(limit = 20).sortedBy { it.capturedAtMillis }
        } else {
            store.recentForQuestion(questionId, limit = 60)
        }
        // 证据内容标识：只有「证据集合真正变化」才推进版本号，避免每次提问都凭空 +1。
        val contentKey = records.joinToString("|") { "${it.evidenceId}:${it.capturedAtMillis}" }
        if (questionId != lastQuestionId) {
            lastQuestionId = questionId
            lastContentKey = contentKey
            contextVersion = 1
        } else if (contentKey != lastContentKey) {
            lastContentKey = contentKey
            contextVersion += 1
        }
        val classificationMap = records.associateWith { store.classificationsOf(it.evidenceId) }
        val classifications = classificationMap.values.flatten()
        val imageAssets = if (questionId.isNullOrBlank()) {
            store.recentImages(limit = 20)
        } else {
            store.imagesForQuestion(questionId)
        }
        val filterFailures = records
            .filter { record ->
                classificationMap[record].orEmpty().isEmpty() && record.rawText.isNotBlank()
            }
            .map { it.evidenceId }
        val context = QuestionContextAssembler.build(
            questionId = questionId,
            version = contextVersion,
            records = records,
            classifications = classifications,
            images = imageAssets,
            nowMillis = clock(),
            updating = updating,
            liveViewportCovered = liveViewportCovered,
            filterFailures = filterFailures
        )
        cachedContext = context
        if (boundTargetPackage != null && context.sourceApp != null &&
            context.sourceApp != boundTargetPackage
        ) {
            // 不同 App 的证据不得互相污染：状态降级并显式说明。
            cachedContext = context.copy(
                status = QuestionContextStatus.STALE,
                missing = context.missing + "证据来源与已绑定目标应用不一致",
                note = (context.note ?: "") + "；来源不一致"
            )
        }
        checkNotNull(cachedContext)
    }

    /** 最近一次已构建的上下文（无则从证据重建）。 */
    fun currentContext(): AssembledQuestionContext? = synchronized(lock) { cachedContext }

    /** 对一条已保存证据重新执行过滤（测试中心「重跑过滤器」）。 */
    fun rerunFilter(
        evidenceId: String,
        boundTargetPackage: String? = null,
        screenWidth: Int? = null,
        screenHeight: Int? = null
    ): List<ClassifiedBlock> {
        val record = store.get(evidenceId) ?: return emptyList()
        val blocks = EvidenceFilter.classify(
            evidence = record,
            context = FilterContext(
                sourceApp = record.sourceApp,
                ownPackage = ownPackage,
                windowType = record.windowType,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                boundTargetPackage = boundTargetPackage
            ),
            processedAtMillis = clock()
        )
        val ok = store.saveClassifications(blocks)
        if (!ok) {
            store.markAvailability(evidenceId, EvidenceAvailability.FILTER_FAILED, "过滤结果保存失败")
        }
        return blocks
    }

    /** 请求侧：当前题目相关的可用图像（按采集先后）。 */
    fun imagesFor(questionId: String?): List<ImageAssetRecord> =
        if (questionId.isNullOrBlank()) store.recentImages(limit = 20)
        else store.imagesForQuestion(questionId)

    fun recordTrace(trace: RequestTraceRecord): Boolean = store.saveTrace(trace)

    fun recentTraces(limit: Int = 20): List<RequestTraceRecord> = store.recentTraces(limit)

    fun getTrace(traceId: String): RequestTraceRecord? = store.getTrace(traceId)

    fun recentEvidence(limit: Int = 50): List<EvidenceRecord> = store.recent(limit)

    fun classificationsOf(evidenceId: String): List<ClassifiedBlock> = store.classificationsOf(evidenceId)

    fun latestClassifications(limit: Int = 200): List<ClassifiedBlock> =
        store.latestClassifications(limit)

    fun stats(): EvidenceStats {
        val base = store.stats()
        return base.copy(imageBytes = images.totalBytes())
    }

    fun activeSessionId(): String = synchronized(lock) { sessionId }

    fun lastFailure(): Pair<String?, String?> = synchronized(lock) { lastFailureStage to lastFailureMessage }

    fun lastCaptureAt(): Long? = synchronized(lock) { lastCaptureAtMillis }

    /** 有界清理：只删除超期且非活动题目的证据，并清理对应孤儿图像文件。 */
    fun prune(maxAgeMillis: Long, activeQuestionId: String?) {
        val removed = store.prune(maxAgeMillis, activeQuestionId)
        val keep = (store.recent(limit = 500).flatMap { listOfNotNull(it.imageRef, it.imagePreviewRef) } +
            store.recentImages(limit = 500).flatMap { listOfNotNull(it.originalRef, it.previewRef) })
            .toSet()
        val filesRemoved = images.pruneOrphans(keep)
        if (removed > 0 || filesRemoved > 0) {
            AppDebugLogStore.i(
                tag,
                "prune evidence=$removed files=$filesRemoved active=${activeQuestionId ?: "-"}"
            )
        }
    }

    // ==================== 内部 ====================

    private fun nextSequence(): Long = synchronized(lock) { ++sequence }

    private fun persistAndFilter(
        record: EvidenceRecord,
        text: String,
        sourceApp: String?,
        windowType: String?
    ): EvidenceRecord? {
        val persisted = store.recordCapture(record)
        if (!persisted) {
            lastFailureStage = "evidence_store"
            lastFailureMessage = "原始证据写入失败"
            AppDebugLogStore.e(tag, "evidence_store_failed event=${record.eventId}", null)
            return null
        }
        synchronized(lock) { lastCaptureAtMillis = record.capturedAtMillis }
        if (text.isNotBlank()) {
            val blocks = EvidenceFilter.classify(
                evidence = record,
                context = FilterContext(
                    sourceApp = sourceApp,
                    ownPackage = ownPackage,
                    windowType = windowType,
                    boundTargetPackage = boundTargetPackageProvider()
                ),
                processedAtMillis = clock()
            )
            if (!store.saveClassifications(blocks)) {
                store.markAvailability(record.evidenceId, EvidenceAvailability.FILTER_FAILED, "过滤结果保存失败")
                lastFailureStage = "filter_store"
                lastFailureMessage = "过滤结果保存失败"
                AppDebugLogStore.e(tag, "filter_store_failed evidence=${record.evidenceId}", null)
            }
        }
        return record
    }

    companion object {
        /** 默认保留时长：7 天（活动题目证据不受此限制）。 */
        const val DEFAULT_RETENTION_MILLIS = 7L * 24 * 3600 * 1000
    }
}

/** 供后台/测试中心把 OCR 行转成证据行（避免调用方重复做字段搬运）。 */
internal fun OcrTextLine.toEvidenceLine(): EvidenceOcrLine =
    EvidenceOcrLine(text, left, top, right, bottom)
