package `fun`.kirari.hanako.screencontext

/**
 * 当前题目上下文的完整性（供调用方判断「够不够可靠地送进模型」）。
 *
 * - [COMPLETE]：至少有一个视口片段整理出了**完整、连续**的 A/B/C/D 选项结构。
 * - [INCOMPLETE]：已有内容，但尚未出现可靠的完整选项结构。
 * - [UNKNOWN]：还没有任何视口片段（题目上下文为空）。
 */
internal enum class QuestionCompleteness {
    COMPLETE,
    INCOMPLETE,
    UNKNOWN
}

/** 一次池更新的结果（调用方据此决定要不要提示用户 / 触发重新观察）。 */
internal enum class PoolUpdateResult {
    /** 建立了新的题目上下文。 */
    NEW_QUESTION,

    /** 既有内容为空，本次视口整体作为新增内容。 */
    APPENDED,

    /** 在相邻边界去重后追加了新增内容。 */
    OVERLAP_MERGED,

    /** 回到已采集过的视口（或内容已完整包含），未追加任何内容。 */
    DUPLICATE,

    /** 与既有内容一致，无实质变化。 */
    NO_CHANGE,

    /**
     * R8.6：判定为**同一道题的新片段** —— 无法证明与既有正文连续，但存在同题证据。
     *
     * 该片段作为**独立片段**保存（**不拼接**既有正文），并允许在用户提问时注入（带分段标记）。
     * 这解决了真机「OCR 已识别出完整题目，却因无法证明重叠被整段丢弃、池永久冻结」的问题。
     */
    NEW_SEGMENT,

    /**
     * R8.6：**关联未知** —— 既无法证明连续，也没有足够同题证据。
     *
     * 既有内容保持原样；本次片段被保留供核查，但**不进入最终请求**（不伪装成已确认的同题正文）。
     */
    UNPROVEN,

    /** 画面可能正在变化（文字过短 / 观察间隔过近）或缺少可比对结构，**池内容保持原样**。 */
    UNSTABLE,

    /** 本次没有读到有效文字，**池内容保持原样**（绝不伪装成最新识别结果）。 */
    OCR_FAILED
}

/**
 * 当前题目上下文的**不可变读取快照**。
 *
 * 一旦返回给调用方（例如某次发送请求），后续池的更新**不会**改变它：
 * [fragments] 与 [text] 都是新构造的不可变副本。
 */
internal data class QuestionContextSnapshot(
    val questionKey: String,
    val questionNumber: Int?,
    val windowKey: String?,
    /** 合并后的阅读顺序文本（= 各片段去重拼接结果），行序即注入顺序。 */
    val text: String,
    /** 参与该题目的原始视口片段（按采集先后排列），用于核查来源。 */
    val fragments: List<ViewportFragment>,
    val completeness: QuestionCompleteness,
    val note: String?,
    val updatedAtMillis: Long,
    /**
     * R8.6：本快照可注入文本中**已确认同题片段**的 id（连续正文片段 + 独立同题片段），按注入顺序排列。
     * 用于「同一片段不得重复注入」的核查与 §7 日志 `requestIncludedSegmentIds`。
     *
     * R8.7：它由**实际组装的文本**推导（见 `snapshotLocked`），因此只包含真正贡献了文字的片段。
     */
    val includedSegmentIds: List<String> = emptyList(),
    /**
     * R8.6：因**关联未知**（[FragmentQuestionRelation.UNPROVEN_EXCLUDED]）而被**排除在最终请求之外**的片段 id。
     * 这些片段仍保留供核查，但不得进入模型请求，也不得伪装成已确认的同题正文。
     */
    val excludedSegmentIds: List<String> = emptyList(),
    /**
     * R8.6：已确认同题片段总数。
     *
     * R8.7：等于 [includedSegmentIds] 的数量（**实际贡献了可注入文本**的片段数），
     * 不再是池内片段总数 —— 后者包含被淘汰 / 被去重的片段，不得当作已注入数量。
     */
    val segmentCount: Int = 0
) {
    /** 合并后文本的行序列（合并层工作单位）。 */
    val lines: List<String> get() = ocrLines(text)
}

