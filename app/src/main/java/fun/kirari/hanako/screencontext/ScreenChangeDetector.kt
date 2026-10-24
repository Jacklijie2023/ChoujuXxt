package `fun`.kirari.hanako.screencontext

/**
 * 屏幕变化分类器（纯函数式，无 Android 依赖，也不修改任何运行时状态）。
 *
 * 定位：下一阶段把无障碍事件接入 [ScreenContextPool] 时，**先**用它把「一次新的屏幕观察」
 * 分成「无变化 / 同题扩展 / 回到已采集视口 / 换新题 / 画面不稳定 / OCR 失败」，
 * 再决定要不要动文本池。本轮只实现判定规则，不接入 [fun.kirari.hanako.accessibility.HanakoAccessibilityService]，
 * 也不触发任何后台截图 / OCR。
 *
 * 切题判定的原则（对应「不能只依赖文本相似度」的要求）：
 * 1. **正向证据优先**：目标窗口标识变化、或两侧都能解析出题号且不同 —— 直接判新题；
 * 2. **行关系其次**：新视口整体出现在既有行序列中 → 回到已采集视口；边界存在连续重叠、
 *    或既有行按**原顺序**整体出现在新视口中 → 同题内容扩展；
 * 3. **拿不准就不动**：没有任何可证明的证据时返回 [ScreenChangeKind.UNSTABLE]，
 *    绝不因为「文本不像」就断定换了新题（错判会把整题上下文丢掉）。
 */
internal enum class ScreenChangeKind {
    /** 与既有内容一致，无实质变化（不刷时间戳以外的任何状态）。 */
    NO_CHANGE,

    /** 同一题继续滚动，出现了新的内容。 */
    CONTENT_EXTENDED,

    /** 滚回已经采集过的视口。 */
    REPEATED_VIEWPORT,

    /** 有正向证据表明换题（窗口变化 / 题号变化 / 首次观察）。 */
    NEW_QUESTION,

    /**
     * R8.6：无法证明与既有内容连续，但存在**同题证据**（共享内容达到阈值 / 双方题号一致）——
     * 判定为「同一道题的新片段」。调用方必须把它作为**独立片段**保留并允许注入（带分段标记），
     * **绝不**与既有正文拼成伪连续原文。
     */
    NEW_SEGMENT,

    /**
     * R8.6：既无法证明连续、也没有足够同题证据 —— **关联未知**。
     * 调用方保留既有片段与该新片段供核查，但该新片段**不得进入最终请求**
     * （不得伪装成已确认的同题正文，也不得据此认定换题）。
     */
    ASSOCIATION_UNKNOWN,

    /** 画面可能正在变化（文字过短 / 观察间隔过近），或缺少可比对结构，无法判定。 */
    UNSTABLE,

    /** 本次没有读到任何有效文字。 */
    OCR_FAILED
}

/**
 * 一次屏幕观察（纯数据）。
 *
 * [questionNumber] 允许为 null：由调用方在能可靠解析时填入；未填时 [ScreenChangeDetector]
 * 会尝试从 [text] 解析（见 [questionNumberOf]），仍解析不到则视为「无题号信号」。
 * [comparable] 表示本次是否具备可靠的行级比对条件（通常来自 [ViewportFragment.comparable]）。
 * [hasCoordinates] 表示本次文本是否带**位图坐标**（OCR 视觉行 vs 无障碍节点树文本）：仅用于
 * [ScreenChangeDetector] 的只读诊断摘要，**不参与任何判定**，避免把来源差异当成判据。
 */
internal data class ScreenObservation(
    val text: String,
    val capturedAtMillis: Long,
    val windowKey: String? = null,
    val questionNumber: Int? = null,
    val comparable: Boolean = true,
    val hasCoordinates: Boolean = true
)

internal data class ScreenChangeDecision(
    val kind: ScreenChangeKind,
    /** 可读的判定原因（仅用于诊断，**不含**题目正文）。 */
    val note: String?
)

internal object ScreenChangeDetector {
    /** 低于该字符数的观察视为「画面尚未稳定」，不参与切题判定。 */
    const val MIN_STABLE_CHARS = 8

