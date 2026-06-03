package `fun`.kirari.hanako.overlay

import `fun`.kirari.hanako.data.AssistantPreset
import `fun`.kirari.hanako.debug.AppDebugLogStore

/**
 * 智能场景识别器
 * 根据屏幕内容自动判断题型并推荐最佳处理策略
 */
object SmartSceneDetector {
    private const val TAG = "SmartScene"

    // 结果缓存，避免重复处理相同内容
    private val resultCache = LinkedHashMap<String, CachedResult>(50, 0.75f, true)

    data class CachedResult(
        val text: String,
        val answer: String,
        val timestamp: Long
    )

    data class SceneAnalysis(
        val detectedType: QuestionType,
        val confidence: Float,
        val suggestedAssistant: String?,
        val suggestedRoute: RouteSuggestion
    )

    enum class QuestionType {
        MULTIPLE_CHOICE,    // 选择题
        FILL_BLANK,         // 填空题
        SHORT_ANSWER,       // 简答题
        TRUE_FALSE,         // 判断题
        MATH,               // 数学题
        ESSAY,              // 论述题
        UNKNOWN             // 未知
    }

    enum class RouteSuggestion {
        OCR_THEN_LLM,      // 建议 OCR + 文本模型
        MULTIMODAL_DIRECT,  // 建议多模态直接处理
        ACCESSIBILITY_TEXT  // 建议无障碍提取
    }

    /**
     * 分析文本内容，识别题型
     */
    fun analyzeText(text: String): SceneAnalysis {
        val normalized = text.trim()

        // 选择题检测
        if (isMultipleChoice(normalized)) {
            return SceneAnalysis(
                detectedType = QuestionType.MULTIPLE_CHOICE,
                confidence = 0.9f,
                suggestedAssistant = findAssistantByKeyword(normalized, listOf("选择", "题目")),
                suggestedRoute = RouteSuggestion.OCR_THEN_LLM
            )
        }

        // 判断题检测
        if (isTrueFalse(normalized)) {
            return SceneAnalysis(
                detectedType = QuestionType.TRUE_FALSE,
                confidence = 0.85f,
                suggestedAssistant = null,
                suggestedRoute = RouteSuggestion.OCR_THEN_LLM
            )
        }

        // 填空题检测
        if (isFillBlank(normalized)) {
            return SceneAnalysis(
                detectedType = QuestionType.FILL_BLANK,
                confidence = 0.8f,
                suggestedAssistant = null,
                suggestedRoute = RouteSuggestion.OCR_THEN_LLM
            )
        }

        // 数学题检测
        if (isMathProblem(normalized)) {
            return SceneAnalysis(
                detectedType = QuestionType.MATH,
                confidence = 0.75f,
                suggestedAssistant = findAssistantByKeyword(normalized, listOf("数学", "计算")),
                suggestedRoute = RouteSuggestion.MULTIMODAL_DIRECT
            )
        }

        // 简答题检测
        if (isShortAnswer(normalized)) {
            return SceneAnalysis(
                detectedType = QuestionType.SHORT_ANSWER,
                confidence = 0.7f,
                suggestedAssistant = null,
                suggestedRoute = RouteSuggestion.OCR_THEN_LLM
            )
        }

        return SceneAnalysis(
            detectedType = QuestionType.UNKNOWN,
            confidence = 0.5f,
            suggestedAssistant = null,
            suggestedRoute = RouteSuggestion.OCR_THEN_LLM
        )
    }

    /**
     * 检查是否有缓存的结果
     */
    fun getCachedResult(text: String): CachedResult? {
        val key = text.take(200).hashCode().toString()
        val cached = resultCache[key]
        if (cached != null) {
            // 缓存 10 分钟有效
            if (System.currentTimeMillis() - cached.timestamp < 600_000) {
                AppDebugLogStore.d(TAG, "Cache hit for key=$key")
                return cached
            } else {
                resultCache.remove(key)
            }
        }
        return null
    }

    /**
     * 缓存处理结果
     */
    fun cacheResult(text: String, answer: String) {
        val key = text.take(200).hashCode().toString()
        resultCache[key] = CachedResult(text, answer, System.currentTimeMillis())
        // 限制缓存大小
        if (resultCache.size > 50) {
            val oldest = resultCache.keys.first()
            resultCache.remove(oldest)
        }
    }

    /**
     * 清空缓存
     */
    fun clearCache() {
        resultCache.clear()
    }

    private fun isMultipleChoice(text: String): Boolean {
        // 检测 A. B. C. D. 或 A、B、C、D、格式
        val patterns = listOf(
            Regex("[A-D][.、．]"),
            Regex("[A-D]\\s"),
            Regex("选项\\s*[A-D]"),
            Regex("[（(][A-D][)）]")
        )
        val matchCount = patterns.count { pattern ->
            pattern.findAll(text).count() >= 2
        }
        return matchCount > 0 || (text.contains("A") && text.contains("B") && text.contains("C"))
    }

    private fun isTrueFalse(text: String): Boolean {
        return text.contains("对错") || text.contains("正确") && text.contains("错误") ||
                text.contains("√") || text.contains("×") ||
                text.contains("对") && text.contains("错") && text.length < 500
    }

    private fun isFillBlank(text: String): Boolean {
        return text.contains("____") || text.contains("（  ）") || text.contains("(  )") ||
                text.contains("填空") || text.contains("填写")
    }

    private fun isMathProblem(text: String): Boolean {
        val mathSymbols = listOf("+", "-", "×", "÷", "=", "∫", "∑", "√", "π", "∞", "≤", "≥", "≠")
        val mathKeywords = listOf("计算", "求解", "方程", "函数", "极限", "导数", "积分", "矩阵")
        val symbolCount = mathSymbols.count { text.contains(it) }
        val keywordCount = mathKeywords.count { text.contains(it) }
        return symbolCount >= 2 || keywordCount >= 1
    }

    private fun isShortAnswer(text: String): Boolean {
        val keywords = listOf("简述", "说明", "解释", "分析", "论述", "比较", "描述", "为什么", "怎么样")
        return keywords.any { text.contains(it) }
    }

    private fun findAssistantByKeyword(text: String, keywords: List<String>): String? {
        // 这里只是返回关键字，实际匹配需要在 ViewModel 中进行
        return keywords.firstOrNull { text.contains(it) }
    }
}
