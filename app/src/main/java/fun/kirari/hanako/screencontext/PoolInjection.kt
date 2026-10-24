package `fun`.kirari.hanako.screencontext

/**
 * 「把累计同题快照注入请求」的判定结果（只读诊断用，**不含**题目正文）。
 *
 * 存在的理由：累计文本池（[ScreenContextPool]）会跨屏合并同一道题的题干 / 选项，
 * 但历史设计刻意让 [currentScreenContextForPrompt] **不读取**该池，导致「同题滚动积累的
 * 内容无法进入最终请求」。本判定在**不改动任何安全门控**的前提下，证明「池内容与当前屏幕
 * 确属同一道题」后，才允许把池快照注入请求；否则一律回退到单次屏幕读取。
 *
 * 与「写池」的门控相互独立：本判定只决定**注入**，写池仍由采集器自身的门控负责。
 */
internal enum class PoolInjectionDecision {
    /** 池快照已被证明与本屏同题同应用，可注入。 */
    INJECT_POOL,

    /** 尚未建立目标应用绑定（AUTO 未确认 / RESTRICTED 未绑定 / 绑定为空）：不得注入。 */
    NO_BOUND_TARGET,

    /** 池中尚无当前题目上下文（实时模式刚开启 / 还没有任何视口片段）：无可注入内容。 */
    NO_POOL_QUESTION,

    /** 池当前题目的所属应用与已绑定目标不一致：绝不注入其它应用的内容。 */
    POOL_WINDOW_MISMATCH,

    /** 当前前台窗口不是已绑定目标（用户已切走 / 无法确认）：不得注入。 */
    CURRENT_WINDOW_NOT_TARGET,

    /** 池快照无法被当前视口证明为同题（例如用户已翻到一道尚未采集的新题）：回退单次读取。 */
    VIEWPORT_NOT_COVERED,

    /**
     * 池当前题目的**最近一次读取失败**（[PoolUpdateResult.OCR_FAILED]）：当前屏幕状态未知，
     * 宁可不注入（回退单次读取），也绝不把可能属于旧题的内容送进请求。
     *
     * R8.6 变更：[PoolUpdateResult.UNSTABLE] / [PoolUpdateResult.UNPROVEN] **不再**触发本分支 ——
     * 它们都不会改变池中**已经确认同题**的可注入内容，一律拒绝会使正常的跨滚动累计失效。
     */
    POOL_UNSETTLED,

    /**
     * 池当前题目的题号与当前视口题号**都能解析且不同** → 已切换到另一道题，绝不注入旧题池。
     *
     * 这是「一拖 N」共享材料题（资料分析）下唯一可靠的区别信号：材料相同、题号不同。
     */
    POOL_NOT_SAME_QUESTION
}

/** 参与覆盖判定时视为「有意义」的最小去空白行字符数。 */
internal const val MIN_MEANINGFUL_POOL_LINE_CHARS = 2

/** 判定「池覆盖当前视口」所需的最小有意义行覆盖率。 */
internal const val POOL_COVERAGE_MIN_RATIO = 0.5

/** 去空白后的非空行（用于覆盖判定；不改动任何匹配阈值）。 */
private fun normalizedNonBlankLines(text: String): List<String> =
    ocrLines(text).map { line -> line.filterNot { it.isWhitespace() } }.filter { it.isNotBlank() }

/**
 * 当前视口中有意义行被池内容覆盖的比例（0..1）。
 *
 * 「覆盖」定义为：视口行的去空白内容被**某一条池行**包含。池行按同题跨屏累积，
 * 因此同题滚动后当前屏应能被池覆盖；翻到新题时则覆盖比例骤降。
 */
internal fun viewportCoverageRatio(poolText: String, viewportText: String): Double {
    val pool = normalizedNonBlankLines(poolText)
    val viewport = normalizedNonBlankLines(viewportText)
    if (pool.isEmpty() || viewport.isEmpty()) return 0.0
    val meaningful = viewport.filter { it.length >= MIN_MEANINGFUL_POOL_LINE_CHARS }
    val candidates = if (meaningful.isEmpty()) viewport else meaningful
    val covered = candidates.count { v -> pool.any { it.contains(v) } }
    return covered.toDouble() / candidates.size
}

/**
 * 池是否可被认为是「当前视口的超集」（同一道题，且已包含当前屏内容）。
 *
 * 条件：池行数不少于视口行数，且覆盖率达到 [POOL_COVERAGE_MIN_RATIO]。
 * 两者都不满足时必须保守回退到单次读取，绝不用可能属于旧题的内容替换当前屏。
 */
internal fun poolSupersedesViewport(poolText: String, viewportText: String): Boolean {
    val pool = normalizedNonBlankLines(poolText)
    val viewport = normalizedNonBlankLines(viewportText)
    if (pool.isEmpty() || viewport.isEmpty()) return false
    if (pool.size < viewport.size) return false
    return viewportCoverageRatio(poolText, viewportText) >= POOL_COVERAGE_MIN_RATIO
}