/**
 * 多视口屏幕上下文池（纯 JVM，无 Android 依赖，不接触截图 / 无障碍 / UI）。
 *
 * 职责：保存**当前题目**的有限数量视口片段与合并结果，并提供明确的一致性读取接口。
 *
 * 一致性规则（全部在 [lock] 内执行，读与写都拿到同一份状态）：
 * 1. 任何一次更新要么整体生效、要么完全不动 —— [PoolUpdateResult.UNSTABLE] /
 *    [PoolUpdateResult.OCR_FAILED] / [PoolUpdateResult.NO_CHANGE] **绝不**修改已合并内容；
 * 2. 空文本 / OCR 失败**不会**覆盖已有有效文本，也不会刷新「最新识别」的时间戳；
 * 3. [currentQuestion] 返回不可变副本，池内部状态的后续变更**不会**影响已发出的快照；
 * 4. 容量有界：原始片段最多 [maxFragments] 条（超出按**采集先后**淘汰最早的一条），
 *    合并文本最长 [maxMergedChars] 字符（超出则本次不合并并返回 [PoolUpdateResult.UNSTABLE]，
 *    绝不静默截断丢失文字）。
 *
 * 注意：本类**不**做任何「无差别全局重复句删除」，去重只发生在相邻边界的连续重叠上
 * （见 [ViewportMerger]）。
 */
