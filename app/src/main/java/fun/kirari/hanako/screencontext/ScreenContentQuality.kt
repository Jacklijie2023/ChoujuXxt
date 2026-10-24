package `fun`.kirari.hanako.screencontext

/**
 * 一次**本次请求**识别结果的内容质量（纯函数判定，便于单元测试）。
 *
 * 真机证据（R8.4）：粉笔「专项智能练习（资料分析）」页无障碍只返回 36 字外壳
 * （`专项智能练习（资料分析）` / `10 / 15` / `材料` ×3），一行题干与选项都读不到。
 * 既有实现把这 36 字原样当作「当前题目」注入了模型请求（日志 `context-injected source=ACCESSIBILITY chars=36`），
 * 而累计池里更完整的 206 字又被 `POOL_UNSETTLED` 挡住 —— 模型实际什么题目都没看到。
 *
 * 因此本轮引入**请求级内容质量门控**：字符数不是唯一标准（真机 22 字的完整一题也合法），
 * 而是检查「是否只有页面标题 / 导航 / 重复材料外壳」「是否提取到实质题干或材料」「选择题是否读到选项」
 * 「识别结果是否属于当前已绑定目标应用」。
 */
internal enum class ScreenContentQuality {
    /** 有实质题目内容（题干 / 材料 / 选项任意一项可辨认）。 */
    SUFFICIENT,

    /** 只有页面标题、导航、题号进度或重复「材料」占位，没有任何实质题目内容。 */
    SHELL_ONLY,

    /**
     * 识别结果被**输入法软键盘按键**主导（真机证据：OCR 读到 `GHI JKL MNO` / `PQRS TUV WXYZ`
     * 这类手机键盘字母行），题目正文缺失。
     *
     * 与 [SHELL_ONLY] 并列但单独成类：它不是「页面外壳」，而是**遮挡窗口（输入法）被拍进截图**
     * 的证据。命中即阻断本次请求，绝不因为「字符串够长」就当成题目。
     */
    KEYBOARD_NOISE,

    /** 有实质内容但**一行选项都读不到**：值得补采，但**不**作为所有题型的绝对失败条件。 */
    NO_OPTIONS,

    /** 无法确认识别结果属于当前已绑定的目标应用。 */
    NOT_TARGET
}

/** 内容质量结论：[blocking] 为 true 表示不足以作为本次请求的当前题目上下文。 */
internal data class ScreenContentAssessment(
    val quality: ScreenContentQuality,
    val blocking: Boolean,
    val needsFreshCapture: Boolean,
    /** 非敏感原因码（进诊断日志，不含题干正文）。 */
    val reasonCode: String
)

/** 本次请求屏幕上下文的动作（纯判定结果，实际读取由调用方执行）。 */
internal enum class RequestScreenContextAction {
    /** 使用可被当前视口证明覆盖的累计池快照。 */
    USE_POOL,

    /** 使用本次即时读取的文本（无障碍或本地 OCR）。 */
    USE_LIVE,

    /** 本次即时读取不足：必须获取新鲜屏幕帧并做本地 OCR。 */
    FRESH_CAPTURE,

    /** 不足以构造当前题目上下文：明确报告失败，绝不伪装成已识别。 */
    BLOCKED
}

/** 一次请求的屏幕上下文选择结论。 */
internal data class RequestScreenContextDecision(
    val action: RequestScreenContextAction,
    val quality: ScreenContentQuality,
    val reasonCode: String,
    /** [RequestScreenContextAction.BLOCKED] 时的失败阶段码（用于面向用户的失败原因）。 */
    val blockedStage: String? = null
)

/** 判定「实质行」的最小长度：短于该长度的行不足以承载题干 / 材料 / 选项正文。 */
internal const val MIN_STEM_LINE_CHARS = 8

/**
 * 页面壳文字（标题 / 导航 / 题号进度 / 重复「材料」占位）的整行匹配。
 *
 * 只在这些词与括号、标点、空白**独立成行**时才算壳行；真正的题干 / 材料行通常包含
 * 其它汉字或数字，因此不会被误判。
 */