/**
 * 决定本次请求是否注入累计同题快照。
 *
 * 判定顺序（任一不满足即回退单次读取，并给出可定位的具体原因）：
 * 1. 已绑定目标应用（[boundPackage] 非空且非空白）；
 * 2. 池中已有当前题目上下文（[poolWindowKey] 非空且 [poolText] 非空白）；
 * 3. 池当前题目的应用 == 已绑定目标（绝不注入其它应用内容）；
 * 4. 当前前台窗口应用 == 已绑定目标（用户仍在该应用）；
 * 5. **题号隔离（R8.3）**：池题号与视口题号都能解析且**不同** → 判为换题，绝不注入（[POOL_NOT_SAME_QUESTION]）；
 * 6. **当前视口隔离（R8.6）**：当前视口读不到任何有效文字 → 无法确认用户仍在这道题上（[VIEWPORT_NOT_COVERED]）；
 * 7. **读取失败隔离（R8.6）**：池最近判定为 OCR 读取失败 → 当前屏幕状态未知（[POOL_UNSETTLED]）。
 *
 * R8.6 变更：**取消**了旧的两条硬门控（「池最近判定 UNSTABLE/OCR_FAILED 一律不注入」、
 * 「池必须覆盖当前视口才注入」）。原因见真机证据：粉笔长题跨滚动时，相邻视口之间往往
 * 只有部分材料重叠，池无法覆盖当前屏是**正常**的；而旧门控会把这种正常情况判为「不可注入」，
 * 使跨屏累计彻底失效（需求 §4.5 要求注入的正是「已可靠累计的同题内容 + 当前视口」）。
 * 门控 6/7 仍保证：无法确认用户仍在该题、或当前屏幕状态未知时，绝不注入。
 */
internal fun decidePoolInjection(
    boundPackage: String?,
    currentWindowPackage: String?,
    poolWindowKey: String?,
    poolText: String,
    viewportText: String,
    /**
     * 池当前题目的最近一次判定结果（[RealtimeViewportCollector] 的 `status().lastResult`）。
     * 为 `UNSTABLE` / `OCR_FAILED` 时池无法证明覆盖当前屏 → 回退单次读取。默认 null = 不做该判定（兼容既有调用）。
     */
    poolLastResult: PoolUpdateResult? = null,
    /** 池当前题目解析出的题号（`status().questionNumber`）。 */
    poolQuestionNumber: Int? = null,
    /** 当前实时视口解析出的题号（[questionNumberOf]）。 */
    viewportQuestionNumber: Int? = null
): PoolInjectionDecision {
    val bound = boundPackage?.trim()?.ifBlank { null } ?: return PoolInjectionDecision.NO_BOUND_TARGET
    if (poolWindowKey.isNullOrBlank() || poolText.isBlank()) return PoolInjectionDecision.NO_POOL_QUESTION
    if (poolWindowKey != bound) return PoolInjectionDecision.POOL_WINDOW_MISMATCH
    if (currentWindowPackage?.trim() != bound) return PoolInjectionDecision.CURRENT_WINDOW_NOT_TARGET
    // R8.3：题号正向矛盾 —— 共享材料题下「材料相同、题号不同」即已换题，绝不注入旧题池。
    if (poolQuestionNumber != null && viewportQuestionNumber != null &&
        poolQuestionNumber != viewportQuestionNumber
    ) {
        return PoolInjectionDecision.POOL_NOT_SAME_QUESTION
    }
    // R8.6：**不再**要求「池必须覆盖当前视口」。跨滚动长题累计的意义恰恰在于：
    //   已确认的同题片段（连续正文 + 独立片段）本来就是「可靠的同题内容」，
    //   它们与当前这一屏不连续是**正常**的，不应因此被拒绝注入。
    //   但仍保留两道保守门控：
    //   (a) 池最近一次判定是「读取失败」(OCR_FAILED) → 当前屏幕状态未知 → 回退单次读取；
    //   (b) 当前屏必须与池有**可证明的同题联系**（池覆盖本屏，或共享一段足够长的连续材料）——
    //       否则无法确认用户仍停在这道题上，绝不把旧题池注入当前（可能是另一道题的）屏幕。
    //   注意：「关联未知」(UNPROVEN) 与「画面不稳定」(UNSTABLE) 不再一律拒绝注入 ——
    //   它们都不会改变池中**已经确认同题**的可注入内容（关联未知片段根本不进入可注入文本）。
    if (poolLastResult == PoolUpdateResult.OCR_FAILED) {
        return PoolInjectionDecision.POOL_UNSETTLED
    }
    val related = poolSupersedesViewport(poolText, viewportText) ||
        ScreenChangeDetector.sharedContiguousChars(poolText, viewportText) >=
        ScreenChangeDetector.MIN_SEGMENT_SHARED_CHARS
    if (!related) return PoolInjectionDecision.VIEWPORT_NOT_COVERED
    return PoolInjectionDecision.INJECT_POOL
}

// ============================== 请求级组装（R8.6 §4.5 / R8.7 P2） ==============================

