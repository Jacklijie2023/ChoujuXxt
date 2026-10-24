package `fun`.kirari.hanako.screencontext

/**
 * 多视口文本合并层（纯 JVM，无 Android 依赖，不修改任何运行时状态）。
 *
 * 设计边界（对应真机约束）：
 * - **只比对相邻边界**：把「上一份内容的行序列」与「新视口的行序列」做
 *   「后缀 ↔ 前缀」的最长连续重叠匹配，只去重这段**可证明的**重叠，
 *   绝不对整个题目全文做无差别的全局重复句删除。边界没有连续重叠时，再接受几种同样可证明的
 *   包含关系：新视口整体出现在既有内容中（回到已采集视口）；既有内容的**行**按原顺序整体出现在新视口中；
 *   或（行切分完全对不上时的兜底）既有内容的每个**字**按原顺序出现在新视口中。
 * - **不做模糊猜测**：以上证据全部落空时返回 [MergeOutcome.UNSTABLE]，
 *   由调用方保持原内容不变，而不是强行拼接（错拼会同时造成重复与错序）。
 * - **绝不补写**：合并结果只由两侧已有的行按原顺序拼成，不生成任何 OCR 未识别的字词。
 * - **保留来源**：调用方（[ScreenContextPool]）负责保存每次采集的原始片段；
 *   本层只产出合并后的行序列。
 */

/**
 * 一次合并的结果。
 *
 * - [APPENDED]：既有内容无行可比对（或为空），新视口整体作为新增内容。
 * - [DUPLICATE]：新视口内容已完整包含在既有内容中（例如向上滚回已采集过的视口），不追加。
 * - [OVERLAP_MERGED]：在相邻边界找到连续重叠，去重后追加剩余内容。
 * - [NEW_QUESTION]：没有任何既有内容可比对（首次采集），直接建立新内容。
 * - [UNSTABLE]：无法证明重叠关系，**不得**替换既有内容。
 */
internal enum class MergeOutcome {
    APPENDED,
    DUPLICATE,
    OVERLAP_MERGED,
    NEW_QUESTION,
    UNSTABLE
}

/**
 * 合并的一方：**阅读顺序**的行序列。
 *
 * [lines] 是最终会注入模型的行文本（即 `OcrOrganization.text` 按行拆开的结果），
 * 因此选项标签与其正文的归属、续行的归属都已在单视口整理阶段确定，合并只做边界去重。
 */
internal data class MergeSide(
    val lines: List<String>
)

internal data class MergeResult(
    val outcome: MergeOutcome,
    /**
     * 合并后的行序列。
     *
     * [MergeOutcome.UNSTABLE] 时为 null，表示调用方**必须**保持既有内容不变。
     */
    val lines: List<String>?,
    /** 可读的判定原因（仅用于诊断，不含题目正文）。 */
    val note: String?
)

internal object ViewportMerger {