private val SHELL_CHROME_LINE_REGEX = Regex(
    "^(?:专项智能练习|材料|更多|收藏|分享|纠错|笔记|上一题|下一题|上一页|下一页|查看解析|答案解析|答题卡|全部|我的" +
        // R8.7：题面外的计时器（如 `剩余时间 01:19`）不是题目材料，且每次采集都会抖动一个字，
        // 若当成正文会被反复当作「新增内容」注入 —— 与标题 / 导航同属页面壳。
        "|剩余时间[\\s:：0-9.]*|倒计时[\\s:：0-9.]*" +
        "|[（(][^）)]*[）)]|[\\s·、，,。.：:/|\\-—～~!！?？])+$"
)

/**
 * 判定一行是否为「页面壳文字」。
 *
 * 两个判据：行本身太短（< [MIN_STEM_LINE_CHARS]），或整行只由壳词 / 括号 / 标点 / 空白构成
 * （如 `专项智能练习（资料分析）`）。空行同样视为壳。
 */
internal fun isShellChromeLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed.length < MIN_STEM_LINE_CHARS) return true
    return SHELL_CHROME_LINE_REGEX.matches(trimmed)
}

/** 选择题题干信号：出现这些措辞说明「当前题目至少是一道有选项的题」。 */
private val CHOICE_QUESTION_SIGNAL_REGEX = Regex(
    "下列|以下|正确的是|错误的是|不正确|说法|选项|哪(?:一|个|些)|以下哪|为多少|是多少|求|计算|约"
)

/** 手机九宫格键盘行：2~4 组、每组 3~4 个连续大写字母（如 `GHI JKL MNO`、`PQRS TUV WXYZ`）。 */
private val IME_KEYPAD_ROW_REGEX = Regex("^(?:[A-Z]{3,4}[\\s·|/]+){1,3}[A-Z]{3,4}$")

/** 全键盘字母行：整行是一个纯大写字母串（如 `QWERTYUIOP`、`ASDFGHJKL`、`ZXCVBNM`）。 */
private val IME_QWERTY_ROW_REGEX = Regex("^(?:QWERTYUIOP|ASDFGHJKL|ZXCVBNM)$")

/** 「键盘行」的总字母数下限：短于此长度不足以判定为键盘按键行。 */
private const val MIN_KEYBOARD_LINE_CHARS = 6

/** 判定为「键盘噪声主导」所需的键盘行数下限。 */
private const val MIN_KEYBOARD_LINES = 2

/** 中文（CJK 统一表意文字）字符数下限：低于此值即缺少题目正文所必需的中文内容。 */
internal const val MIN_CJK_CHARS_FOR_QUESTION = 6

/** 是否为中日韩统一表意文字（题目正文的实际载体）。 */
internal fun isCjkChar(ch: Char): Boolean =
    ch.code in 0x3400..0x9FFF || ch.code in 0xF900..0xFAFF

/**
 * 判定一行是否像**手机软键盘按键行**。
 *
 * 真机证据：OCR 结果里出现 `GHI JKL MNO`、`PQRS TUV WXYZ` —— 这是九宫格键盘字母行被拍进截图后
 * 被识别的产物。特征：整行无中文、无小写字母，只由 2~5 个连续大写字母分组构成。
 */
internal fun isImeKeyboardLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.length < MIN_KEYBOARD_LINE_CHARS) return false
    if (trimmed.any { isCjkChar(it) }) return false
    // 正常英文正文极少整行全大写；小写字母出现即排除键盘行。
    if (trimmed.any { it in 'a'..'z' }) return false
    if (IME_KEYPAD_ROW_REGEX.matches(trimmed)) return true
    if (IME_QWERTY_ROW_REGEX.matches(trimmed)) return true
    val groups = trimmed.split(Regex("[\\s·|/,]+")).filter { it.isNotEmpty() }
    return groups.size >= 3 && groups.all { group -> group.length in 1..5 && group.all { it in 'A'..'Z' } }
}

/**
 * 判定一次识别结果是否被**输入法键盘**主导（真机 R8.5 根因之一）。
 *
 * 判据（其一成立即可）：
 * 1. **正文行全部是键盘行** —— 去掉页面壳行（标题 / 导航 / 重复「材料」）之后，剩下的「正文」
 *    就是 `GHI JKL MNO` 这类键盘按键：真机 119 字 / 19 行的结果正是这种形态，此时**不能**
 *    因为「存在够长的行」就当成题干。
 * 2. 键盘行数达到 [MIN_KEYBOARD_LINES] 且整段几乎没有中文（[MIN_CJK_CHARS_FOR_QUESTION] 以下）——
 *    题目正文必然含中文，没有中文却有键盘行，只能是键盘被拍进了截图（对应截图时输入法未收起）。
 *
 * 这**不是**「字符数阈值」判定：键盘行本身长度足够，旧的「存在若干长行即放行」规则会把它当成题目。
 */
