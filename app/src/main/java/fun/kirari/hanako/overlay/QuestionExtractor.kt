package `fun`.kirari.hanako.overlay

/** 从 OCR 原文中还原出的一个选项。 */
internal data class ExtractedOption(val label: String, val text: String)

/**
 * 从 OCR 原文中还原出的题目：材料 / 题干 / 选项。
 *
 * 资料分析等题型有材料；言语理解、判断推理等通常只有题干和选项。
 */
internal data class ExtractedQuestion(
    val material: List<String>,
    val stem: String,
    val options: List<ExtractedOption>
) {
    /** 至少还原出题干或选项时才值得展示。 */
    val hasContent: Boolean get() = stem.isNotBlank() || options.isNotEmpty()
}

/**
 * 把 OCR 原文「过滤」成题干与选项，供结果页「完整材料和题目」区域按刷题 App 的布局展示。
 *
 * 只做文本整理，不调用模型：识别选项标记（A./B./C./D.，含全角写法）、
 * 找到题干起点，剩下的内容按顺序作为材料。OCR 噪声行（题号、进度、按钮、模块名）会被丢弃。
 */
internal object QuestionExtractor {
    /** 选项标记：A. / B、 / C． / D) 等写法。 */
    private val optionMarker = Regex("""([A-D])\s*[.．、,，:：)）]\s*""")

    /** 刷题 App 的界面元素与模块名，不属于题目内容。 */
    private val chromePatterns = listOf(
        Regex("""^\d+$"""),
        Regex("""^\d+\s*/\s*\d+$"""),
        Regex("""^\d{1,2}:\d{2}(:\d{2})?$"""),
        Regex("""^剩余时间.*"""),
        Regex("""^第?\s*\d+\s*题.*$"""),
        Regex("""^(言语理解与表达|言语理解|资料分析|判断推理|数量关系|常识判断|定义判断|图形推理|类比推理|逻辑判断|逻辑填空|片段阅读|数字推理|数学运算|选词填空|语句表达|常识应用|综合分析|套题|模考|历年真题|模拟考试)$"""),
        Regex("""^(收藏|纠错|笔记|解析|视频解析|答题卡|上一题|下一题|提交|查看答案|继续|退出|分享|举报|夜间模式|字体设置|答题报告)$"""),
        Regex("""^(正确率|错误率|本题得分|作答用时|已答|未答)\s*.*$"""),
        Regex("""^[-—_=·•\s]+$""")
    )

    fun extract(ocrText: String): ExtractedQuestion {
        val lines = dedupe(
            ocrText.lines()
                .map { normalize(it.trim()) }
                .filter { it.isNotBlank() && !isChrome(it) }
        )
        if (lines.isEmpty()) return ExtractedQuestion(emptyList(), "", emptyList())

        val firstOption = lines.indexOfFirst { parseOptions(it).isNotEmpty() }
        if (firstOption < 0) {
            // 没有识别到选项：整段作为题干（材料可能在上方，但无法可靠切分）。
            return ExtractedQuestion(emptyList(), lines.joinToString("\n"), emptyList())
        }

        val head = lines.subList(0, firstOption)
        val options = mutableListOf<ExtractedOption>()
        for (line in lines.subList(firstOption, lines.size)) {
            for (option in parseOptions(line)) {
                val existing = options.indexOfFirst { it.label == option.label }
                when {
                    existing < 0 -> options += option
                    // 长截图在屏幕交界处会把同一个选项截断成两半：保留更完整的那一份。
                    option.text.length > options[existing].text.length ->
                        options[existing] = option
                }
            }
        }

        val boundary = findStemBoundary(head)
        val material = head.subList(0, boundary + 1)
            .filter { it.length >= 4 }
        val stem = head.subList(boundary + 1, head.size).joinToString("\n").trim()
        return ExtractedQuestion(material, stem, options)
    }