    /**
     * 合并「既有内容」与「新视口」。
     *
     * @param previous 既有内容；null 表示尚无任何内容（首次采集）。
     * @param incoming 新采集的视口行序列。
     */
    fun merge(previous: MergeSide?, incoming: MergeSide): MergeResult {
        val inc = meaningfulLines(incoming.lines)
        if (inc.isEmpty()) {
            return MergeResult(MergeOutcome.UNSTABLE, null, "新视口没有有效文字行，未合并")
        }
        if (previous == null) {
            return MergeResult(MergeOutcome.NEW_QUESTION, inc, "首次采集，建立新的题目内容")
        }
        val prev = meaningfulLines(previous.lines)
        if (prev.isEmpty()) {
            return MergeResult(MergeOutcome.APPENDED, inc, "既有内容为空，直接采纳本次视口")
        }

        val prevKeys = prev.map(::ocrLineKey)
        val incKeys = inc.map(::ocrLineKey)

        // 1. 相邻边界的最长连续重叠（既有内容的尾部 ↔ 新视口的头部）。
        val overlap = longestSuffixPrefixOverlap(prevKeys, incKeys)
        if (overlap > 0) {
            return if (overlap >= incKeys.size) {
                // 新视口完全落在既有内容的尾部重叠区里：没有任何新增内容。
                MergeResult(
                    MergeOutcome.DUPLICATE,
                    prev,
                    "新视口 $overlap 行全部落在已有内容中，未追加任何内容"
                )
            } else {
                // 只去掉新视口头部这段重复，其余按原顺序追加。
                MergeResult(
                    MergeOutcome.OVERLAP_MERGED,
                    prev + inc.drop(overlap),
                    "边界重叠 $overlap 行已去重，追加剩余 ${inc.size - overlap} 行"
                )
            }
        }

        // 2. 边界上没有连续重叠：若新视口整体出现在既有内容中间，
        //    说明是「滚回已经采集过的视口」，同样不应追加。
        val containedAt = indexOfSubList(prevKeys, incKeys)
        if (containedAt >= 0) {
            return MergeResult(
                MergeOutcome.DUPLICATE,
                prev,
                "新视口与已有内容第 ${containedAt + 1} 行起的片段一致，判定为回到已采集视口，未追加"
            )
        }

        // 3. 既有内容的行按**原顺序**（不要求连续）全部出现在新视口中 —— 有序子序列包含。
        //
        //    与 [ScreenChangeDetector] 的同类判定**同一口径**（共用 [isOrderedSubsequenceOf] 与
        //    [ScreenChangeDetector.MIN_ORDERED_CONTAINMENT_LINES]）：OCR 在既有行中间插入了一条碎片行、
        //    或把边界处的行切分得不同，都会**同时**打断「边界连续重叠」与「字符级整体包含」，
        //    但行序仍然可证明。此时新视口是既有内容的有序超集，采纳新视口即可：
        //    既有内容一行不丢、不重复，其相对阅读顺序也完全保留（新增内容按自身顺序插入原位）。
        //
        //    保守边界：不做任何模糊相似度；既有内容只要有一行在新视口中找不到，就不满足条件，
        //    继续落回下面的 UNSTABLE —— 绝不把「缺了一行的既有内容」强行拼进新视口造成丢字或错序。
        if (prevKeys.size >= ScreenChangeDetector.MIN_ORDERED_CONTAINMENT_LINES &&
            isOrderedSubsequenceOf(prevKeys, incKeys)
        ) {
            return MergeResult(
                MergeOutcome.OVERLAP_MERGED,
                inc,
                "既有内容（${prevKeys.size} 行）按原顺序全部出现在新视口中，以新视口为准替换既有内容"
            )
        }

        // 4. 行级关系全部落空：再退一步做**字符级整体包含**判定（与 [ScreenChangeDetector] 同一口径）。
        //    无障碍节点树按控件换行、OCR 按视觉行换行，同一段题干在两侧的行键往往对不上，
        //    使上面的行级规则全部落空。此时若既有内容的去空白全文**整体**出现在新视口中，
        //    说明新视口是既有内容的超集（既有内容仍在这一屏上，新视口还多了别的内容），
        //    可以安全地用新视口替换既有内容 —— 既有内容不会有任何丢失，也不会补写 OCR 未识别的字词。
        val prevSignature = prevKeys.joinToString("")
        val incSignature = incKeys.joinToString("")
        if (prevSignature.length >= ScreenChangeDetector.MIN_CONTAINMENT_CHARS) {
            if (incSignature.contains(prevSignature)) {
                return MergeResult(
                    MergeOutcome.OVERLAP_MERGED,
                    inc,
                    "既有内容（${prevSignature.length} 字）整体出现在新视口中，以新视口为准替换既有内容"
                )
            }

            // 4b. 既有内容的每个**字**按**原顺序**出现在新视口中 —— 字符级有序子序列包含。
            //
            //     与 [ScreenChangeDetector] 的 ⑫b 同一口径。典型的真机触发场景正是本轮要修的问题：
            //     文本池先被一段**短的无障碍种子**（按控件换行、甚至只有两三行）初始化，随后同一视口
            //     被 OCR 完整识别；两侧的行切分与视觉分组完全对不上，而且 OCR 还会在既有文字中间插入
            //     碎片字词 —— 于是「边界连续重叠」「行级有序子序列」「字符级整体连续包含」三条全部落空，
            //     池停在几十字上永不增长。
            //
            //     此时既有内容的每个字仍按原顺序全部在场，新视口是既有内容的**有序超集**，可以安全地
            //     以新视口为准替换既有内容：既有内容一字不丢、不重复、不补写，相对顺序也完全保留
            //     （新增内容按自身顺序插入原位）。
            //
            //     保守边界：只允许中间插入其它文字，**绝不**允许既有内容的字乱序或缺失；任一既有字
            //     找不到即返回 false，继续落回 UNSTABLE —— 不把「缺字/错序的既有内容」强行拼进新视口。
            if (isOrderedCharSubsequenceOf(prevSignature, incSignature)) {
                return MergeResult(
                    MergeOutcome.OVERLAP_MERGED,
                    inc,
                    "既有内容（${prevSignature.length} 字）按原顺序逐字出现在新视口中，以新视口为准替换既有内容"
                )
            }
        }

        return MergeResult(
            MergeOutcome.UNSTABLE,
            null,
            "与已有内容没有可证明的连续重叠，无法确定是继续滚动还是换了画面，未合并以免重复或错序"
        )
    }
}