internal fun isImeKeyboardDominated(text: String): Boolean {
    val lines = ocrLines(text).map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return false
    val keyboardLines = lines.filter { isImeKeyboardLine(it) }
    if (keyboardLines.isEmpty()) return false
    val substantiveLines = lines.filter { !isShellChromeLine(it) }
    if (substantiveLines.isNotEmpty() && substantiveLines.all { isImeKeyboardLine(it) }) return true
    if (keyboardLines.size >= MIN_KEYBOARD_LINES && text.count { isCjkChar(it) } < MIN_CJK_CHARS_FOR_QUESTION) {
        return true
    }
    return false
}

/**
 * 评估一次识别结果能否作为**本次请求**的当前题目上下文。
 *
 * - [currentWindowPackage] 为本次识别所归属的窗口包名，[boundTargetPackage] 为已建立绑定的目标包名；
 *   两者存在而**不一致**（或归属包名为空）时判为 [ScreenContentQuality.NOT_TARGET] —— 身份未确认时
 *   既不得当作已确认的当前屏幕内容，也不得据此写共享累计池。
 * - 文本为空或**只有壳行且一行选项都读不到**时判为 [ScreenContentQuality.SHELL_ONLY]：
 *   这是真机 36 字外壳的精确判据（`材料` 重复行、`10 / 15` 题号进度行均为壳行）。
 * - 识别结果被输入法键盘按键主导（`GHI JKL MNO` 等，见 [isImeKeyboardDominated]）时判为
 *   [ScreenContentQuality.KEYBOARD_NOISE]：这是「遮挡窗口被拍进截图」的直接证据，一律阻断。
 * - 有实质行但读不到任何选项、且题干出现选择题信号时判为 [ScreenContentQuality.NO_OPTIONS]：
 *   值得补采一帧，但**不**阻断注入（选项缺失不能作为所有题型的绝对失败条件）。
 */
internal fun assessLiveScreenContent(
    text: String?,
    currentWindowPackage: String?,
    boundTargetPackage: String?
): ScreenContentAssessment {
    if (boundTargetPackage != null &&
        (currentWindowPackage == null || currentWindowPackage != boundTargetPackage)
    ) {
        return ScreenContentAssessment(
            quality = ScreenContentQuality.NOT_TARGET,
            blocking = true,
            needsFreshCapture = false,
            reasonCode = "identity_unconfirmed"
        )
    }
    val normalized = text?.trim().orEmpty()
    if (normalized.isEmpty()) {
        return ScreenContentAssessment(
            quality = ScreenContentQuality.SHELL_ONLY,
            blocking = true,
            needsFreshCapture = true,
            reasonCode = "blank"
        )
    }
    // 键盘噪声必须先于「存在长行」判定：键盘行本身长度足够，绝不能被当成题干正文放行。
    if (isImeKeyboardDominated(normalized)) {
        return ScreenContentAssessment(
            quality = ScreenContentQuality.KEYBOARD_NOISE,
            blocking = true,
            needsFreshCapture = true,
            reasonCode = "keyboard_noise"
        )
    }
    val substantiveLines = ocrLines(normalized).filter { !isShellChromeLine(it) }
    val optionMarkers = countOptionLikeLines(normalized)
    if (substantiveLines.isEmpty() && optionMarkers == 0) {
        return ScreenContentAssessment(
            quality = ScreenContentQuality.SHELL_ONLY,
            blocking = true,
            needsFreshCapture = true,
            reasonCode = "shell_only"
        )
    }
    if (optionMarkers == 0 && CHOICE_QUESTION_SIGNAL_REGEX.containsMatchIn(normalized)) {
        return ScreenContentAssessment(
            quality = ScreenContentQuality.NO_OPTIONS,
            blocking = false,
            needsFreshCapture = true,
            reasonCode = "no_options"
        )
    }
    return ScreenContentAssessment(
        quality = ScreenContentQuality.SUFFICIENT,
        blocking = false,
        needsFreshCapture = false,
        reasonCode = "ok"
    )
}

