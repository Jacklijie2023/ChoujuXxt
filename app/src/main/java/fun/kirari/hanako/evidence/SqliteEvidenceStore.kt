package `fun`.kirari.hanako.evidence

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 基于 Android 原生 SQLite（[SQLiteOpenHelper]）的原始证据库。
 *
 * 选择 SQLiteOpenHelper 而不是 Room：当前工程未接入 KSP/Room 依赖，引入它们会改变构建配置，
 * 与「不修改无关构建」的约束冲突；SQLiteOpenHelper 零新增依赖即可满足元数据持久化需求。
 *
 * 存储边界（需求 §3.1）：
 * - 数据库只存**元数据与文本**，不存大图 BLOB；图像由 [EvidenceImageFiles] 以文件形式保存；
 * - 每条采集记录独立一行，重复图像通过 [image_asset.sha256] 共享同一文件，但采集事件仍各自保留；
 * - 原文列（raw_text / raw_nodes / ocr_lines）写入后不再更新。
 */
internal class SqliteEvidenceStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), EvidenceStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val tag = "evidence_store"

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS evidence (
                evidence_id TEXT PRIMARY KEY,
                event_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                captured_at INTEGER NOT NULL,
                source_app TEXT,
                window_id TEXT,
                window_type TEXT,
                viewport_id TEXT,
                question_id TEXT,
                source_type TEXT NOT NULL,
                raw_text TEXT NOT NULL,
                raw_nodes TEXT,
                ocr_raw_text TEXT,
                ocr_lines TEXT,
                organized_text TEXT,
                image_ref TEXT,
                image_preview_ref TEXT,
                image_hash TEXT,
                image_region TEXT,
                content_hash TEXT NOT NULL,
                confidence REAL,
                availability TEXT NOT NULL,
                error_stage TEXT,
                error_message TEXT,
                processing_source TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_evidence_time ON evidence(captured_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_evidence_question ON evidence(question_id, captured_at DESC)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS classification (
                evidence_id TEXT NOT NULL,
                line_index INTEGER NOT NULL,
                text TEXT NOT NULL,
                verdict TEXT NOT NULL,
                category TEXT NOT NULL,
                reason TEXT NOT NULL,
                rule_version TEXT NOT NULL,
                confidence REAL NOT NULL,
                processed_at INTEGER NOT NULL,
                PRIMARY KEY (evidence_id, rule_version, line_index)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS image_asset (
                image_id TEXT PRIMARY KEY,
                evidence_id TEXT NOT NULL,
                question_id TEXT,
                original_ref TEXT NOT NULL,
                preview_ref TEXT,
                sha256 TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                available INTEGER NOT NULL,
                unavailable_reason TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS request_trace (
                trace_id TEXT PRIMARY KEY,
                question_id TEXT,
                context_version INTEGER NOT NULL,
                context_status TEXT NOT NULL,
                text_chars INTEGER NOT NULL,
                text_fp TEXT NOT NULL,
                image_ids TEXT NOT NULL,
                image_count INTEGER NOT NULL,
                vision_status TEXT NOT NULL,
                sent INTEGER NOT NULL,
                fail_stage TEXT,
                error_message TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trace_time ON request_trace(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 为初始版本；后续版本必须写真正的迁移，不得 drop 活动证据。
    }

    override fun recordCapture(record: EvidenceRecord): Boolean = runCatching {
        writableDatabase.insertOrThrow("evidence", null, record.toValues())
    }.onFailure {
        AppDebugLogStore.e(tag, "record_capture_failed ${it.javaClass.simpleName} msg=${it.message}", it)
    }.isSuccess

    override fun markAvailability(evidenceId: String, availability: EvidenceAvailability, reason: String?) {
        runCatching {
            writableDatabase.update(
                "evidence",
                ContentValues().apply {
                    put("availability", availability.name)
                    if (reason != null) put("error_message", reason)
                },
                "evidence_id=?",
                arrayOf(evidenceId)
            )
        }
    }

    override fun get(evidenceId: String): EvidenceRecord? = runCatching {
        readableDatabase.query(
            "evidence", null, "evidence_id=?", arrayOf(evidenceId), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }
    }.getOrNull()

    override fun recent(limit: Int): List<EvidenceRecord> = runCatching {
        readableDatabase.query(
            "evidence", null, null, null, null, null, "captured_at DESC", limit.toString()
        ).use { cursor -> cursor.readRecords() }
    }.getOrDefault(emptyList())

    override fun recentForQuestion(questionId: String, limit: Int): List<EvidenceRecord> = runCatching {
        readableDatabase.query(
            "evidence", null, "question_id=?", arrayOf(questionId), null, null,
            "captured_at ASC", limit.toString()
        ).use { cursor -> cursor.readRecords() }
    }.getOrDefault(emptyList())

    override fun saveClassifications(blocks: List<ClassifiedBlock>): Boolean = runCatching {
        val db = writableDatabase
        db.beginTransaction()
        try {
            blocks.forEach { block ->
                db.insertWithOnConflict(
                    "classification",
                    null,
                    ContentValues().apply {
                        put("evidence_id", block.evidenceId)
                        put("line_index", block.lineIndex)
                        put("text", block.text)
                        put("verdict", block.verdict.name)
                        put("category", block.category.name)
                        put("reason", block.reason)
                        put("rule_version", block.ruleVersion)
                        put("confidence", block.confidence)
                        put("processed_at", block.processedAtMillis)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }.onFailure {
        AppDebugLogStore.e(tag, "save_classification_failed ${it.javaClass.simpleName}", it)
    }.isSuccess

    override fun classificationsOf(evidenceId: String): List<ClassifiedBlock> = runCatching {
        readableDatabase.query(
            "classification", null, "evidence_id=?", arrayOf(evidenceId), null, null,
            "processed_at DESC, line_index ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toClassification())
            }
        }
    }.getOrDefault(emptyList())

    override fun latestClassifications(limit: Int): List<ClassifiedBlock> = runCatching {
        readableDatabase.query(
            "classification", null, null, null, null, null, "processed_at DESC", limit.toString()
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toClassification()) }
        }
    }.getOrDefault(emptyList())

    override fun saveImage(asset: ImageAssetRecord): Boolean = runCatching {
        writableDatabase.insertWithOnConflict(
            "image_asset",
            null,
            ContentValues().apply {
                put("image_id", asset.imageId)
                put("evidence_id", asset.evidenceId)
                put("question_id", asset.questionId)
                put("original_ref", asset.originalRef)
                put("preview_ref", asset.previewRef)
                put("sha256", asset.sha256)
                put("captured_at", asset.capturedAtMillis)
                put("width", asset.width)
                put("height", asset.height)
                put("available", if (asset.available) 1 else 0)
                put("unavailable_reason", asset.unavailableReason)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }.onFailure {
        AppDebugLogStore.e(tag, "save_image_failed ${it.javaClass.simpleName}", it)
    }.isSuccess

    override fun imagesForQuestion(questionId: String): List<ImageAssetRecord> = runCatching {
        readableDatabase.query(
            "image_asset", null, "question_id=?", arrayOf(questionId), null, null,
            "captured_at ASC"
        ).use { cursor -> cursor.readImages() }
    }.getOrDefault(emptyList())

    override fun recentImages(limit: Int): List<ImageAssetRecord> = runCatching {
        readableDatabase.query(
            "image_asset", null, null, null, null, null, "captured_at DESC", limit.toString()
        ).use { cursor -> cursor.readImages() }
    }.getOrDefault(emptyList())

    override fun markImageUnavailable(imageId: String, reason: String) {
        runCatching {
            writableDatabase.update(
                "image_asset",
                ContentValues().apply {
                    put("available", 0)
                    put("unavailable_reason", reason)
                },
                "image_id=?",
                arrayOf(imageId)
            )
        }
    }

    override fun saveTrace(trace: RequestTraceRecord): Boolean = runCatching {
        writableDatabase.insertWithOnConflict(
            "request_trace",
            null,
            ContentValues().apply {
                put("trace_id", trace.traceId)
                put("question_id", trace.questionId)
                put("context_version", trace.contextVersion)
                put("context_status", trace.contextStatus)
                put("text_chars", trace.textChars)
                put("text_fp", trace.textFingerprint)
                put("image_ids", trace.imageIds.joinToString(","))
                put("image_count", trace.imageCount)
                put("vision_status", trace.visionStatus)
                put("sent", if (trace.sent) 1 else 0)
                put("fail_stage", trace.failStage)
                put("error_message", trace.errorMessage)
                put("created_at", trace.createdAtMillis)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }.isSuccess

    override fun recentTraces(limit: Int): List<RequestTraceRecord> = runCatching {
        readableDatabase.query(
            "request_trace", null, null, null, null, null, "created_at DESC", limit.toString()
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTrace()) }
        }
    }.getOrDefault(emptyList())

    override fun getTrace(traceId: String): RequestTraceRecord? = runCatching {
        readableDatabase.query(
            "request_trace", null, "trace_id=?", arrayOf(traceId), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toTrace() else null }
    }.getOrNull()

    override fun prune(maxAgeMillis: Long, activeQuestionId: String?): Int {
        val threshold = System.currentTimeMillis() - maxAgeMillis
        return runCatching {
            val db = writableDatabase
            val selection = if (activeQuestionId.isNullOrBlank()) {
                "captured_at < ?"
            } else {
                "captured_at < ? AND (question_id IS NULL OR question_id != ?)"
            }
            val args = if (activeQuestionId.isNullOrBlank()) {
                arrayOf(threshold.toString())
            } else {
                arrayOf(threshold.toString(), activeQuestionId)
            }
            val removedIds = mutableListOf<String>()
            db.query("evidence", arrayOf("evidence_id"), selection, args, null, null, null)
                .use { cursor -> while (cursor.moveToNext()) removedIds += cursor.getString(0) }
            db.beginTransaction()
            try {
                removedIds.forEach { db.delete("evidence", "evidence_id=?", arrayOf(it)) }
                db.delete("classification", "evidence_id NOT IN (SELECT evidence_id FROM evidence)", null)
                db.delete("image_asset", "evidence_id NOT IN (SELECT evidence_id FROM evidence)", null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            removedIds.size
        }.getOrDefault(0)
    }

    override fun stats(): EvidenceStats = runCatching {
        val db = readableDatabase
        fun count(table: String, where: String? = null): Int =
            db.query(table, arrayOf("COUNT(*)"), where, null, null, null, null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        val lastCapture = db.query(
            "evidence", arrayOf("MAX(captured_at)"), null, null, null, null, null
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        EvidenceStats(
            evidenceCount = count("evidence"),
            imageCount = count("image_asset", "available=1"),
            imageMissingCount = count("image_asset", "available=0"),
            databaseBytes = 0L,
            imageBytes = 0L,
            lastCaptureAtMillis = lastCapture
        )
    }.getOrDefault(EvidenceStats(0, 0, 0, 0, 0, null))

    private fun EvidenceRecord.toValues(): ContentValues = ContentValues().apply {
        put("evidence_id", evidenceId)
        put("event_id", eventId)
        put("session_id", sessionId)
        put("seq", sequence)
        put("captured_at", capturedAtMillis)
        put("source_app", sourceApp)
        put("window_id", windowId)
        put("window_type", windowType)
        put("viewport_id", viewportId)
        put("question_id", questionId)
        put("source_type", sourceType.name)
        put("raw_text", rawText)
        put(
            "raw_nodes",
            json.encodeToString(ListSerializer(EvidenceNode.serializer()), rawNodes)
        )
        put("ocr_raw_text", ocrRawText)
        put(
            "ocr_lines",
            json.encodeToString(ListSerializer(EvidenceOcrLine.serializer()), ocrLines)
        )
        put("organized_text", organizedText)
        put("image_ref", imageRef)
        put("image_preview_ref", imagePreviewRef)
        put("image_hash", imageHash)
        put("image_region", imageRegion)
        put("content_hash", contentHash)
        put("confidence", confidence)
        put("availability", availability.name)
        put("error_stage", errorStage)
        put("error_message", errorMessage)
        put("processing_source", processingSource)
    }

    private fun Cursor.readRecords(): List<EvidenceRecord> = buildList {
        while (moveToNext()) add(toRecord())
    }

    private fun Cursor.readImages(): List<ImageAssetRecord> = buildList {
        while (moveToNext()) add(toImage())
    }

    private fun Cursor.toRecord(): EvidenceRecord = EvidenceRecord(
        evidenceId = getString(getColumnIndexOrThrow("evidence_id")),
        eventId = getString(getColumnIndexOrThrow("event_id")),
        sessionId = getString(getColumnIndexOrThrow("session_id")),
        sequence = getLong(getColumnIndexOrThrow("seq")),
        capturedAtMillis = getLong(getColumnIndexOrThrow("captured_at")),
        sourceApp = getString(getColumnIndexOrThrow("source_app")),
        windowId = getString(getColumnIndexOrThrow("window_id")),
        windowType = getString(getColumnIndexOrThrow("window_type")),
        viewportId = getString(getColumnIndexOrThrow("viewport_id")),
        questionId = getString(getColumnIndexOrThrow("question_id")),
        sourceType = runCatching {
            EvidenceSourceType.valueOf(getString(getColumnIndexOrThrow("source_type")))
        }.getOrDefault(EvidenceSourceType.FAILURE),
        rawText = getString(getColumnIndexOrThrow("raw_text")).orEmpty(),
        rawNodes = decodeNodes(getString(getColumnIndexOrThrow("raw_nodes"))),
        ocrRawText = getString(getColumnIndexOrThrow("ocr_raw_text")),
        ocrLines = decodeLines(getString(getColumnIndexOrThrow("ocr_lines"))),
        organizedText = getString(getColumnIndexOrThrow("organized_text")),
        imageRef = getString(getColumnIndexOrThrow("image_ref")),
        imagePreviewRef = getString(getColumnIndexOrThrow("image_preview_ref")),
        imageHash = getString(getColumnIndexOrThrow("image_hash")),
        imageRegion = getString(getColumnIndexOrThrow("image_region")),
        contentHash = getString(getColumnIndexOrThrow("content_hash")).orEmpty(),
        confidence = getFloat(getColumnIndexOrThrow("confidence")).takeUnless { isNull(getColumnIndexOrThrow("confidence")) },
        availability = runCatching {
            EvidenceAvailability.valueOf(getString(getColumnIndexOrThrow("availability")))
        }.getOrDefault(EvidenceAvailability.STORED),
        errorStage = getString(getColumnIndexOrThrow("error_stage")),
        errorMessage = getString(getColumnIndexOrThrow("error_message")),
        processingSource = getString(getColumnIndexOrThrow("processing_source"))
    )

    private fun Cursor.toClassification(): ClassifiedBlock = ClassifiedBlock(
        evidenceId = getString(getColumnIndexOrThrow("evidence_id")),
        lineIndex = getInt(getColumnIndexOrThrow("line_index")),
        text = getString(getColumnIndexOrThrow("text")).orEmpty(),
        verdict = runCatching {
            BlockVerdict.valueOf(getString(getColumnIndexOrThrow("verdict")))
        }.getOrDefault(BlockVerdict.UNCERTAIN),
        category = runCatching {
            BlockCategory.valueOf(getString(getColumnIndexOrThrow("category")))
        }.getOrDefault(BlockCategory.UNKNOWN),
        reason = getString(getColumnIndexOrThrow("reason")).orEmpty(),
        ruleVersion = getString(getColumnIndexOrThrow("rule_version")).orEmpty(),
        confidence = getFloat(getColumnIndexOrThrow("confidence")),
        processedAtMillis = getLong(getColumnIndexOrThrow("processed_at"))
    )

    private fun Cursor.toImage(): ImageAssetRecord = ImageAssetRecord(
        imageId = getString(getColumnIndexOrThrow("image_id")),
        evidenceId = getString(getColumnIndexOrThrow("evidence_id")),
        questionId = getString(getColumnIndexOrThrow("question_id")),
        originalRef = getString(getColumnIndexOrThrow("original_ref")).orEmpty(),
        previewRef = getString(getColumnIndexOrThrow("preview_ref")),
        sha256 = getString(getColumnIndexOrThrow("sha256")).orEmpty(),
        capturedAtMillis = getLong(getColumnIndexOrThrow("captured_at")),
        width = getInt(getColumnIndexOrThrow("width")),
        height = getInt(getColumnIndexOrThrow("height")),
        available = getInt(getColumnIndexOrThrow("available")) != 0,
        unavailableReason = getString(getColumnIndexOrThrow("unavailable_reason"))
    )

    private fun Cursor.toTrace(): RequestTraceRecord = RequestTraceRecord(
        traceId = getString(getColumnIndexOrThrow("trace_id")),
        questionId = getString(getColumnIndexOrThrow("question_id")),
        contextVersion = getInt(getColumnIndexOrThrow("context_version")),
        contextStatus = getString(getColumnIndexOrThrow("context_status")).orEmpty(),
        textChars = getInt(getColumnIndexOrThrow("text_chars")),
        textFingerprint = getString(getColumnIndexOrThrow("text_fp")).orEmpty(),
        imageIds = getString(getColumnIndexOrThrow("image_ids")).orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() },
        imageCount = getInt(getColumnIndexOrThrow("image_count")),
        visionStatus = getString(getColumnIndexOrThrow("vision_status")).orEmpty(),
        sent = getInt(getColumnIndexOrThrow("sent")) != 0,
        failStage = getString(getColumnIndexOrThrow("fail_stage")),
        errorMessage = getString(getColumnIndexOrThrow("error_message")),
        createdAtMillis = getLong(getColumnIndexOrThrow("created_at"))
    )

    private fun decodeNodes(raw: String?): List<EvidenceNode> = runCatching {
        json.decodeFromString(ListSerializer(EvidenceNode.serializer()), raw.orEmpty())
    }.getOrDefault(emptyList())

    private fun decodeLines(raw: String?): List<EvidenceOcrLine> = runCatching {
        json.decodeFromString(ListSerializer(EvidenceOcrLine.serializer()), raw.orEmpty())
    }.getOrDefault(emptyList())

    private companion object {
        const val DATABASE_NAME = "hanako_evidence.db"
        const val DATABASE_VERSION = 1
    }
}