// ============================== 行键与匹配工具 ==============================

/**
 * 行比对键：**去掉全部空白**后再比较。
 *
 * 中文题面里空白本身没有语义，而 OCR 在行内空格上波动很大（例如 `A.选项一` 与 `A. 选项一`），
 * 因此用「去空白」作为比较键能显著提高边界重叠匹配的稳定性；但**输出仍用原始行文本**，
 * 不会因此改写任何文字。
 */
internal fun ocrLineKey(line: String): String = line.filter { !it.isWhitespace() }

/** 取文本中所有非空行（保留原始行文本，不做任何改写）。 */
internal fun ocrLines(text: String): List<String> = text.lines().filter { it.isNotBlank() }

/** 取有意义（去掉比较键后仍非空）的行序列。 */
private fun meaningfulLines(lines: List<String>): List<String> =
    lines.filter { ocrLineKey(it).isNotEmpty() }

/**
 * 最长重叠长度 `k`：既有内容的**末尾 k 行**与新视口的**开头 k 行**逐行相等。
 *
 * 从大到小尝试，保证取到最长的那段。
 */
internal fun longestSuffixPrefixOverlap(prevKeys: List<String>, incKeys: List<String>): Int {
    val max = minOf(prevKeys.size, incKeys.size)
    for (k in max downTo 1) {
        var matched = true
        for (i in 0 until k) {
            if (prevKeys[prevKeys.size - k + i] != incKeys[i]) {
                matched = false
                break
            }
        }
        if (matched) return k
    }
    return 0
}

/**
 * [needle] 是否按**原顺序**（不要求连续）作为子序列出现在 [haystack] 中。
 *
 * 用途与安全边界：既有内容的每一行都按原顺序出现在新视口中，说明新视口是既有内容的**有序超集** ——
 * 既有内容仍显示在这一屏上，只是被 OCR 的行切分 / 碎片插入打散。因此可以用新视口替换既有内容：
 * 不丢字、不重复，且两行的相对阅读顺序保持不变。
 *
 * 只要有一行既有内容在新视口中找不到（被遮挡、跨题、或共用阅读材料导致歧义），即返回 false，
 * 保守地退回 UNSTABLE，绝不自行发明匹配规则强行拼接。
 */
internal fun isOrderedSubsequenceOf(needle: List<String>, haystack: List<String>): Boolean {
    if (needle.isEmpty()) return false
    var cursor = 0
    for (key in needle) {
        var matched = false
        while (cursor < haystack.size) {
            val candidate = haystack[cursor]
            cursor++
            if (candidate == key) {
                matched = true
                break
            }
        }
        if (!matched) return false
    }
    return true
}

/**
 * [needle] 是否按**原顺序**（不要求连续）作为**字符**子序列出现在 [haystack] 中。
 *
 * 用途与安全边界：既有内容的每一个字都按原顺序出现在新视口中，说明新视口是既有内容的**有序超集** ——
 * 既有内容仍显示在这一屏上，只是被 OCR 的字词插入 / 行切分打散（这会让 [isOrderedSubsequenceOf] 的
 * **行**级匹配全部落空）。因此可以用新视口替换既有内容：不丢字、不补写，相对阅读顺序也不变。
 *
 * 允许中间插入其它文字，但**不允许**既有内容的字乱序或缺失：任一既有字在新视口中找不到即返回 false，
 * 保守地退回 UNSTABLE，绝不自行发明匹配规则强行拼接。空 [needle] 一律视为不成立（不构成证据）。
 */
