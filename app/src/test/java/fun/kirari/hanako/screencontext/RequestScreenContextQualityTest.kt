package `fun`.kirari.hanako.screencontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8.4「本次请求」内容质量门控的纯逻辑单元测试（不联网、不依赖 Android 运行时）。
 *
 * 覆盖真机事实：粉笔「专项智能练习（资料分析）」页无障碍只返回 36 字外壳
 * （标题 / 题号进度 / 重复「材料」），一行题干与选项都读不到。
 * 既有实现把这段外壳直接当作当前题目注入模型请求（`context-injected source=ACCESSIBILITY chars=36`），
 * 因此本轮引入请求级门控：只看**本次**识别结果是否够用，而不是「累计池非空 / 曾经读到过选项」。
 */
class RequestScreenContextQualityTest {

    private val bound = "com.fenbi.android.servant"
    private val other = "com.other.app"

    /** 真机 36 字外壳（标题 / 题号进度 / 重复「材料」，无题干无选项）。 */
    private val fenbiShell = listOf(
        "专项智能练习（资料分析）",
        "10 / 15",
        "材料",
        "材料",
        "材料"
    ).joinToString("\n")

    /** OCR / 无障碍读到的一屏完整资料分析题（题干 + 四个选项）。 */
    private val fullQuestion = listOf(
        "专项智能练习（资料分析）",
        "材料一：2023年上半年，全国规模以上工业企业实现利润总额33884亿元，同比下降16.8%。",
        "问题：2023年上半年全国规模以上工业企业实现利润总额同比约减少多少亿元？",
        "A 约5600亿元",
        "B 约6800亿元",
        "C 约7200亿元",
        "D 约8400亿元"
    ).joinToString("\n")

    /** 本身简短但已含题干 + 选项的读屏结果（去空白仅 22 字，却是完整一题）。 */
    private val shortCompleteQuestion = listOf(
        "第 1/10 题",
        "下列关于光合作用的说法，正确的是",
        "A 选项甲",
        "B 选项乙"
    ).joinToString("\n")

    /** 有实质题干、出现选择题措辞，但**一行选项都读不到**。 */
    private val stemOnlyChoiceQuestion = listOf(
        "材料二：某市 2020 年新业态营业收入 1250 亿元。",
        "下列说法正确的是"
    ).joinToString("\n")

    // ==================== 1. 壳文字判定（req 1 / 10） ====================

    @Test
    fun fenbiShellTextIsShellOnlyAndNotSufficient() {
        // 场景 10：只有「专项智能练习（资料分析）」「材料」等壳文字时，必须报告内容不足，
        // 不得当作有效题目。
        val assessment = assessLiveScreenContent(fenbiShell, bound, bound)
        assertEquals(ScreenContentQuality.SHELL_ONLY, assessment.quality)
        assertTrue("外壳文字不足以作为当前题目上下文", assessment.blocking)
        assertTrue("外壳文字必须触发新鲜截图识别", assessment.needsFreshCapture)
        assertEquals("shell_only", assessment.reasonCode)
    }

    @Test
    fun shellChromeLineClassifierOnlyFlagsChrome() {
        assertTrue("标题行（含括号）应判为壳行", isShellChromeLine("专项智能练习（资料分析）"))
        assertTrue("重复材料占位应判为壳行", isShellChromeLine("材料"))
        assertTrue("题号进度行过短应判为壳行", isShellChromeLine("10 / 15"))
        assertTrue("空行应判为壳行", isShellChromeLine("   "))
        assertFalse("题干行不得判为壳行", isShellChromeLine("下列关于光合作用的说法，正确的是"))
        assertFalse("选项行不得判为壳行", isShellChromeLine("A 约5600亿元"))
        assertFalse(
            "含实际内容的材料行不得判为壳行",
            isShellChromeLine("材料一：2023年上半年，全国规模以上工业企业实现利润总额33884亿元")
        )
    }

    // ==================== 2. 请求级选择：壳文字必须触发新鲜识别（req 1 / 2） ====================