    /** 两次观察的最小间隔；过近时按「画面正在变化」处理，避免把滚动中间态当成新题。 */
    const val MIN_OBSERVATION_INTERVAL_MILLIS = 250L

    /**
     * 「字符级整体包含」这条兜底证据的最小长度（本类与 [ViewportMerger] 共用）。
     *
     * 无障碍节点树按**控件**换行、OCR 按**视觉行**换行，同一段题干在两侧的行比对键因此常常对不上，
     * 行级规则 ⑨/⑩/⑪ 会全部落空。该阈值保证这条兜底证据只对足够长的内容生效，
     * 避免短通用文案（如工具栏、按钮文字）造成误合并。
     */
    const val MIN_CONTAINMENT_CHARS = 12

    /**
     * 「有序子序列包含」这条行级证据的最小行数（本类与 [ViewportMerger] 共用）。
     *
     * 既有内容的行**按原顺序**（不要求连续）全部出现在本次视口中 —— 说明本次视口是既有内容的超集，
     * 既有内容仍显示在这一屏上，只是被 OCR 的行切分／碎片插入打散。该阈值避免仅凭一两行通用文案误判。
     */
    const val MIN_ORDERED_CONTAINMENT_LINES = 2

    /**
     * R8.6：「同一道题的新片段」这条同题证据所需的最小**连续公共字符数**（去空白后）。
     *
     * 真机证据（粉笔长题跨滚动）：旧池 178 字与新 OCR 184 字之间既有行命中 6/22、最长连续公共段 48 字 ——
     * 共享 48 个连续字符足以证明两侧仍在读同一段材料，只是不构成「整体包含」。
     *
     * 阈值为什么明显高于 [MIN_CONTAINMENT_CHARS]：需求 §4.1 明确「禁止只通过『页面标题相同』
     * 就把不同题目合并」。刷题 App 的**固定页面标题 / 练习名**（如粉笔的「专项智能练习（资料分析）」
     * 恰好 12 字）在整屏任意题目间都会出现，若阈值取 12 会把「同一 App 的相邻两道题」误判为同题。
     * 取 20 使这类固定 UI 文案（标题 / 工具栏）不再构成同题证据，而真机的长材料重叠（≥ 48 字）仍能成立。
     */
    const val MIN_SEGMENT_SHARED_CHARS = 20

    /**
     * R8.6：请求级「池是否仍属于当前题目」证据判定时，两侧各自参与比对的最大字符数。
     * 仅用于控制单次请求的比对开销（[sharedContiguousChars]），不改变任何阈值语义。
     */
    const val MAX_EVIDENCE_CHARS = 2_000