/**
 * R8.7：**本次新鲜视口**的标记。
 *
 * 与「累计片段之间的分段标记」[ScreenContextPool.SEGMENT_BREAK_MARK] 区分开：
 * 前者表达「这是本次请求真正采集到的当前屏」，后者只表达「同一道题的另一段」。
 * 两个标记必须不同，模型才能区分「已确认的累计内容」与「最新视口」。
 */
internal const val LIVE_VIEWPORT_MARK = "【当前屏幕（本次新鲜采集，与上方累计内容不连续）】"

/**
 * R8.7：本次**没有**采集到可用当前视口时的显式说明。
 *
 * 目的：绝不把「只有累计内容」冒充成「已获取当前完整题目」。该行不补写任何题目文字，
 * 只陈述采集事实，使模型明确知道当前屏未被采信。
 */
internal const val LIVE_VIEWPORT_UNAVAILABLE_MARK =
    "【本次未采集到当前屏幕，上文仅为此前累计的同题内容，请勿当作当前完整题目】"

/**
 * 一次「累计片段 + 本次视口」的组装结果（纯数据，可直接单测）。
 *
 * - [text]：最终交给模型的屏幕上下文文本。
 * - [liveIncluded]：本次新鲜视口是否被并入（未并入时给出 [note] 原因，绝不静默丢弃）。
 * - [liveVerified]：本次视口是否**被采信为当前屏幕**（false = 未采集到 / 被内容质量门控阻断）。
 * - [hasLiveMarker]：最终文本中是否出现 [LIVE_VIEWPORT_MARK]。
 * - [note]：可读的组装说明（**不含**题目正文）。
 */
internal data class RequestScreenContextAssembly(
    val text: String,
    val liveIncluded: Boolean,
    val note: String,
    val liveVerified: Boolean = true,
    val hasLiveMarker: Boolean = false
)

/**
 * 按可信度组装最终送给模型的屏幕上下文（需求 §4.5 / R8.7 P2）。
 *
 * 顺序：已确认同题的累计片段（[ScreenContextPool.currentQuestion] 的 `text`）在前，
 * 本次新鲜视口在后，两者之间用 [LIVE_VIEWPORT_MARK] 明确分段 ——
 * 这是两个**不同来源**，绝不允许被读成一段连续原文。
 *
 * 安全边界：
 * - **同一片段不得重复注入**：本次视口的内容若已完整包含在累计文本中，则不再重复追加；
 * - 累计文本为空时只给本次视口；本次视口为空/不可用时只给累计文本，并显式声明未采集到当前屏
 *   （[LIVE_VIEWPORT_UNAVAILABLE_MARK]），绝不声称已获取当前完整题目；
 * - 两者都为空时返回空文本（由调用方按「识别失败」处理，绝不合成内容）；
 * - **不补写**任何 OCR 未识别的字，只做拼接与去重判定。
 */
internal fun assembleAccumulatedWithLive(
    accumulatedText: String,
    liveText: String?,
    liveViewportId: String? = null,
    /** 本次视口不可用的原因码（非敏感）；非空时会把「未采集到当前屏幕」写进注入文本。 */
    liveUnavailableReason: String? = null
): RequestScreenContextAssembly {
    val accumulated = accumulatedText.trim()
    val live = liveText?.trim().orEmpty()
    if (accumulated.isEmpty() && live.isEmpty()) {
        return RequestScreenContextAssembly("", false, "累计片段与本次视口都没有可用文字", liveVerified = false)
    }
    if (live.isEmpty()) {
        val reason = liveUnavailableReason?.trim()?.takeIf { it.isNotBlank() }
        val text = if (reason == null) {
            accumulated
        } else {
            accumulated + "\n" + LIVE_VIEWPORT_UNAVAILABLE_MARK + "（原因：$reason）"
        }
        return RequestScreenContextAssembly(
            text = text,
            liveIncluded = false,
            note = if (reason == null) {
                "本次视口没有可用文字，仅使用累计片段"
            } else {
                "本次视口不可用（$reason），仅使用累计片段并显式声明未采集到当前屏幕"
            },
            liveVerified = false
        )
    }
    if (accumulated.isEmpty()) {
        return RequestScreenContextAssembly(
            live,
            true,
            "暂无累计片段，仅使用本次视口",
            liveVerified = true
        )
    }
    // 本次视口已完整落在累计文本中（同一屏被重复识别）→ 不重复注入。
    if (poolSupersedesViewport(accumulated, live)) {
        return RequestScreenContextAssembly(
            accumulated,
            false,
            "本次视口内容已包含在累计片段中，未重复注入",
            liveVerified = true
        )
    }
    val idNote = liveViewportId?.takeIf { it.isNotBlank() }?.let { "（本次视口 $it）" } ?: ""
    return RequestScreenContextAssembly(
        text = accumulated + "\n" + LIVE_VIEWPORT_MARK + "\n" + live,
        liveIncluded = true,
        note = "累计同题片段 + 本次视口已分段组装$idNote（未做任何拼接改写）",
        liveVerified = true,
        hasLiveMarker = true
    )
}