internal fun isOrderedCharSubsequenceOf(needle: String, haystack: String): Boolean {
    if (needle.isEmpty()) return false
    var cursor = 0
    for (char in needle) {
        var matched = false
        while (cursor < haystack.length) {
            val candidate = haystack[cursor]
            cursor++
            if (candidate == char) {
                matched = true
                break
            }
        }
        if (!matched) return false
    }
    return true
}

// ============================== R8.7 新增信息量（去重门控） ==============================

/**
 * R8.7：新视口「重复率」阈值。
 *
 * 含义：本次视口的行里，被**已保存内容**覆盖的比例达到该值，即认定它是**同一屏被重复识别**
 * （OCR 抖动使逐行比对部分落空，但整屏行构成几乎没有变化），不得作为新的独立片段保存。
 *
 * 取舍依据（真机日志 `hanako_debug_log_20261020_224509`）：同屏连拍 11 次，每次 26 行、
 * `commonRun` 120–173 字、`fragments` 卡在 12、`poolChars` 在 2160–2168 之间抖动 —— 整屏被反复追加。
 * 真正向下滚动到新内容时，新视口与既有内容的行重合通常远低于该值。
 *
 * 注意：这是**是否新增片段**的判定，不是对文本按百分比裁剪 —— 一旦判定有新增内容，
 * 保存的是**逐行去重后的新增行**，不会丢掉任何一行真正的新内容。
 */
internal const val ECHO_DUPLICATE_RATIO = 0.90

/** 参与「重复率」判定的最小行数：过少的行无法区分「同一屏抖动」与「真正的新内容」。 */
internal const val MIN_ECHO_LINES = 4

/** 判定「带来新增内容」所需的最小新增行数。 */
internal const val MIN_NEW_SEGMENT_LINES = 1

/** 判定「带来新增内容」所需的最小新增字符数（去空白后）。 */
internal const val MIN_NEW_SEGMENT_CHARS = 6

/** 行级包含判定的最小行字符数：防止极短行（如「A」）把无关内容判成重复。 */
internal const val MIN_LINE_CONTAINMENT_CHARS = 4

/**
 * R8.7：一个视口相对「已保存内容」的新增信息量（纯数据，可直接单测）。
 *
 * - [newLines]：本次视口中**未被已保存内容覆盖**的行（按原阅读顺序，保留原始行文本）；
 * - [coveredLineCount]：被覆盖的行数（完全相同 / 行级包含 / 仅相差一个字的 OCR 抖动）；
 * - [newChars]：新增行去空白后的字符数；
 * - [duplicateRatio]：**行覆盖重复率**（0..1），是「同一屏被重复识别」的主判据。
 */
internal data class ViewportNovelty(
    val incomingLineCount: Int,
    val coveredLineCount: Int,
    val newLines: List<String>,
    val newChars: Int
) {
    /** 被已保存内容覆盖的行占比（0..1）；视口为空时视为完全重复。 */
    val duplicateRatio: Double
        get() = if (incomingLineCount == 0) 1.0 else coveredLineCount.toDouble() / incomingLineCount

    /** 已保存内容几乎覆盖了整个视口 → 同一屏的重复识别，不得重复保存。 */
    val isEcho: Boolean
        get() = incomingLineCount >= MIN_ECHO_LINES && duplicateRatio >= ECHO_DUPLICATE_RATIO

    /** 一行新增都没有。 */
    val isDuplicate: Boolean get() = newLines.isEmpty()

    /**
     * 新增行**全部**是页面壳文字（倒计时 / 标题 / 导航 / 题号进度）。
     *
     * 这类行不是题目材料，每次采集都会抖动（`剩余时间 01:20` → `01:19`），
     * 若当成「新增内容」保存，就会把界面噪声按分段标记注入模型请求。
     */
    val newLinesAreChromeOnly: Boolean
        get() = newLines.isNotEmpty() && newLines.all { isShellChromeLine(it) }

    /** 新增量足以作为「独立同题片段」保存（且必须包含真正的题目材料行）。 */
    val isSubstantive: Boolean
        get() = newLines.size >= MIN_NEW_SEGMENT_LINES &&
            newChars >= MIN_NEW_SEGMENT_CHARS &&
            !newLinesAreChromeOnly
}