    /**
     * 分类一次新观察。
     *
     * @param previous 上一次**有效**观察（null 表示首次观察）。
     * @param incoming 本次观察。
     * @param minObservationIntervalMillis 两次观察的最小间隔，便于测试与后续调参。
     */
    fun classify(
        previous: ScreenObservation?,
        incoming: ScreenObservation,
        minObservationIntervalMillis: Long = MIN_OBSERVATION_INTERVAL_MILLIS
    ): ScreenChangeDecision {
        val incKeys = comparableKeys(incoming.text)
        val incSignature = signatureOf(incoming.text)

        // ① 没有任何有效文字：OCR 失败 / 空文本。
        if (incSignature.isEmpty()) {
            return ScreenChangeDecision(
                ScreenChangeKind.OCR_FAILED,
                "本次没有读到任何有效文字，保持既有上下文不变"
            )
        }

        // ② 文字过短：画面可能正在滚动 / 刷新，尚不稳定。
        if (incSignature.length < MIN_STABLE_CHARS) {
            return ScreenChangeDecision(
                ScreenChangeKind.UNSTABLE,
                "本次识别文字过短（${incSignature.length} 字），画面可能尚未稳定"
            )
        }

        // ③ 首次观察：没有可比较的既有内容，建立新题目上下文。
        if (previous == null) {
            return ScreenChangeDecision(
                ScreenChangeKind.NEW_QUESTION,
                "首次观察，建立新的题目上下文"
            )
        }

        val prevKeys = comparableKeys(previous.text)
        val prevSignature = signatureOf(previous.text)

        // ④ 去空白后完全相同：无实质变化（**不看**时间间隔，避免同一画面被反复判为不稳定）。
        if (prevSignature == incSignature) {
            return ScreenChangeDecision(
                ScreenChangeKind.NO_CHANGE,
                "与既有内容一致，无需更新"
            )
        }

        // ⑤ 间隔过近：画面正在变化，暂不判定切题。
        val elapsed = incoming.capturedAtMillis - previous.capturedAtMillis
        if (elapsed in 0 until minObservationIntervalMillis) {
            return ScreenChangeDecision(
                ScreenChangeKind.UNSTABLE,
                "距上次观察仅 $elapsed ms（< $minObservationIntervalMillis ms），画面可能正在变化"
            )
        }

        // ⑥ 正向证据一：目标窗口标识变化。
        val prevWindow = previous.windowKey
        val incWindow = incoming.windowKey
        if (prevWindow != null && incWindow != null && prevWindow != incWindow) {
            return ScreenChangeDecision(
                ScreenChangeKind.NEW_QUESTION,
                "目标窗口由 $prevWindow 变为 $incWindow，判定为新的题目上下文"
            )
        }

        // ⑦ 正向证据二：双方都能解析出题号且不同。
        val prevQuestion = previous.questionNumber ?: questionNumberOf(previous.text)
        val incQuestion = incoming.questionNumber ?: questionNumberOf(incoming.text)
        if (prevQuestion != null && incQuestion != null && prevQuestion != incQuestion) {
            return ScreenChangeDecision(
                ScreenChangeKind.NEW_QUESTION,
                "题号由第 $prevQuestion 题变为第 $incQuestion 题，判定为新的题目上下文"
            )
        }

        // ⑧ 缺少可靠的行级比对条件：不得凭文本相似度硬判。
        if (!incoming.comparable || prevKeys.isEmpty() || incKeys.isEmpty()) {
            return ScreenChangeDecision(
                ScreenChangeKind.UNSTABLE,
                "缺少可比对的结构化文字行，无法确定是同题滚动还是换了画面"
            )
        }

        // ⑨ 行关系一：新视口整体出现在既有行序列中 —— 回到已经采集过的视口。
        if (indexOfSubList(prevKeys, incKeys) >= 0) {
            return ScreenChangeDecision(
                ScreenChangeKind.REPEATED_VIEWPORT,
                "本次内容整体出现在既有内容中，判定为回到已采集视口"
            )
        }

        // ⑩ 行关系二：相邻边界存在连续重叠 —— 同题内容扩展。
        val overlap = longestSuffixPrefixOverlap(prevKeys, incKeys)
        if (overlap >= 1) {
            return ScreenChangeDecision(
                ScreenChangeKind.CONTENT_EXTENDED,
                "相邻边界连续重叠 $overlap 行，判定为同题内容扩展"
            )
        }

        // ⑪ 行关系三：既有内容的行按**原顺序**（不要求连续）全部出现在本次视口中 —— 同题内容扩展。
        //
        //    与 [ViewportMerger] 的同名规则**同一口径**（共用 [isOrderedSubsequenceOf] 与
        //    [MIN_ORDERED_CONTAINMENT_LINES]）：分类器判为 [ScreenChangeKind.CONTENT_EXTENDED] 的场景，
        //    合并器必定能安全完成合并，不再出现「分类说扩展、合并却 UNSTABLE」的规则错位
        //    （这正是「OCR 已识别出 400+ 字，文本池却一直停在 45 字」的直接成因）。
        //
        //    保守边界：只接受「原顺序整体出现」，不做任何模糊相似度。既有内容只要有一行在本次视口中
        //    找不到（被遮挡、跨题、或共用阅读材料导致歧义），就不满足条件，继续落回 ⑬ 的 UNSTABLE。
        // 逐规则结果（V11）：**与上面的分支条件完全同一表达式**，先算出布尔值再用于分支，
        // 使 ⑬ 的诊断日志原样记录「实际判定所用的计算结果」，而不是另算一套算法冒充结论。
        val ruleLinesOrdered = prevKeys.size >= MIN_ORDERED_CONTAINMENT_LINES &&
            isOrderedSubsequenceOf(prevKeys, incKeys)
        if (ruleLinesOrdered) {
            return ScreenChangeDecision(
                ScreenChangeKind.CONTENT_EXTENDED,
                "既有内容 ${prevKeys.size} 行按原顺序全部出现在本次视口中，判定为同题内容扩展"
            )
        }

        // ⑫ 兜底证据（字符级整体包含）：既有内容去空白后**整体**出现在本次视口中。
        //
        //    为什么需要它：无障碍节点树按控件换行（[fun.kirari.hanako.accessibility.HanakoAccessibilityService] 的
        //    traverseNodeText 逐节点追加并用 '\n' 分隔），而 OCR 按视觉行换行；同一段题干在两侧的行比对键
        //    因此对不上，使 ⑨/⑩/⑪ 全部落空 —— 这正是「OCR 已识别出 400+ 字，文本池却一直停在 45 字」的成因。
        //    此时若既有内容整体出现在本次视口里，说明它仍显示在同一屏上、本次视口是它的**超集**，
        //    属于可证明的同题证据，判定为同题内容扩展（真正的合并在 [ViewportMerger] 中完成）。
        //
        //    保守边界：只接受「整体包含」，不做任何模糊相似度；长度不足 [MIN_CONTAINMENT_CHARS] 不采纳；
        //    既非包含又不满足行级规则时，仍然落回 ⑬ 的 UNSTABLE，绝不强行合并。
        // 逐规则结果（V12/V12b）：与 ⑫/⑫b 的分支条件**完全同一表达式**，先算出布尔值再用于分支，
        // 供 ⑬ 诊断原样记录。若既有文本不足 [MIN_CONTAINMENT_CHARS]，这两条规则本就不被求值，
        // 保持 false（即「未成立」），与判定语义一致。
        var ruleContainedContiguously = false
        var ruleOrderedCharSubsequence = false
        if (prevSignature.length >= MIN_CONTAINMENT_CHARS) {
            ruleContainedContiguously = incSignature.contains(prevSignature)
            if (ruleContainedContiguously) {
                return ScreenChangeDecision(
                    ScreenChangeKind.CONTENT_EXTENDED,
                    "既有内容（${prevSignature.length} 字）整体出现在本次视口中，判定为同题内容扩展"
                )
            }

            // ⑫b 兜底证据（字符级**有序**子序列包含）：既有内容去空白后，每个**字**按原顺序出现在本次视口中。
            //
            //    为什么还需要它：文本池常先被一段**短的无障碍种子**（按控件换行，可能只有两三行）初始化，
            //    随后同一视口被 OCR 完整识别。两侧不仅**行**的切分不同，OCR 还会在既有文字中间插入碎片
            //    字词，使「行级有序子序列」（⑪）与「字符级整体连续包含」（⑫）**双双落空** —— 这正是
            //    「OCR 已识别出 400+ 字，文本池却一直停在 45 / 52 字」的直接成因。
            //    此时既有内容的相对顺序仍完整保留，是可证明的同题证据，判定为同题内容扩展
            //    （真正的合并在 [ViewportMerger] 的 4b 中完成）。
            //
            //    保守边界：只允许中间插入其它文字，**绝不**允许既有内容的字乱序或缺失；任一既有字
            //    找不到即落回 ⑬ 的 UNSTABLE，绝不强行合并。
            ruleOrderedCharSubsequence = isOrderedCharSubsequenceOf(prevSignature, incSignature)
            if (ruleOrderedCharSubsequence) {
                return ScreenChangeDecision(
                    ScreenChangeKind.CONTENT_EXTENDED,
                    "既有内容（${prevSignature.length} 字）按原顺序逐字出现在本次视口中，判定为同题内容扩展"
                )
            }
        }

        // ⑬ R8.6：既无法证明连续、也没有整体包含类证据。此处**不再**一律返回 UNSTABLE 并丢弃本次视口，
        //     而是按**同题证据**分级 —— 有证据则判为「同一道题的新片段」（[ScreenChangeKind.NEW_SEGMENT]），
        //     无证据则判为「关联未知」（[ScreenChangeKind.ASSOCIATION_UNKNOWN]）。
        //     两种情形都**不**与既有正文拼接（见 [ViewportMerger]），因此不会造成重复或错序：
        //     有证据的片段由调用方作为**独立片段**保留并允许注入（带分段标记）；无证据的片段只保留供核查、不注入。
        //
        //     同题证据（任一成立即可，全部是可核验的定量 / 正向判据，不做任何模糊相似度）：
        //     a) 去空白后的**最长连续公共字符数** ≥ [MIN_SEGMENT_SHARED_CHARS]：两侧仍共享一段真实材料；
        //     b) 双方都能解析出题号且**相同**：正向的同题信号（题号不同已在 ⑦ 判为新题）。
        val commonRun = longestCommonContiguousRun(prevSignature, incSignature)
        val sameQuestionNumber = prevQuestion != null && incQuestion != null && prevQuestion == incQuestion
        val sharedContiguousEnough = commonRun >= MIN_SEGMENT_SHARED_CHARS
        val sameQuestionEvidence = sharedContiguousEnough || sameQuestionNumber
        // 诊断带上：双方行数与去空白字数、**逐规则真实结果**（V11/V12/V12b，取自上面实际判定所用的布尔值）、
        // 同题证据判定、以及**脱敏**的行匹配摘要与首行指纹（只含计数 / 下标 / 布尔 / 短哈希，不含正文），
        // 便于真机定位「为什么没能合并」。分类结果即本分支选定的 kind，一并记入。
        val evidenceNote =
            "同题证据=${if (sameQuestionEvidence) "有" else "无"}" +
                "（共享连续字符 $commonRun ≥ $MIN_SEGMENT_SHARED_CHARS ? $sharedContiguousEnough；" +
                "题号相同=$sameQuestionNumber）"
        val diagnostic = "（既有 ${prevKeys.size} 行 / 本次 ${incKeys.size} 行，" +
            "去空白 ${prevSignature.length}/${incSignature.length} 字；" +
            "规则结果 V11=$ruleLinesOrdered V12=$ruleContainedContiguously " +
            "V12b=$ruleOrderedCharSubsequence；$evidenceNote；" +
            overlapDiagnostic(prevKeys, incKeys, prevSignature, incSignature, previous.hasCoordinates) +
            "；" +
            firstLineDiagnostic(previous.text, incoming.text, incSignature) +
            "）"
        return if (sameQuestionEvidence) {
            ScreenChangeDecision(
                ScreenChangeKind.NEW_SEGMENT,
                "无法证明与既有内容连续，但存在同题证据 —— 按**独立片段**保留（不拼接既有正文、也不丢弃）" +
                    evidenceNote + diagnostic + "；分类结果=NEW_SEGMENT"
            )
        } else {
            ScreenChangeDecision(
                ScreenChangeKind.ASSOCIATION_UNKNOWN,
                "没有可证明的同题重叠证据，无法确定是滚动还是换题" +
                    "（$evidenceNote），保留既有片段并把本次片段标为未确认（不进入最终请求）" +
                    diagnostic + "；分类结果=ASSOCIATION_UNKNOWN"
            )
        }
    }