internal class ScreenContextPool(
    private val maxFragments: Int = DEFAULT_MAX_FRAGMENTS,
    private val maxMergedChars: Int = DEFAULT_MAX_MERGED_CHARS,
    private val minObservationIntervalMillis: Long = ScreenChangeDetector.MIN_OBSERVATION_INTERVAL_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** R8.6：已确认同题的**独立片段**数量上限（超出按采集先后淘汰最早的一条）。 */
    private val maxSeparateSegments: Int = DEFAULT_MAX_SEPARATE_SEGMENTS,
    /** R8.6：**关联未知**片段（仅保留供核查）的数量上限。 */
    private val maxUnconfirmedFragments: Int = DEFAULT_MAX_UNCONFIRMED_FRAGMENTS
) {
    private val lock = Any()

    private var questionKey: String? = null
    private var questionNumber: Int? = null
    private var windowKey: String? = null
    private var mergedLines: List<String> = emptyList()
    private val fragments = ArrayDeque<ViewportFragment>()
    /**
     * R8.6：已确认同题、但**无法证明与连续正文相邻**的独立片段（按采集先后）。
     * 允许注入，但注入时必须带分段标记（[SEGMENT_BREAK_MARK]），绝不与 [mergedLines] 拼成伪连续原文。
     */
    private val separateSegments = ArrayDeque<ViewportFragment>()
    /** R8.6：**关联未知**（证据不足）的片段，仅保留供核查，**不注入**最终请求。 */
    private val unconfirmedFragments = ArrayDeque<ViewportFragment>()
    private var lastObservation: ScreenObservation? = null
    private var updatedAtMillis: Long = 0L
    private var note: String? = null
    /** 最近一次分类判定的具体原因（只读诊断用，不含题目正文）。 */
    private var lastDecisionNote: String? = null
    private var questionSeq = 0

    /** 当前题目上下文的不可变快照；尚未建立任何题目上下文时返回 null。 */
    fun currentQuestion(): QuestionContextSnapshot? = synchronized(lock) { snapshotLocked() }

    /** 当前已保存的原始片段数量（用于容量断言与诊断）。 */
    fun fragmentCount(): Int = synchronized(lock) { fragments.size }

    /** R8.6：已确认同题、但无法证明连续的**独立片段**数量（允许注入，带分段标记）。 */
    fun separateSegmentCount(): Int = synchronized(lock) { separateSegments.size }

    /** R8.6：**关联未知**、仅保留供核查（不注入）的片段数量。 */
    fun unconfirmedFragmentCount(): Int = synchronized(lock) { unconfirmedFragments.size }

    /** 是否已建立题目上下文。 */
    fun hasQuestion(): Boolean = synchronized(lock) { questionKey != null }

    /**
     * 最近一次分类判定的具体原因（只读诊断；**不含**题目正文）。
     *
     * 用于回答「为什么这次没有合并」：[PoolUpdateResult] 只表达结果种类，
     * 会丢掉判定细节（例如 ⑬ 兜底会带上双方的行数与去空白字数）。
     */
    fun lastDecisionNote(): String? = synchronized(lock) { lastDecisionNote }

    /**
     * 观察一个新的视口片段并尝试合并进当前题目上下文。
     *
     * 判定由 [ScreenChangeDetector] 完成，合并由 [ViewportMerger] 完成；
     * 本方法只负责在两者之间做状态迁移。
     */
    fun observe(fragment: ViewportFragment): PoolUpdateResult = synchronized(lock) {
        val observation = ScreenObservation(
            text = fragment.text,
            capturedAtMillis = fragment.capturedAtMillis,
            // 片段自身未提供窗口标识时沿用池中已知值，避免丢失「同窗口」这一正向信号。
            windowKey = fragment.windowKey ?: windowKey,
            questionNumber = questionNumberOf(fragment.text),
            comparable = fragment.comparable,
            // 仅用于分类器的脱敏诊断：OCR 片段带位图坐标行（视觉行），无障碍片段不带（节点树文本）。
            hasCoordinates = fragment.lines.isNotEmpty()
        )
        val decision = ScreenChangeDetector.classify(lastObservation, observation, minObservationIntervalMillis)
        // 只读诊断：把本次判定的具体原因留在池上（供采集器诊断行展示，不含题目正文）。
        lastDecisionNote = decision.note

        when (decision.kind) {
            // ①/② 没有有效文字：不碰任何状态。
            ScreenChangeKind.OCR_FAILED -> PoolUpdateResult.OCR_FAILED

            // ⑤ 画面不稳定 / ⑫ 证据不足：保持既有内容不变，等待下一次观察。
            ScreenChangeKind.UNSTABLE -> PoolUpdateResult.UNSTABLE

            // ④ 无实质变化：仅刷新观察时间基线，不改内容。
            ScreenChangeKind.NO_CHANGE -> {
                lastObservation = observation
                PoolUpdateResult.NO_CHANGE
            }

            // ③/⑥/⑦ 建立新的题目上下文：新题不继承旧题任何文本。
            ScreenChangeKind.NEW_QUESTION -> {
                establishQuestionLocked(fragment.capturedAtMillis, fragment.windowKey, observation, fragment)
                PoolUpdateResult.NEW_QUESTION
            }

            // ⑨ 回到已采集视口：不追加任何片段，避免无限增长。
            ScreenChangeKind.REPEATED_VIEWPORT -> {
                lastObservation = observation
                if (fragment.windowKey != null) windowKey = fragment.windowKey
                PoolUpdateResult.DUPLICATE
            }

            // ⑩/⑪ 同题内容扩展：交给合并层按相邻边界去重后累积。
            ScreenChangeKind.CONTENT_EXTENDED -> mergeExtendedLocked(observation, fragment)

            // R8.6 ⑬a 同一道题的新片段（有同题证据但无法证明连续）：作为**独立片段**保存，不拼接既有正文。
            ScreenChangeKind.NEW_SEGMENT -> adoptSeparateSegmentLocked(observation, fragment)

            // R8.6 ⑬b 关联未知（证据不足）：既有内容保持原样，本次片段只保留供核查、**不注入**。
            ScreenChangeKind.ASSOCIATION_UNKNOWN -> retainUnconfirmedLocked(observation, fragment)
        }
    }

    /**
     * 显式上报一次「读取失败 / 空文本」。
     *
     * 独立入口的好处：调用方在**没有**片段可提交时（例如无障碍读取失败、OCR 返回空）
     * 也能明确表达「本次失败」，而池不会因此改动任何内容或伪装成一次成功更新。
     */
    fun observeFailure(): PoolUpdateResult = PoolUpdateResult.OCR_FAILED

    /**
     * 主动建立一个新的空题目上下文（用于「用户确认换了新题」或阶段 2 的事件驱动入口）。
     *
     * 新上下文不继承旧文本；[questionKey] 留空时由池自动分配。
     */
    fun startNewQuestion(
        questionKey: String = "",
        windowKey: String? = null,
        atMillis: Long = clock()
    ): QuestionContextSnapshot = synchronized(lock) {
        startNewQuestionLocked(questionKey.takeIf { it.isNotBlank() }, windowKey, atMillis)
        checkNotNull(snapshotLocked())
    }

    /** 清空全部题目上下文与片段（不改变其它任何运行时状态）。 */
    fun clear() = synchronized(lock) {
        questionKey = null
        questionNumber = null
        windowKey = null
        mergedLines = emptyList()
        fragments.clear()
        separateSegments.clear()
        unconfirmedFragments.clear()
        lastObservation = null
        updatedAtMillis = 0L
        note = null
        lastDecisionNote = null
    }

    // ============================== 内部 ==============================

    private fun mergeExtendedLocked(
        observation: ScreenObservation,
        fragment: ViewportFragment
    ): PoolUpdateResult {
        val merged = ViewportMerger.merge(MergeSide(mergedLines), MergeSide(fragment.readingOrderLines))
        return when (merged.outcome) {
            MergeOutcome.APPENDED, MergeOutcome.OVERLAP_MERGED -> {
                val candidate = merged.lines ?: return PoolUpdateResult.UNSTABLE
                val candidateText = candidate.joinToString("\n")
                if (candidateText.length > maxMergedChars) {
                    // 超出上限时宁可保持既有内容，也不静默截断丢字。
                    note = "合并后文本超过 $maxMergedChars 字符上限，本次未合并"
                    PoolUpdateResult.UNSTABLE
                } else {
                    mergedLines = candidate
                    adoptFragmentLocked(observation, fragment)
                    note = merged.note
                    if (merged.outcome == MergeOutcome.APPENDED) {
                        PoolUpdateResult.APPENDED
                    } else {
                        PoolUpdateResult.OVERLAP_MERGED
                    }
                }
            }

            // 合并层发现内容其实已全部包含在既有内容中（滚动重叠区间）：同样不追加。
            MergeOutcome.DUPLICATE -> {
                lastObservation = observation
                if (fragment.windowKey != null) windowKey = fragment.windowKey
                PoolUpdateResult.DUPLICATE
            }

            // 理论不可达（既有内容非空时不会返回 NEW_QUESTION），保守起见仍建立新上下文。
            MergeOutcome.NEW_QUESTION -> {
                establishQuestionLocked(
                    fragment.capturedAtMillis,
                    fragment.windowKey,
                    observation,
                    fragment
                )
                PoolUpdateResult.NEW_QUESTION
            }

            MergeOutcome.UNSTABLE -> PoolUpdateResult.UNSTABLE
        }
    }

    /**
     * R8.6：把「同一道题的新片段」作为**独立片段**保存。
     *
     * 关键安全边界：
     * - **不**并入 [mergedLines]：否则就是把「无法证明相邻」的关系伪装成连续正文，可能造成重复或错序；
     * - 仍计入 [fragments]：它确实是这道题已确认的一部分，参与完整性与容量统计；
     * - 若题目上下文尚未建立（首次观察即被判为「新片段」），则等价于建立新上下文，此时没有「独立」可言。
     */
    private fun adoptSeparateSegmentLocked(
        observation: ScreenObservation,
        fragment: ViewportFragment
    ): PoolUpdateResult {
        val key = questionKey
        if (key == null) {
            establishQuestionLocked(fragment.capturedAtMillis, fragment.windowKey, observation, fragment)
            return PoolUpdateResult.NEW_QUESTION
        }
        // R8.7 去重门控：先量出「相对**全部已保存内容**的新增信息量」，再决定是否保存。
        // 真机证据（hanako_debug_log_20261020_224509）：同一屏被连续识别 11 次，每次 26 行、
        // 共享 120–173 字，全部判为 NEW_SEGMENT → fragments 卡在上限 12、poolChars 在 2160–2168 抖动，
        // 整屏被反复追加，累计内容并未增长。此处必须先挡住这种「回声视口」。
        val novelty = measureViewportNovelty(savedTextLinesLocked(), fragment.readingOrderLines)
        if (novelty.isEcho || novelty.isDuplicate || novelty.newLinesAreChromeOnly) {
            lastObservation = observation
            questionNumber = observation.questionNumber ?: questionNumber
            if (fragment.windowKey != null) windowKey = fragment.windowKey
            updatedAtMillis = fragment.capturedAtMillis
            note = if (novelty.newLinesAreChromeOnly) {
                "同题证据成立，但本次视口新增的行全部是页面壳文字（倒计时 / 标题 / 导航）" +
                    " → 不计入题目材料，不新增片段"
            } else {
                "同题证据成立，但本视口与已保存内容高度重复（重复率 " +
                    "${percentOf(novelty.duplicateRatio)}，覆盖 ${novelty.coveredLineCount}/${novelty.incomingLineCount} 行，" +
                    "新增 ${novelty.newLines.size} 行 / ${novelty.newChars} 字）→ 不重复保存整屏"
            }
            return PoolUpdateResult.DUPLICATE
        }
        if (!novelty.isSubstantive) {
            // 有新增行但不足以作为可注入内容：保持**未确认**状态，绝不伪装成已确认的同题片段。
            note = "同题证据成立，但相对已保存内容新增过少" +
                "（新增 ${novelty.newLines.size} 行 / ${novelty.newChars} 字）→ 记为未确认片段，不注入"
            return retainUnconfirmedLocked(observation, fragment)
        }
        // 只保存**可证明的新增行**：被已保存内容覆盖的行一律不重复保存；
        // 这不是按百分比裁剪，而是逐行去重（保留原始行文本与阅读顺序，不补写任何字）。
        val keptText = novelty.newLines.joinToString("\n")
        val keyed = fragment.copy(
            text = keptText,
            fingerprint = fingerprintOf(keptText),
            // 增量片段只保留部分行，整屏坐标已不再对应这段文字 —— 不虚构坐标。
            lines = emptyList(),
            rows = emptyList(),
            comparable = false,
            questionKey = key,
            questionRelation = FragmentQuestionRelation.SEPARATE_CONFIRMED
        )
        separateSegments.addLast(keyed)
        while (separateSegments.size > maxSeparateSegments) separateSegments.removeFirst()
        fragments.addLast(keyed)
        while (fragments.size > maxFragments) fragments.removeFirst()
        lastObservation = observation
        questionNumber = observation.questionNumber ?: questionNumber
        if (fragment.windowKey != null) windowKey = fragment.windowKey
        updatedAtMillis = fragment.capturedAtMillis
        note = "同题证据无法证明与既有正文连续，已按新增内容保留为独立片段" +
            "（保留 ${novelty.newLines.size} 行 / ${novelty.newChars} 字，" +
            "覆盖 ${novelty.coveredLineCount}/${novelty.incomingLineCount} 行，分段注入、不与既有正文拼接）"
        return PoolUpdateResult.NEW_SEGMENT
    }

    /** R8.7：当前**全部已保存内容**的行序列（连续正文 + 各独立片段），用于计算新增信息量。 */
    private fun savedTextLinesLocked(): List<String> =
        mergedLines + separateSegments.flatMap { it.readingOrderLines }

    private fun percentOf(ratio: Double): String =
        "${(ratio * 100).toInt()}%"

    /**
     * R8.6：保留一个**关联未知**的片段供核查，但**不**改动可注入文本、也不改动判定基线。
     *
     * 既有内容（[mergedLines] / [separateSegments]）完全不动；该片段只进入 [unconfirmedFragments]，
     * 因此在用户提问时被明确排除。刻意**不**更新 [lastObservation]：关联未知的片段不构成新的判定基线，
     * 否则下一次观察会被迫与一个未经确认的内容比对。
     */
    private fun retainUnconfirmedLocked(
        observation: ScreenObservation,
        fragment: ViewportFragment
    ): PoolUpdateResult {
        val key = questionKey ?: return PoolUpdateResult.UNPROVEN
        // 同一帧被反复上报（例如同屏重复事件）时不重复留档：按整理后文本指纹去重，
        // 避免「未确认片段」在无内容变化时无限堆积（与 §6.3「重复采集不得无限重复添加」一致）。
        if (unconfirmedFragments.none { it.fingerprint == fragment.fingerprint }) {
            unconfirmedFragments.addLast(
                fragment.copy(
                    questionKey = key,
                    questionRelation = FragmentQuestionRelation.UNPROVEN_EXCLUDED
                )
            )
            while (unconfirmedFragments.size > maxUnconfirmedFragments) unconfirmedFragments.removeFirst()
        }
        return PoolUpdateResult.UNPROVEN
    }

    /**
     * 建立新的题目上下文，并把首个片段作为初始内容。
     *
     * 必须同时落定 [mergedLines]，否则后续视口会因「既有内容为空」被误判为 APPENDED，
     * 导致第一屏的内容被后续屏幕覆盖（而不是拼接）。
     */
    private fun establishQuestionLocked(
        atMillis: Long,
        windowKey: String?,
        observation: ScreenObservation,
        fragment: ViewportFragment
    ) {
        startNewQuestionLocked(
            requestedKey = null,
            windowKey = windowKey,
            atMillis = atMillis
        )
        mergedLines = fragment.readingOrderLines
        adoptFragmentLocked(observation, fragment)
    }

    private fun adoptFragmentLocked(observation: ScreenObservation, fragment: ViewportFragment) {
        val key = questionKey ?: return
        fragments.addLast(fragment.copy(questionKey = key))
        while (fragments.size > maxFragments) {
            // 明确淘汰规则：按采集先后，淘汰最早的一条。
            fragments.removeFirst()
        }
        lastObservation = observation
        questionNumber = observation.questionNumber ?: questionNumber
        if (fragment.windowKey != null) windowKey = fragment.windowKey
        updatedAtMillis = fragment.capturedAtMillis
    }

    private fun startNewQuestionLocked(
        requestedKey: String?,
        windowKey: String?,
        atMillis: Long
    ) {
        questionKey = requestedKey ?: "q-${++questionSeq}"
        questionNumber = null
        this.windowKey = windowKey
        mergedLines = emptyList()
        fragments.clear()
        separateSegments.clear()
        unconfirmedFragments.clear()
        lastObservation = null
        updatedAtMillis = atMillis
        note = null
    }

    private fun snapshotLocked(): QuestionContextSnapshot? {
        val key = questionKey ?: return null
        val completeness = completenessLocked()
        val completenessNote = when (completeness) {
            QuestionCompleteness.COMPLETE -> null
            QuestionCompleteness.INCOMPLETE -> "尚未出现完整、连续的选项结构，可能需要继续滚动补齐"
            QuestionCompleteness.UNKNOWN -> "尚未采集到任何视口片段"
        }
        // R8.6：可注入文本 = 连续正文 + 各独立同题片段（带明确分段标记）。
        // 独立片段之间、以及它与连续正文之间一律插入 [SEGMENT_BREAK_MARK]，
        // 绝不把它们拼成「看起来连续」的伪原文，也不补写任何 OCR 未识别的字。
        //
        // R8.7：文本与 `includedSegmentIds` **由同一次组装推导**，且对完全相同的行做跨片段去重：
        // - 去重按「行键完全相同」执行，保序，绝不丢掉任何唯一行（不按百分比裁剪）；
        // - 只有真正贡献了文字的片段才进入 `includedSegmentIds`，因此日志里的片段 ID
        //   与最终注入文本**一一对应**（不是把池内片段总数当成已注入数量）。
        val seenLineKeys = HashSet<String>()
        val injectableLines = ArrayList<String>()
        val includedSegmentIds = ArrayList<String>()
        var blockCount = 0

        fun appendBlock(ids: List<String>, lines: List<String>) {
            val kept = ArrayList<String>(lines.size)
            for (line in lines) {
                val lineKey = ocrLineKey(line)
                if (lineKey.isEmpty()) continue
                if (seenLineKeys.add(lineKey)) kept.add(line)
            }
            if (kept.isEmpty()) return
            if (blockCount > 0) injectableLines.add(SEGMENT_BREAK_MARK)
            injectableLines.addAll(kept)
            includedSegmentIds.addAll(ids)
            blockCount++
        }

        // 连续正文片段的来源：仍保存在容量内、且确实被并入正文的片段。
        appendBlock(
            ids = fragments.filter { it.questionRelation == FragmentQuestionRelation.MERGED }.map { it.id },
            lines = mergedLines
        )
        separateSegments.forEach { segment ->
            appendBlock(ids = listOf(segment.id), lines = segment.readingOrderLines)
        }

        return QuestionContextSnapshot(
            questionKey = key,
            questionNumber = questionNumber,
            windowKey = windowKey,
            text = injectableLines.joinToString("\n"),
            // 不可变副本：后续池更新不会改变这个已发出的快照。
            fragments = fragments.toList(),
            completeness = completeness,
            note = listOfNotNull(note, completenessNote).joinToString("；").ifBlank { null },
            updatedAtMillis = updatedAtMillis,
            // 已确认同题片段：**实际贡献了可注入文本**的那些（连续正文 + 独立同题片段），按注入顺序。
            includedSegmentIds = includedSegmentIds,
            // 关联未知片段：保留供核查，但**明确排除**在最终请求之外。
            excludedSegmentIds = unconfirmedFragments.map { it.id },
            segmentCount = includedSegmentIds.size
        )
    }

    private fun completenessLocked(): QuestionCompleteness {
        if (fragments.isEmpty()) return QuestionCompleteness.UNKNOWN
        return if (fragments.any { it.optionAssociation == OcrOptionAssociation.COMPLETE }) {
            QuestionCompleteness.COMPLETE
        } else {
            QuestionCompleteness.INCOMPLETE
        }
    }

    companion object {
        /** 原始视口片段的内存上限，超出后按采集先后淘汰最早的一条。 */
        const val DEFAULT_MAX_FRAGMENTS = 12

        /** 合并文本的字符上限，超出后不再合并（避免长期滚动无限增长，且不静默截断）。 */
        const val DEFAULT_MAX_MERGED_CHARS = 8_000

        /** R8.6：已确认同题的独立片段数量上限。 */
        const val DEFAULT_MAX_SEPARATE_SEGMENTS = 8

        /** R8.6：关联未知片段（仅保留供核查）的数量上限。 */
        const val DEFAULT_MAX_UNCONFIRMED_FRAGMENTS = 6

        /**
         * R8.6：独立片段注入时的**分段标记**。
         *
         * 它只表达「此处与上文不连续」这一事实，既不携带也不补写任何题目文字；
         * 目的是防止多个来源片段被模型误读为一段连续原文（见需求 §4.1 / §4.5）。
         */
        const val SEGMENT_BREAK_MARK = "【同题另一段（与上文不连续，未做拼接）】"
    }
}