    /** 全角字母/数字归一，避免 `Ａ．` 这类 OCR 写法漏判。 */
    private fun normalize(line: String): String {
        val sb = StringBuilder(line.length)
        for (ch in line) {
            sb.append(
                when (ch) {
                    in 'Ａ'..'Ｚ' -> 'A' + (ch - 'Ａ')
                    in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                    in '０'..'９' -> '0' + (ch - '０')
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    private fun isChrome(line: String): Boolean = chromePatterns.any { it.matches(line) }

    /**
     * 去掉长截图 OCR 里由页面重叠产生的重复行。
     *
     * 拼接处会把同一段文字识别两次，且两次之间常有空格/标点差异，
     * 因此按「去标点后的相似度」判断，只保留第一次出现的版本。
     */
    private fun dedupe(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (line in lines) {
            val previous = result.lastOrNull()
            if (previous != null && isSameContent(previous, line)) continue
            result += line
        }
        return result
    }

    private fun isSameContent(a: String, b: String): Boolean {
        val sa = signature(a)
        val sb = signature(b)
        if (sa.isEmpty() || sb.isEmpty()) return sa == sb
        if (sa == sb) return true
        val shortest = minOf(sa.length, sb.length)
        val longest = maxOf(sa.length, sb.length)
        // 太短的行（选项编号、数字）不做模糊比较，避免误删真正不同的内容。
        if (shortest < 6) return false
        if (shortest.toFloat() / longest < 0.75f) return false
        return similarity(sa, sb) >= 0.9f
    }

    /** 去掉空格与标点后的文本指纹，只保留文字和数字。 */
    private fun signature(line: String): String = line.filter { it.isLetterOrDigit() }

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        return 1f - editDistance(a, b).toFloat() / maxOf(a.length, b.length)
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * 该行包含的选项（支持一行多个选项）。
     *
     * 只接受从 A 开始、按 A→B→C→D 连续出现的标记：正文里偶然出现的「A.」不会被打断成选项。
     */
    private fun parseOptions(line: String): List<ExtractedOption> {
        val matches = optionMarker.findAll(line)
            .filter { match ->
                val start = match.range.first
                start == 0 || line[start - 1].isWhitespace() || line[start - 1] == '　'
            }
            .toList()
        if (matches.isEmpty()) return emptyList()

        val result = mutableListOf<ExtractedOption>()
        // 每行独立判断起点：OCR 常常把选项拆成多行，单行里只有 B/C/D 也是正常的。
        var expected = matches.first().groupValues[1].first()
        for ((index, match) in matches.withIndex()) {
            val label = match.groupValues[1].first()
            if (label != expected) continue
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: line.length
            val text = line.substring(textStart, textEnd).trim().trimEnd('，', ',', '；', ';')
            if (text.isBlank()) break
            result += ExtractedOption(expected.toString(), text)
            expected = when (expected) {
                'A' -> 'B'
                'B' -> 'C'
                'C' -> 'D'
                else -> return result
            }
        }
        return result
    }

    /**
     * 题干在 [head] 中的起始位置（返回其前一行的下标，-1 表示题干从第一行开始）。
     *
     * 优先用句号切分：材料最后一句通常以「。」结束，其后就是题干。
     * 材料也可能以冒号收尾（如「……如下：」），但最后一行一定是题干，不能当边界。
     * 没有此类标点时，跳过开头的短标题行（模块名/题号），其余全部算题干。
     */
    private fun findStemBoundary(head: List<String>): Int {
        if (head.isEmpty()) return -1
        for (i in head.indices.reversed()) {
            if (head[i].endsWith("。")) return i
        }
        for (i in (0 until head.size - 1).reversed()) {
            val line = head[i]
            if (line.endsWith("：") || line.endsWith(":")) return i
        }
        var i = -1
        while (i + 1 < head.size &&
            head[i + 1].length <= 8 &&
            !head[i + 1].contains("（") &&
            !head[i + 1].contains("(")
        ) {
            i++
        }
        return i
    }
}