/**
 * 无障碍阶段的请求上下文选择（纯函数）。
 *
 * [poolSnapshotProvable] 为 true 表示累计池当前题**可被本次视口证明覆盖**（同题 + 同应用 + 覆盖证据，
 * 由 `decidePoolInjection` 判定），此时可直接用池快照；它**不等于**「历史池显示 COMPLETE 就无条件注入」。
 *
 * 顺序：
 * 1. 身份未确认 → [RequestScreenContextAction.BLOCKED]（连池也不注入）；
 * 2. 本帧只有壳文字 / 无实质内容 → 池可证明覆盖则用池，否则**强制新鲜截图 + 本地 OCR**；
 * 3. 本帧缺选项（选择题信号存在）→ 同上：池可证明覆盖则用池，否则补一帧；
 * 4. 本帧内容足够 → 使用本帧即时文本（池可覆盖时由调用方优先用池补齐同题累计材料）。
 *
 * 无障碍完全读不到文本（[liveText] 为空）时保持既有行为：直接走新鲜截图 + 本地 OCR，
 * 绝不回退到 TTL 旧缓存。
 */
internal fun decideRequestScreenContext(
    liveText: String?,
    currentWindowPackage: String?,
    boundTargetPackage: String?,
    poolSnapshotProvable: Boolean
): RequestScreenContextDecision {
    val assessment = assessLiveScreenContent(liveText, currentWindowPackage, boundTargetPackage)
    if (assessment.quality == ScreenContentQuality.NOT_TARGET) {
        return RequestScreenContextDecision(
            action = RequestScreenContextAction.BLOCKED,
            quality = assessment.quality,
            reasonCode = assessment.reasonCode,
            blockedStage = "target_identity_unconfirmed"
        )
    }
    if (liveText.isNullOrBlank()) {
        return RequestScreenContextDecision(
            action = RequestScreenContextAction.FRESH_CAPTURE,
            quality = assessment.quality,
            reasonCode = "a11y_blank"
        )
    }
    if (assessment.blocking || assessment.needsFreshCapture) {
        return RequestScreenContextDecision(
            action = if (poolSnapshotProvable) {
                RequestScreenContextAction.USE_POOL
            } else {
                RequestScreenContextAction.FRESH_CAPTURE
            },
            quality = assessment.quality,
            reasonCode = assessment.reasonCode
        )
    }
    return RequestScreenContextDecision(
        action = if (poolSnapshotProvable) {
            RequestScreenContextAction.USE_POOL
        } else {
            RequestScreenContextAction.USE_LIVE
        },
        quality = assessment.quality,
        reasonCode = assessment.reasonCode
    )
}

/**
 * 新鲜截图 + 本地 OCR 之后的请求上下文选择（纯函数）。
 *
 * 与 [decideRequestScreenContext] 的差异：本次不再有「再补一帧」这一选项 —— 已经用过新鲜帧了。
 * 因此内容仍不足（壳文字 / 身份未确认）时，只能「池可证明覆盖则用池」，否则
 * [RequestScreenContextAction.BLOCKED]（阶段码 `content_insufficient`），
 * 由调用方明确告知用户识别失败，而不是拿壳文字冒充题目。
 */
internal fun decideOcrContextAction(
    ocrText: String?,
    currentWindowPackage: String?,
    boundTargetPackage: String?,
    poolSnapshotProvable: Boolean
): RequestScreenContextDecision {
    val assessment = assessLiveScreenContent(ocrText, currentWindowPackage, boundTargetPackage)
    if (assessment.quality == ScreenContentQuality.NOT_TARGET) {
        return RequestScreenContextDecision(
            action = RequestScreenContextAction.BLOCKED,
            quality = assessment.quality,
            reasonCode = assessment.reasonCode,
            blockedStage = "target_identity_unconfirmed"
        )
    }
    if (assessment.blocking) {
        return RequestScreenContextDecision(
            action = if (poolSnapshotProvable) {
                RequestScreenContextAction.USE_POOL
            } else {
                RequestScreenContextAction.BLOCKED
            },
            quality = assessment.quality,
            reasonCode = assessment.reasonCode,
            blockedStage = if (poolSnapshotProvable) null else "content_insufficient"
        )
    }
    // 缺选项（NO_OPTIONS）不阻断：不得把选项缺失作为所有题型的绝对失败条件。
    return RequestScreenContextDecision(
        action = if (poolSnapshotProvable) {
            RequestScreenContextAction.USE_POOL
        } else {
            RequestScreenContextAction.USE_LIVE
        },
        quality = assessment.quality,
        reasonCode = assessment.reasonCode
    )
}