/**
 * 计算 [incomingLines] 相对 [existingLines] 的新增信息量。
 *
 * 只做**可证明的**比较（完全相同 / 行级包含 / 相差一个字的抖动），不做相似度猜测；
 * 无法证明被覆盖的行一律计为新增行 —— 宁可多保存一行，也不把真正的新内容当成重复丢掉。
 *
 * [existingLines] 必须是**全部已保存内容**（连续正文 + 各独立片段），否则跨片段重复无法发现。
 */
internal fun measureViewportNovelty(
    existingLines: List<String>,
    incomingLines: List<String>
): ViewportNovelty {
    val incoming = meaningfulLines(incomingLines)
    if (incoming.isEmpty()) return ViewportNovelty(0, 0, emptyList(), 0)
    val existingKeys = existingLines.map(::ocrLineKey).filter { it.isNotEmpty() }
    if (existingKeys.isEmpty()) {
        val chars = incoming.sumOf { ocrLineKey(it).length }
        return ViewportNovelty(incoming.size, 0, incoming, chars)
    }
    val existingSet = existingKeys.toHashSet()
    val newLines = ArrayList<String>()
    val newKeys = HashSet<String>()
    var covered = 0
    for (line in incoming) {
        val key = ocrLineKey(line)
        if (key.isEmpty()) continue
        if (isLineAlreadySaved(key, existingSet)) {
            covered++
        } else if (newKeys.add(key)) {
            newLines.add(line)
        }
    }
    val newChars = newLines.sumOf { ocrLineKey(it).length }
    return ViewportNovelty(
        incomingLineCount = incoming.size,
        coveredLineCount = covered,
        newLines = newLines,
        newChars = newChars
    )
}

/** 该行是否已被已保存内容覆盖（完全相同 / 行级包含 / 只差一个字的 OCR 抖动）。 */
private fun isLineAlreadySaved(key: String, existing: Set<String>): Boolean {
    if (key in existing) return true
    if (key.length < MIN_LINE_CONTAINMENT_CHARS) return false
    return existing.any { candidate ->
        (candidate.length >= MIN_LINE_CONTAINMENT_CHARS && candidate.contains(key)) ||
            (key.length >= MIN_LINE_CONTAINMENT_CHARS && key.contains(candidate)) ||
            isNearDuplicateLine(key, candidate)
    }
}

/**
 * 两行是否只相差一个字符（OCR 抖动的典型形态：错认一个字 / 多一个标点）。
 *
 * 只接受长度差 ≤ 1 的编辑距离 ≤ 1，且要求首字或末字相同作为廉价预筛 ——
 * 预筛漏判只会让该行被当成「新增行」，由 [ViewportNovelty.duplicateRatio] 兜底；
 * 绝不因此把真正的新内容判成重复。
 */
internal fun isNearDuplicateLine(a: String, b: String, maxDistance: Int = 1): Boolean {
    if (a == b) return true
    if (a.isEmpty() || b.isEmpty()) return false
    if (kotlin.math.abs(a.length - b.length) > maxDistance) return false
    if (a.first() != b.first() && a.last() != b.last()) return false
    return editDistanceWithin(a, b, maxDistance)
}

/** 编辑距离是否 ≤ [limit]（超出即提前返回 false，避免长行的完整 DP）。 */
private fun editDistanceWithin(a: String, b: String, limit: Int): Boolean {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        var rowMin = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            if (current[j] < rowMin) rowMin = current[j]
        }
        if (rowMin > limit) return false
        previous = current
    }
    return previous[b.length] <= limit
}

/** 在 [haystack] 中查找 [needle] 作为**连续子序列**首次出现的下标；找不到返回 -1。 */
internal fun indexOfSubList(haystack: List<String>, needle: List<String>): Int {
    if (needle.isEmpty() || needle.size > haystack.size) return -1
    outer@ for (start in 0..haystack.size - needle.size) {
        for (i in needle.indices) {
            if (haystack[start + i] != needle[i]) continue@outer
        }
        return start
    }
    return -1
}