    /**
     * ⑬ 的**脱敏**诊断摘要。
     *
     * 输出全部是计数 / 下标 / 布尔，**绝不**包含题目正文、选项全文或聊天内容：
     * - `既有行命中 a/b，首个未命中第 i 行`：判断是否只是某一行（顶栏 / 题号横幅 / 被遮挡行）不在 OCR 截图里；
     * - `最长连续公共段 n 字`：n 很小说明既有文字与本次文字**本身不同**（OCR 误识别 / 确实换题）；
     * - `既有来源`：区分既有内容是「无坐标的无障碍文本」还是「带坐标的 OCR 文本」。
     */
    private fun overlapDiagnostic(
        prevKeys: List<String>,
        incKeys: List<String>,
        prevSignature: String,
        incSignature: String,
        prevHasCoordinates: Boolean
    ): String {
        val incSet = incKeys.toHashSet()
        var matched = 0
        var firstUnmatched = -1
        for ((index, key) in prevKeys.withIndex()) {
            if (incSet.contains(key)) {
                matched++
            } else if (firstUnmatched < 0) {
                firstUnmatched = index
            }
        }
        val unmatchedNote = if (firstUnmatched < 0) "无未命中行" else "首个未命中第 ${firstUnmatched + 1} 行"
        val commonRun = longestCommonContiguousRun(prevSignature, incSignature)
        val origin = if (prevHasCoordinates) "带坐标文本" else "无坐标文本"
        return "既有行命中 $matched/${prevKeys.size}，$unmatchedNote；最长连续公共段 $commonRun 字；既有来源=$origin"
    }

