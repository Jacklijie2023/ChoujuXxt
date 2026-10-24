package `fun`.kirari.hanako.screencontext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕理解抽象与采纳门控的纯逻辑单元测试。
 *
 * 覆盖：视觉模型输出必须通过「目标身份 + 当前前台 + 会话 epoch + 时间戳」验证才能采纳；
 * 任一不满足即拒绝（不得因为视觉模型返回文本就写池）。
 * 本轮不接入任何视觉模型；接口仅为后续预留。
 */
class ScreenUnderstandingProviderTest {

    private val bound = "com.example.exam"

    private fun observation(
        kind: ScreenUnderstandingKind = ScreenUnderstandingKind.LOCAL_VLM,
        windowKey: String? = bound,
        capturedAtMillis: Long = 1_000L,
        questionText: String = "下列说法正确的是",
        options: List<String> = listOf("A. 甲", "B. 乙"),
        figureDescription: String? = null,
        confidence: Float? = 0.9f,
        missingFields: List<String> = emptyList()
    ) = ScreenUnderstandingObservation(kind, windowKey, capturedAtMillis, questionText, options, figureDescription, confidence, missingFields)

    private fun adopt(
        obs: ScreenUnderstandingObservation?,
        boundPackage: String? = bound,
        currentWindowPackage: String? = bound,
        sessionEpochMatches: Boolean = true,
        nowMillis: Long = 1_500L,
        maxAgeMillis: Long = 10_000L
    ) = canAdoptObservation(obs, boundPackage, currentWindowPackage, sessionEpochMatches, nowMillis, maxAgeMillis)

    @Test
    fun structuredObservationCarriesVlmFields() {
        val obs = observation(figureDescription = "折线图：随浓度上升", confidence = 0.42f, missingFields = listOf("optionD"))
        assertTrue(obs.options.isNotEmpty())
        assertTrue(obs.figureDescription != null)
        assertTrue(obs.confidence != null)
        assertTrue(obs.missingFields.contains("optionD"))
    }

    @Test
    fun validSameSessionObservationIsAdopted() {
        assertTrue(adopt(observation()))
    }

    @Test
    fun nullObservationIsRejected() {
        assertFalse(adopt(null))
    }

    @Test
    fun blankQuestionTextIsRejected() {
        assertFalse(adopt(observation(questionText = "   ")))
    }

    @Test
    fun unboundTargetRejectsObservation() {
        assertFalse("未绑定目标时任何来源（含视觉模型）都不得采纳", adopt(observation(), boundPackage = null))
        assertFalse(adopt(observation(), boundPackage = "   "))
    }

    @Test
    fun observationFromOtherAppIsRejected() {
        assertFalse(adopt(observation(windowKey = "com.other.app")))
    }

    @Test
    fun nullWindowKeyIsRejected() {
        assertFalse(adopt(observation(windowKey = null)))
    }

    @Test
    fun currentForegroundNotTargetRejectsObservation() {
        assertFalse("用户已切走时不得采纳观察", adopt(observation(), currentWindowPackage = "com.other.app"))
        assertFalse(adopt(observation(), currentWindowPackage = null))
    }

    @Test
    fun staleSessionEpochRejectsObservation() {
        assertFalse("会话边界已切换时，旧 epoch 的观察不得采纳", adopt(observation(), sessionEpochMatches = false))
    }

    @Test
    fun staleOrFutureTimestampRejectsObservation() {
        assertFalse("观察过旧（超过 maxAge）不得采纳", adopt(observation(capturedAtMillis = 1_000L), nowMillis = 100_000L, maxAgeMillis = 10_000L))
        assertFalse("时间戳无效不得采纳", adopt(observation(capturedAtMillis = 0L)))
        assertFalse("时间戳位于未来不得采纳", adopt(observation(capturedAtMillis = 5_000L), nowMillis = 1_000L))
    }
}