    @Test
    fun shellTextWithNoPoolProofForcesFreshCapture() {
        // 场景 1 / 10：无障碍只返回页面标题 + 重复「材料」→ 必须走新鲜截图 + 本地 OCR，
        // 不得把外壳文字当作当前题目注入。
        val decision = decideRequestScreenContext(
            liveText = fenbiShell,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals(RequestScreenContextAction.FRESH_CAPTURE, decision.action)
        assertEquals(ScreenContentQuality.SHELL_ONLY, decision.quality)
        assertEquals("shell_only", decision.reasonCode)
        assertNull("新鲜识别可继续，尚不构成被阻止", decision.blockedStage)
    }

    @Test
    fun blankAccessibilityForcesFreshCaptureNotStalePool() {
        // 无障碍完全读不到文本：直接走新鲜截图 + 本地 OCR，绝不回退 TTL 旧缓存。
        val decision = decideRequestScreenContentNull()
        assertEquals(RequestScreenContextAction.FRESH_CAPTURE, decision.action)
        assertEquals("a11y_blank", decision.reasonCode)
    }

    private fun decideRequestScreenContentNull(): RequestScreenContextDecision =
        decideRequestScreenContext(
            liveText = null,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )

    @Test
    fun shellTextUsesPoolOnlyWhenPoolCanProveCoverage() {
        // 场景 3 的反面：本帧只有外壳，但累计池**可被本次视口证明覆盖**（同题 + 同应用 + 覆盖证据），
        // 此时才允许用池快照，否则一律补一帧。
        val decision = decideRequestScreenContext(
            liveText = fenbiShell,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = true
        )
        assertEquals(RequestScreenContextAction.USE_POOL, decision.action)
    }

    // ==================== 3. 完整题目 / 缺选项（req 3 / 11） ====================

    @Test
    fun completeShortQuestionIsSufficientAndUsesLive() {
        // 场景 11：完整题目识别成功（短但含题干 + 选项）→ 判 SUFFICIENT，用本帧即时文本，
        // 不需要也无意义重复识别。
        val assessment = assessLiveScreenContent(shortCompleteQuestion, bound, bound)
        assertEquals(ScreenContentQuality.SUFFICIENT, assessment.quality)
        assertFalse(assessment.blocking)
        assertFalse("完整题目不需要补采", assessment.needsFreshCapture)

        val decision = decideRequestScreenContext(
            liveText = shortCompleteQuestion,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals(RequestScreenContextAction.USE_LIVE, decision.action)
    }

    @Test
    fun fullOcrQuestionIsSufficientAndUsesLiveEvenWithoutPoolWrite() {
        // 场景 4：新鲜 OCR 成功（完整题目），但**无法安全写入累计池**（poolSnapshotProvable=false）时，
        // 仍应作为本次请求的临时上下文使用 —— 池写入与请求可用性不得混淆。
        val decision = decideOcrContextAction(
            ocrText = fullQuestion,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals(RequestScreenContextAction.USE_LIVE, decision.action)
        assertEquals(ScreenContentQuality.SUFFICIENT, decision.quality)
    }

    @Test
    fun missingOptionsFlaggedForFreshCaptureButNeverBlocking() {
        // 选项缺失**不**作为所有题型的绝对失败条件：有实质题干 + 选择题措辞但无选项时，
        // 记 NO_OPTIONS 并值得补采，但 blocking=false；OCR 后仍可作本次请求上下文。
        val assessment = assessLiveScreenContent(stemOnlyChoiceQuestion, bound, bound)
        assertEquals(ScreenContentQuality.NO_OPTIONS, assessment.quality)
        assertFalse("缺选项不得作为绝对失败条件", assessment.blocking)
        assertTrue("缺选项时值得补一帧", assessment.needsFreshCapture)

        val a11yDecision = decideRequestScreenContext(
            liveText = stemOnlyChoiceQuestion,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals("缺选项且池不可证明覆盖 → 补一帧", RequestScreenContextAction.FRESH_CAPTURE, a11yDecision.action)

        val ocrDecision = decideOcrContextAction(
            ocrText = stemOnlyChoiceQuestion,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals("已用新鲜帧仍缺选项 → 不阻断，仍用作本次上下文", RequestScreenContextAction.USE_LIVE, ocrDecision.action)
    }

    // ==================== 4. 身份归属门控（req 5） ====================

    @Test
    fun unconfirmedTargetIdentityBlocksInsteadOfReusingContent() {
        // 场景 5：本次识别归属包名与绑定目标不一致（或归属包名为空）→ 判 NOT_TARGET 并 BLOCKED，
        // 既不得当作已确认的当前屏幕内容，也不得据其写共享池。
        val mismatch = assessLiveScreenContent(fullQuestion, other, bound)
        assertEquals(ScreenContentQuality.NOT_TARGET, mismatch.quality)
        assertTrue(mismatch.blocking)

        val decision = decideRequestScreenContext(
            liveText = fullQuestion,
            currentWindowPackage = other,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals(RequestScreenContextAction.BLOCKED, decision.action)
        assertEquals("target_identity_unconfirmed", decision.blockedStage)

        val nullWindow = decideRequestScreenContext(
            liveText = fullQuestion,
            currentWindowPackage = null,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals(RequestScreenContextAction.BLOCKED, nullWindow.action)
    }

    @Test
    fun unconfirmedIdentityBlocksEvenWithProvablePool() {
        // 身份未确认时连池也不得注入（不得强行用旧内容冒充当前屏幕）。
        val decision = decideRequestScreenContext(
            liveText = fenbiShell,
            currentWindowPackage = other,
            boundTargetPackage = bound,
            poolSnapshotProvable = true
        )
        assertEquals(RequestScreenContextAction.BLOCKED, decision.action)
    }

    // ==================== 5. OCR 后的不足判定（req 7） ====================

    @Test
    fun ocrShellContentWithoutPoolProofReportsContentInsufficient() {
        // 场景 7：新鲜截图 OCR 之后仍只有壳文字（无法确认题目内容）→ 明确报告识别失败
        // （阶段码 content_insufficient），绝不拿壳文字冒充已识别。
        val decision = decideOcrContextAction(
            ocrText = fenbiShell,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals(RequestScreenContextAction.BLOCKED, decision.action)
        assertEquals("content_insufficient", decision.blockedStage)
    }

    @Test
    fun ocrShellContentFallsBackToPoolOnlyWhenProvable() {
        val decision = decideOcrContextAction(
            ocrText = fenbiShell,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = true
        )
        assertEquals(RequestScreenContextAction.USE_POOL, decision.action)
        assertNull(decision.blockedStage)
    }

    @Test
    fun ocrBlankTextIsAlsoContentInsufficient() {
        val decision = decideOcrContextAction(
            ocrText = "   ",
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals(RequestScreenContextAction.BLOCKED, decision.action)
        assertEquals("content_insufficient", decision.blockedStage)
    }

    // ==================== 6. R8.5 输入法键盘噪声门控（req 3） ====================

    /**
     * 真机 R8.5 证据：截图把**输入法软键盘**拍进去了，OCR 读到 `GHI JKL MNO` / `PQRS TUV WXYZ`
     * 这类手机键盘字母行（截图 1080×2460、`masked=true`、OCR 仅 128~132 字 / 33~35 行、`options=0`）。
     * 这些行长度足够（≥8 字），旧规则「存在若干长行即放行」会把它们当成题干注入模型。
     */
    private val imeKeyboardNoise = listOf(
        "专项智能练习（资料分析）",
        "10 / 15",
        "GHI JKL MNO",
        "PQRS TUV WXYZ",
        "材料",
        "材料"
    ).joinToString("\n")

    @Test
    fun imeKeyboardLineClassifierFlagsKeypadRowsOnly() {
        assertTrue("九宫格键盘行必须被识别", isImeKeyboardLine("GHI JKL MNO"))
        assertTrue("九宫格键盘行必须被识别", isImeKeyboardLine("PQRS TUV WXYZ"))
        assertTrue("全键盘字母行必须被识别", isImeKeyboardLine("QWERTYUIOP"))
        assertFalse("中文题干行不得被识别为键盘行", isImeKeyboardLine("下列关于光合作用的说法，正确的是"))
        assertFalse("含小写的英文正文不得被识别为键盘行", isImeKeyboardLine("The quick brown fox"))
        assertFalse("选项行不得被识别为键盘行", isImeKeyboardLine("A 约5600亿元"))
    }

    @Test
    fun keyboardNoiseDominatedOcrIsBlockedNotSufficient() {
        // req 3：键盘字符占主导、正文缺失 → 质量门控拒绝当成有效题目，
        // 且必须触发补采（有意义的行全部是键盘行）。
        assertTrue("该样本必须被判为键盘噪声主导", isImeKeyboardDominated(imeKeyboardNoise))

        val assessment = assessLiveScreenContent(imeKeyboardNoise, bound, bound)
        assertEquals(ScreenContentQuality.KEYBOARD_NOISE, assessment.quality)
        assertTrue("键盘噪声不足以作为当前题目上下文", assessment.blocking)
        assertTrue("键盘噪声必须触发新鲜截图识别", assessment.needsFreshCapture)
        assertEquals("keyboard_noise", assessment.reasonCode)

        // req 9：识别失败时不得偷偷发出只含标题 / 键盘 / 旧题的请求 → 池不可证明覆盖时直接 BLOCKED。
        val decision = decideOcrContextAction(
            ocrText = imeKeyboardNoise,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals(RequestScreenContextAction.BLOCKED, decision.action)
        assertEquals("content_insufficient", decision.blockedStage)
    }

    @Test
    fun keyboardNoiseInAccessibilityPathForcesFreshCapture() {
        // 无障碍路径同样命中键盘噪声时（屏幕上有键盘），必须转新鲜识别而不是直接注入。
        val decision = decideRequestScreenContext(
            liveText = imeKeyboardNoise,
            currentWindowPackage = bound,
            boundTargetPackage = bound,
            poolSnapshotProvable = false
        )
        assertEquals(RequestScreenContextAction.FRESH_CAPTURE, decision.action)
        assertEquals(ScreenContentQuality.KEYBOARD_NOISE, decision.quality)
    }

    @Test
    fun realChineseQuestionWithLatinMaterialIsNotFlaggedAsKeyboardNoise() {
        // 反向保护：含英文 / 数字 / 缩写词行的资料分析材料不得被误判为键盘噪声
        // （键盘判据只在「正文行全是键盘行」或「几乎无中文且有键盘行」时成立）。
        val materialWithLatin = listOf(
            "专项智能练习（资料分析）",
            "GDP CPI PPI",
            "材料三：2023 年某公司 GDR 发行规模为 12.5 亿美元，同比增速回落。",
            "问题：该公司 2023 年 GDR 发行规模同比增速比 2022 年低多少个百分点？",
            "A 1.2",
            "B 2.4"
        ).joinToString("\n")
        assertFalse(isImeKeyboardDominated(materialWithLatin))
        val assessment = assessLiveScreenContent(materialWithLatin, bound, bound)
        assertFalse("含中文材料的真实题目不得被当成键盘噪声", assessment.blocking)
    }
}