    /**
     * 首行**脱敏指纹**诊断（仅用于 ⑬ 日志，**绝不**输出题干 / 选项原文）。
     *
     * 分别给出既有文本第一行与本次观察第一行的「去空白长度 / 字符类别 / 会话盐短哈希」，
     * 并给出既有首行的字符是否**全部在场**、是否**按原顺序在场**。
     * 任一无法可靠计算时显式记为 `unknown`，不做任何伪造或猜测。
     *
     * 用途：真机据此区分「第一行是动态非题目行（每次哈希都变）」「OCR 漏读（字符在场 partial/none）」
     * 与「文本顺序不一致（字符全部在场但有序=false）」。
     */
    private fun firstLineDiagnostic(
        previousText: String,
        incomingText: String,
        incSignature: String
    ): String {
        val prevRaw = ocrLines(previousText).firstOrNull { it.isNotBlank() }
        val incRaw = ocrLines(incomingText).firstOrNull { it.isNotBlank() }
        val prevKey = prevRaw?.let(::ocrLineKey)
        val incKey = incRaw?.let(::ocrLineKey)

        val present = when {
            prevKey.isNullOrEmpty() -> "unknown"
            prevKey.all { incSignature.contains(it) } -> "all"
            prevKey.any { incSignature.contains(it) } -> "partial"
            else -> "none"
        }
        val ordered = if (prevKey.isNullOrEmpty()) {
            "unknown"
        } else {
            isOrderedCharSubsequenceOf(prevKey, incSignature).toString()
        }
        return "既有首行=${lineFingerprint(prevRaw, prevKey)}；" +
            "本次首行=${lineFingerprint(incRaw, incKey)}；" +
            "既有首行字符在场=$present；既有首行有序=$ordered"
    }

    /** 单行脱敏指纹：`L{去空白长度}/C{类别位}/H{会话盐短哈希}`；无法计算时 `unknown`。 */
    private fun lineFingerprint(rawLine: String?, key: String?): String {
        if (rawLine == null || key.isNullOrEmpty()) return "unknown"
        return "L${key.length}/C${charClassFlags(rawLine)}/H${FingerprintHash.shortHash(key)}"
    }

    /**
     * 字符类别位（按**原始行**统计，故空白位有实际意义）：
     * D 数字 / L 拉丁字母 / H 汉字 / P 标点 / S 空白；不含则该位记为 `-`。
     */
    private fun charClassFlags(line: String): String {
        val hasDigit = line.any { it.isDigit() }
        val hasLatin = line.any { it in 'a'..'z' || it in 'A'..'Z' }
        val hasCjk = line.any { it.code in 0x4E00..0x9FFF }
        val hasPunct = line.any { !it.isLetterOrDigit() && !it.isWhitespace() }
        val hasSpace = line.any { it.isWhitespace() }
        return buildString {
            append(if (hasDigit) 'D' else '-')
            append(if (hasLatin) 'L' else '-')
            append(if (hasCjk) 'H' else '-')
            append(if (hasPunct) 'P' else '-')
            append(if (hasSpace) 'S' else '-')
        }
    }

    /** 两个字符串的最长**连续**公共子串长度（**仅用于诊断**，不做任何合并判定）。 */
    private fun longestCommonContiguousRun(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var best = 0
        var previous = IntArray(b.length + 1)
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1 else 0
                if (current[j] > best) best = current[j]
            }
            val swap = previous
            previous = current
            current = swap
        }
        return best
    }

    /** 去空白后的整体签名，用于「无实质变化」判定。 */
    private fun signatureOf(text: String): String = text.filter { !it.isWhitespace() }

    /**
     * R8.6：供**请求级注入判定**复用的「最长连续公共字符数」。
     *
     * 与 ⑬ 的同题证据**同一算法、同一口径**（阈值 [MIN_SEGMENT_SHARED_CHARS]）。
     * 为控制单次请求开销，两侧各只取**末尾** [maxChars] 个非空白字符参与比对 ——
     * 池的尾部正是最近累计的那一屏，也是最可能与当前视口发生重叠的位置。
     */
    internal fun sharedContiguousChars(
        poolText: String,
        viewportText: String,
        maxChars: Int = MAX_EVIDENCE_CHARS
    ): Int = longestCommonContiguousRun(
        signatureOf(poolText).takeLast(maxChars),
        signatureOf(viewportText).takeLast(maxChars)
    )

    /** 阅读顺序的行比对键（复用合并层的去空白口径，保证两处判定一致）。 */
    private fun comparableKeys(text: String): List<String> =
        ocrLines(text).map(::ocrLineKey).filter { it.isNotEmpty() }
}

// ============================== 题号解析 ==============================

/** 形如「第 11/15 题」。 */
private val QUESTION_RANGE_REGEX = Regex("第\\s*(\\d{1,3})\\s*[/／]\\s*\\d{1,3}\\s*题")

/** 形如「第 11 题」。 */
private val QUESTION_INDEX_REGEX = Regex("第\\s*(\\d{1,3})\\s*题")

/** 只有首个非空行且很短时才接受的横幅形态「11/15」。 */
private val BANNER_RATIO_REGEX = Regex("^(\\d{1,3})\\s*[/／]\\s*(\\d{1,3})$")

/** 短横幅的最大字符数（含空白），防止把题干里的分数误认成题号。 */
private const val BANNER_MAX_LENGTH = 14

/**
 * 从屏幕文本里尽量解析出「题号」（纯函数）。
 *
 * 解析顺序（从严到宽，宁可解析不到也不误判）：
 * 1. 全文优先匹配 `第 N/M 题`（题号取 N），常见于刷题 App 的顶栏或题干首行；
 * 2. 再匹配 `第 N 题`；
 * 3. 兜底：**仅当**首个非空行很短（≤ [BANNER_MAX_LENGTH] 字符）且形如 `N/M` 时才接受，
 *    避免把题干正文里的分数（如 `3/4`）当成题号。
 *
 * 解析不到返回 null —— 调用方必须把「题号未知」当作「无题号信号」，不得据此切题。
 */
internal fun questionNumberOf(text: String): Int? {
    val lines = ocrLines(text)
    for (line in lines) {
        QUESTION_RANGE_REGEX.find(line)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
    }
    for (line in lines) {
        QUESTION_INDEX_REGEX.find(line)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
    }
    val first = lines.firstOrNull()?.trim() ?: return null
    if (first.length <= BANNER_MAX_LENGTH) {
        BANNER_RATIO_REGEX.find(first)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
    }
    return null
}

// ============================== 诊断：会话盐短哈希 ==============================

/**
 * 诊断用的**会话盐短哈希**（仅用于同一运行会话内比较某行文本是否变化）。
 *
 * 安全边界：
 * - 盐在首次使用时由 [java.security.SecureRandom] 生成、**只驻内存、绝不写入日志**；
 * - 同一次会话内同一段文本得到同一结果，便于真机对比「首行是否每次都变」；
 * - 退出应用后盐丢失，历史日志**无法反查**原文；
 * - 只取 SHA-256 前 4 字节（8 位十六进制），且输入是**去空白后的行文本**。
 *
 * 因此日志中只出现短哈希与长度 / 类别，**不含**题干、选项或聊天正文。
 */
private object FingerprintHash {
    private const val HEX = "0123456789abcdef"

    private val salt: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

    /** 返回输入文本的 8 位十六进制短哈希（已混入会话盐）。 */
    fun shortHash(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(text.toByteArray(Charsets.UTF_8))
        }.digest()
        return buildString(8) {
            for (i in 0 until 4) {
                val value = digest[i].toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }
}
