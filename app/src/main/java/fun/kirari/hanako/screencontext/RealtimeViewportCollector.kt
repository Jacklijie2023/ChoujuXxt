package `fun`.kirari.hanako.screencontext

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次持续采集的**来源**。
 *
 * - [ACCESSIBILITY]：无障碍节点树文本（主路径，无坐标，不触发截图）。
 * - [LOCAL_OCR]：单次截图 + 本地 OCR（仅在「确有必要」时按频率限制触发一次）。
 */
internal enum class RealtimeCollectSource {
    ACCESSIBILITY,
    LOCAL_OCR
}

/**
 * 一次采集提交的结果。
 *
 * [applied] 明确区分「本次是否真正作用到了文本池」：被实时模式门控拒绝、被判为乱序迟到、
 * 或本次没有读到有效文字时为 false —— 调用方不得把未生效的提交当作池已更新。
 */
internal data class RealtimeCollectionOutcome(
    val result: PoolUpdateResult,
    val applied: Boolean,
    val source: RealtimeCollectSource?,
    /** 可读原因（仅用于诊断，**不含**题目正文）。 */
    val note: String?
) {
    /**
     * 文本池内容是否**真正发生变化**。
     *
     * 必须与 [applied] 严格区分：[applied] 只表示「本次提交被接受」，
     * 而 [PoolUpdateResult.NO_CHANGE] / [PoolUpdateResult.UNSTABLE] / [PoolUpdateResult.DUPLICATE]
     * 同样会被接受（[applied] 为 true），但文本池内容**没有任何变化** ——
     * 调用方**不得**仅凭 [applied] 推断池已更新。
     */
    val poolContentChanged: Boolean get() = applied && result.contentChanged
}

/**
 * 本次池更新结果是否**真正改变了文本池内容**（与「提交是否被接受」无关）。
 *
 * 只有 [PoolUpdateResult.NEW_QUESTION] / [PoolUpdateResult.APPENDED] /
 * [PoolUpdateResult.OVERLAP_MERGED] 会改变池内容；[PoolUpdateResult.NO_CHANGE] /
 * [PoolUpdateResult.DUPLICATE] / [PoolUpdateResult.UNSTABLE] / [PoolUpdateResult.OCR_FAILED]
 * 一律保持既有池内容不变（UNSTABLE / OCR_FAILED 尤其如此）。
 */
internal val PoolUpdateResult.contentChanged: Boolean
    get() = this == PoolUpdateResult.NEW_QUESTION ||
        this == PoolUpdateResult.APPENDED ||
        this == PoolUpdateResult.OVERLAP_MERGED ||
        // R8.6：独立同题片段同样改变了池的**可注入内容**，因此推进 acceptedSequence，
        // 使在途的旧 OCR 帧过期，避免迟到结果污染新的题目会话。
        this == PoolUpdateResult.NEW_SEGMENT

/**
 * 持续采集的只读诊断状态（供既有诊断页展示）。
 *
 * 注意：[poolText] 是**当前文本池**的合并内容，与「某次请求注入模型的文本」不是一回事，
 * 诊断页必须把它们分开展示，避免把「当前池」误读成「上次请求用的文本」。
 */
internal data class RealtimeCollectionStatus(
    val enabled: Boolean,
    val ocrAvailable: Boolean,
    val degradedNote: String?,
    /** 最近一次**内容真正发生变化**的更新时间（无内容变化时为 null）。 */
    val lastUpdateAtMillis: Long?,
    /** 最近一次被接受的观察时间（含「无变化 / 不确定」）。 */
    val lastObservedAtMillis: Long?,
    /**
     * 最近一次「采集提交」的时间（**含**未读到文本 / 未确认目标窗口的提交）。
     *
     * 与 [lastObservedAtMillis] 明确区分：后者只有「真正进入合并、被接受」的观察才更新，
     * 而本字段每次提交都更新。真机上「累计采集提交次数 103、最近一次采集观察 -」这类
     * 看似矛盾的状态，正是因为 103 次提交全部因「读不到文本 / 未确认窗口」而未生效。
     */
    val lastSubmissionAtMillis: Long?,
    val lastResult: PoolUpdateResult?,
    val lastSource: RealtimeCollectSource?,
    val lastNote: String?,
    /** 最近一次观察携带的滚动信号判定（区分「文本未变但有有效滚动信号」与「重复上报」）。 */
    val lastScrollDecision: RealtimeScrollDecision,
    /** 最近一次**已被接受**的新滚动位置键；无可靠位置信息时为 null（不虚构位置）。 */
    val lastScrollPositionKey: String?,
    /** 最近一次收到的无障碍事件类型（诊断用；从未收到事件时为 null）。 */
    val lastEventKind: RealtimeEventKind?,
    /**
     * 最近一次事件来源包名是否与目标窗口一致。
     * `false` 表示本应用悬浮窗或其它窗口（其滚动**不得**当作刷题页面滚动，已被忽略）。
     */
    val lastEventFromTargetWindow: Boolean?,
    /**
     * 最近一次无障碍**文本读取**的结果状态（区分「读到文本」「目标窗口有效但视口无文本」
     * 「连目标窗口都没取到」）。null 表示本次会话尚未收到任何无障碍事件。
     *
     * 这是诊断页区分「当前视口没有读到文本」与「后台采集没有运行」的关键字段。
     */
    val lastTextReadStatus: RealtimeTextReadStatus?,
    /** 最近一次「值得 OCR 但被拦截」的原因（频率限制 / 单飞 / 能力降级），无则为 null。 */
    val lastOcrGateNote: String?,
    /** 最近一次 OCR 的**结果状态**（成功 / 失败），与上面的「触发 / 门控原因」分开记录。 */
    val lastOcrStatusNote: String?,
    val lastOcrAtMillis: Long?,
    val ocrInFlight: Boolean,
    /**
     * 采集器的观察序号与「已被接受的最大序号」（只读诊断）。
     *
     * [acceptedSequence] **只在真正改变池内容**的观察之后推进；OCR 结果若小于它，
     * 说明在途期间池内容已被更新，会被判为过期丢弃。两者对照即可区分
     * 「提交被接受（但内容未变）」与「因过期被丢弃」。
     */
    val sequence: Long,
    val acceptedSequence: Long,
    /**
     * 累计「采集提交次数」。**语义**：每次无障碍观察 / OCR 结果提交均计数（含未读到文本、
     * 未确认目标窗口、乱序丢弃等未生效的提交），**不代表成功写入文本池的次数**。
     * 与 [lastSubmissionAtMillis] 配对使用，保证「次数」与「最近一次提交时间」语义一致。
     */
    val updateCount: Int,
    val questionKey: String?,
    val questionNumber: Int?,
    val fragmentCount: Int,
    /** 目标应用最近一次滚动事件时间；用于决定「画面变化探测回退」是否需要激活。 */
    val lastTargetScrollAtMillis: Long?,
    /** 画面变化探测的采样间隔（毫秒）。 */
    val probeIntervalMillis: Long,
    /** 画面变化探测要求的最小「无目标滚动事件」时长（毫秒）。 */
    val probeScrollSilenceMillis: Long,
    val lastProbeAtMillis: Long?,
    val lastProbeDecision: ScreenProbeDecision?,
    /**
     * 当前文本池的完整度。没有任何题目上下文时按 [QuestionCompleteness.UNKNOWN] 报告，
     * 避免诊断页出现「未知的未知」这种无法解释的状态。
     */
    val completeness: QuestionCompleteness,
    val poolText: String
) {
    val poolLines: List<String> get() = ocrLines(poolText)
}

/**
 * 一次无障碍**滚动事件**携带的位置元数据（由调用方在事件回调内同帧复制，纯数据）。
 *
 * 为什么需要它：无障碍节点树文本可能不随滚动刷新（刷题 App 的题目常被固定容器承载），
 * 此时「用户确实滚动了」无法从文本变化推断；只有把 Android 已经提供的滚动位置带进采集器，
 * 才能把「真实滚动但文本未变化」与「同一画面重复上报」区分开。
 *
 * 位置是否「可靠」的判定见 [hasReliablePosition]；**不可靠时必须安全降级，绝不虚构滚动状态**。
 */
internal data class RealtimeScrollSignal(
    val fromIndex: Int,
    val toIndex: Int,
    val itemCount: Int,
    val scrollY: Int,
    val maxScrollY: Int
) {
    /**
     * 是否提供了**可靠**的滚动位置信息。
     * - 列表型滚动：`fromIndex` / `toIndex` 均有效（≥ 0）；
     * - 像素型滚动：`maxScrollY > 0`（可据此区分「位置未变」与「已滚动」）。
     *
     * 两者都不满足时**不得**据此推断滚动状态（可能只是重复分发或位置未知），
     * 按 [RealtimeScrollDecision.UNKNOWN_LOCATION] 安全降级处理。
     */
    val hasReliablePosition: Boolean
        get() = (fromIndex >= 0 && toIndex >= 0) || maxScrollY > 0

    /** 滚动位置键；仅在 [hasReliablePosition] 为真时非 null，否则为 null（不虚构位置）。 */
    val positionKey: String?
        get() = when {
            fromIndex >= 0 && toIndex >= 0 -> "items:$fromIndex-$toIndex/$itemCount"
            maxScrollY > 0 -> "px:$scrollY/$maxScrollY"
            else -> null
        }
}

/**
 * 一次观察携带的滚动信号判定结果（供门控与只读诊断使用）。
 */
internal enum class RealtimeScrollDecision {
    /** 本次观察不是滚动事件（如窗口/内容/文本变化）。 */
    NOT_SCROLL,

    /**
     * 是滚动事件，但**未提供可靠位置**：视为「**滚动已发生、精确位置未知**」。
     *
     * 这种情况仍允许按 [RealtimeViewportCollector.DEFAULT_MIN_OCR_INTERVAL_MILLIS] 间隔 +
     * 单飞补一次受控 OCR —— 真机上刷题 App 常常只分发 `TYPE_VIEW_SCROLLED` 而不带
     * `scrollY` / `maxScrollY` / `fromIndex` / `toIndex`，若一律禁止截图，会让整个自动采集
     * 静默失效；但**绝不虚构滚动位置**（诊断里的位置键保持 null），也**不**与
     * [NOT_SCROLL]（无法确认发生过滚动）混为一谈。
     */
    UNKNOWN_LOCATION,

    /** 位置与上次已接受的滚动位置相同：视为重复分发 / 滚动位置未变。 */
    REPEAT,

    /** 出现了**新的滚动位置**：可作为「无障碍文本无法证明视口已更新」时的补充证据。 */
    ADVANCED
}

/**
 * 一次无障碍事件的**原始类型**（由服务在事件回调内同帧记录，纯数据）。
 *
 * 用途：把「确为目标应用的滚动事件（`TYPE_VIEW_SCROLLED`）」与「内容 / 文本 / 窗口变化」
 * 在采集器内明确区分开，并作为只读诊断「最近一次收到的无障碍事件类型」的输入。
 */
internal enum class RealtimeEventKind {
    WINDOW_STATE_CHANGED,
    WINDOW_CONTENT_CHANGED,
    VIEW_TEXT_CHANGED,
    VIEW_SCROLLED,
    UNKNOWN
}

/**
 * 最近一次无障碍**文本读取**的结果状态（纯枚举，供诊断与门控使用）。
 *
 * 存在的理由：真机上「后台采集没有产出」有四种完全不同的原因，不能都显示成「未获取到屏幕内容」：
 * - [SUCCESS]：读到了非空节点树文本；
 * - [EMPTY]：**目标窗口有效**（包名/类型均已确认），但本次视口没有任何可读文本
 *   （题目被 Canvas / WebView / 自绘控件承载是常见情形）—— 这**不等于**采集没运行；
 * - [FAILED]：连有效的第三方目标窗口都没取到（服务未连接 / 当前只有本应用悬浮窗 / 系统栏）；
 * - null：本次会话尚未收到任何无障碍事件。
 */
internal enum class RealtimeTextReadStatus {
    SUCCESS,
    EMPTY,
    FAILED
}

/**
 * 行首「选项标记」判定（无障碍读屏专用，比 OCR 侧的标点判据更宽松）。
 *
 * 无障碍节点树按控件逐行拼接，选项常写成「A 叶绿体是光合作用的场所」「（A）…」；
 * OCR 侧常见的「A．…」形式之外，还必须接受「字母 + 空格」这种形式，
 * 否则会把一段真实含有选项的读屏结果误判成「没有选项」。
 *
 * 匹配：行首为 A–D（可带全/半角括号），其后紧跟 `. 、 ． , ， : ： ) ）` 或空白或行尾。
 */
internal val ACCESSIBILITY_OPTION_LINE_REGEX =
    Regex("^[（(]?[A-Da-d](?:[.、．,，:：)）]|\\s|$)")

/** 文本中「像选项行」的行数（行首为 A–D，可带括号与标点 / 空白）。 */
internal fun countOptionLikeLines(text: String): Int =
    ocrLines(text).count { ACCESSIBILITY_OPTION_LINE_REGEX.containsMatchIn(it.trim()) }

/**
 * 判定一次**无障碍读到的文本**是否「明显不完整」。
 *
 * 真机证据（R8.2）：粉笔「专项智能练习（资料分析）」页无障碍只返回 28 字
 * （标题 / 题号 / 材料占位：`专项智能练习（资料分析）` `10 / 15` `材料` ×3），
 * 节点树读不到题干与选项。此时文本非空（[RealtimeTextReadStatus.SUCCESS]），
 * 既有门控会把「文本池已由文本更新」当成「无需补 OCR」，导致题目正文永远采集不到。
 *
 * 判据：**一行选项标记都读不到**（选择题必然含 A/B/C/D 选项）。这比单纯的字符数阈值更稳健：
 * 既能放过「本身简短但已含题干 + 选项」的读屏结果（如「第 3/10 题 + 题干 + A 选项甲」，
 * 去空白后仅 22 字却已是完整一题），也能识别「文本看似不短但只有外壳」的页面。
 */
internal fun accessibilityTextLacksOptionLines(text: String): Boolean {
    if (text.isBlank()) return false
    return countOptionLikeLines(text) == 0
}

/**
 * 受控「画面变化探测回退」（修复 B）的一份降采样样本（纯数据，便于单测）。
 *
 * [cells] 为按行优先排列的**亮度**网格（0..255），由调用方把一次截图降采样后给出；
 * 采集器只做数值比较，**不接触** Bitmap / 截图 API，因此本文件保持纯 JVM。
 */
internal data class ScreenSample(val cells: List<Int>)

/** 一次画面探测采样的判定结果（只读诊断用）。 */
internal enum class ScreenProbeDecision {
    /** 首次采样：仅建立基线，没有可比对的历史样本，不触发 OCR。 */
    FIRST_SAMPLE,

    /** 与上一份样本的差异未达到实质变化阈值：跳过 OCR。 */
    UNCHANGED,

    /** 画面发生实质变化：进入既有的 OCR 单飞 + 频率限制流程。 */
    CHANGED
}

/**
 * 画面样本的轻量变化判定（纯函数）。
 *
 * 规则：逐格比较亮度差，**超过 [CELL_DELTA] 的格子占比**达到 [CHANGED_CELL_RATIO] 才判为
 * 「实质变化」。阈值刻意宽松：系统时钟、过渡动画、悬浮窗状态这类只占据屏幕极小面积的变化
 * 不会累计到足够的格子占比，因此不会无限制触发 OCR。
 */
internal object ScreenSampleComparison {
    /** 单个格子判为「变化」的最小亮度差。 */
    const val CELL_DELTA = 24

    /** 需要判为「实质变化」的最小变化格子占比。 */
    const val CHANGED_CELL_RATIO = 0.12

    fun substantiallyChanged(previous: List<Int>, current: List<Int>): Boolean {
        if (previous.isEmpty() || current.isEmpty()) return previous != current
        if (previous.size != current.size) return true
        var changed = 0
        for (index in previous.indices) {
            if (kotlin.math.abs(previous[index] - current[index]) >= CELL_DELTA) changed++
        }
        return changed.toDouble() / previous.size >= CHANGED_CELL_RATIO
    }
}

/**
 * 实时「持续采集」接入层（纯 JVM，无 Android / 截图 / 无障碍 / UI 依赖，可直接单测）。
 *
 * 职责：把「无障碍事件 / 单次 OCR 结果」这类外部观察，按既定规则喂给 [ScreenContextPool]，
 * 并在**不触碰发送请求路径**的前提下维护采集门控、频率限制、单飞与乱序丢弃。
 *
 * 边界（务必保持）：
 * 1. **只更新本地文本池**：本类不持有任何聊天 / 网络 / 模型依赖，结构上不可能发起模型请求；
 * 2. **实时模式门控**：未启用（或已停止）时一律拒绝新的采集任务，且不会自动重启采集；
 * 3. **频率限制 + 单飞**：截图 OCR 两次之间至少间隔 [minOcrIntervalMillis]，
 *    同一时刻只允许一个在途 OCR 任务；
 * 4. **过期丢弃（不是「任意更新即丢弃」）**：只有**真正改变池内容**的观察才会推进「已接受序号」；
 *    序号早于它的迟到结果直接丢弃，绝不覆盖更新的池内容。而「无变化 / 不确定 / 重复视口」
 *    这类**未改变内容**的观察不携带新内容、无法与新题内容竞争，因此**不影响**在途 OCR 的序号判定
 *    （否则一次在途 OCR 期间任意一条重复上报都会把有效 OCR 结果误判成「乱序迟到」而丢弃）；
 * 5. **失败不覆盖**：OCR 失败 / 空文本只记录状态，**不改动**已有有效池内容。
 */
internal class RealtimeViewportCollector(
    private val pool: ScreenContextPool = ScreenContextPool(),
    private val minOcrIntervalMillis: Long = DEFAULT_MIN_OCR_INTERVAL_MILLIS,
    private val probeIntervalMillis: Long = DEFAULT_SCREEN_PROBE_INTERVAL_MILLIS,
    private val probeScrollSilenceMillis: Long = DEFAULT_PROBE_SCROLL_SILENCE_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()

    private var enabled = false
    private var ocrAvailable = false
    private var degradedNote: String? = null

    private var lastUpdateAtMillis: Long? = null
    private var lastObservedAtMillis: Long? = null

    /**
     * 最近一次「采集提交」的时间（**含**未读到文本 / 未确认目标窗口的提交）。
     *
     * 与 [lastObservedAtMillis] 明确区分：后者只有「真正进入合并、被接受」的观察才更新，
     * 本字段每次提交都更新 —— 这样「累计提交次数 N」与「最近一次提交时间」始终一致，
     * 不会再出现「提交了 103 次、最近一次观察却是 -」这种无法解释的状态差异。
     */
    private var lastSubmissionAtMillis: Long? = null

    private var lastResult: PoolUpdateResult? = null
    private var lastSource: RealtimeCollectSource? = null
    private var lastNote: String? = null

    /** 最近一次观察携带的滚动信号判定；用于区分「文本未变但有有效滚动」与「重复上报」。 */
    private var lastScrollDecision: RealtimeScrollDecision = RealtimeScrollDecision.NOT_SCROLL

    /** 最近一次**已被接受**的新滚动位置键；无可靠位置时保持 null。 */
    private var lastScrollPositionKey: String? = null

    /** 最近一次事件的原始类型与来源（诊断用；与滚动位置判定相互独立）。 */
    private var lastEventKind: RealtimeEventKind? = null
    private var lastEventFromTargetWindow: Boolean? = null

    /** 最近一次无障碍文本读取的结果状态（诊断用；区分「读到文本 / 视口无文本 / 读取失败」）。 */
    private var lastTextReadStatus: RealtimeTextReadStatus? = null

    /** 最近一次被门控拦截的 OCR 触发原因（仅诊断用，不含题目正文）。 */
    private var lastOcrGateNote: String? = null

    /** 最近一次 OCR 的**结果状态**（成功 / 失败），与「触发 / 门控原因」分开记录。 */
    private var lastOcrStatusNote: String? = null

    private var lastOcrAtMillis: Long? = null
    private var ocrInFlight = false

    /**
     * 目标应用最近一次滚动事件时间。
     *
     * 用于抑制「画面变化探测回退」：目标应用只要能提供滚动事件，滚动侧（修复 A）就已能
     * 可靠触发采集，无需再周期性截图探测，避免耗电、画面闪烁与 MediaProjection 抖动。
     */
    private var lastTargetScrollAtMillis: Long? = null

    /** 画面变化探测回退的运行状态：上次采样时间、判定结果与基线样本。 */
    private var lastProbeAtMillis: Long? = null
    private var lastProbeDecision: ScreenProbeDecision? = null
    private var lastProbeSample: List<Int>? = null

    private var updateCount = 0

    /** 观察序号：每次「开始接受一次观察」时自增，用于丢弃乱序迟到的旧结果。 */
    private var sequence = 0L

    /** 已经被接受的**最大**观察序号；小于它的提交一律判为乱序迟到。 */
    private var acceptedSequence = 0L

    /**
     * 最近一次「因无障碍文本明显不完整而补采集」所对应的**本帧内容指纹**（R8.4）。
     *
     * 去重语义：同一屏、同一段外壳文字只补一次；以下任一情况都会释放该键，使补采集重新可用 ——
     * 本帧无障碍内容变化（新的 [lastIncompleteAccessibilityFrameKey]）、目标应用滚动、
     * 或一次补采失败。因此「内容没有变化」不会永久锁死后继补采（频率仍由 [minOcrIntervalMillis] 限制）。
     */
    private var lastIncompleteSupplementFrameKey: String? = null

    /**
     * 最近一次无障碍观察**本帧**的内容指纹（仅在确认读到有效文本时记录）。
     *
     * 用于回答「这次要补采的内容是不是已经补过」：旧实现拿**池里**的文本判定完整性，
     * 会把「池里曾读到过选项」当成「当前屏幕已完整识别」的证据（真机因此长期跳过补采）。
     */
    private var lastIncompleteAccessibilityFrameKey: String? = null

    /**
     * 最近一次**无障碍观察**携带的视口是否「明显不完整」（一行选项标记都读不到）。
     *
     * 用途：回答「**本帧**（而不是池里）是否缺题干 / 选项」——它现在是补采集判定的主判据：
     * 本帧缺内容（且这一帧内容尚未补采过）就值得补一帧；本帧已读到选项则无需无意义重复 OCR。
     */
    private var lastIncomingAccessibilityIncomplete = false

    /** 视口文本的内容指纹（去空白后的原文，仅内存比较用；不写日志、不落盘）。 */
    private fun frameKeyOf(text: String): String = text.filter { !it.isWhitespace() }

    /** 当前文本池（供诊断页只读展示；池自身的语义不受影响）。 */
    fun currentPool(): ScreenContextPool = pool

    fun isEnabled(): Boolean = synchronized(lock) { enabled }

    /**
     * 实时模式开关。
     *
     * 关闭时必须**停止采集并释放池内容**（按既定生命周期），避免实时模式关闭后仍残留上一段
     * 会话的题目上下文；重新开启时从空池开始。
     */
    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        // 无论开关方向，都清空在途/计时状态，避免跨会话复用旧的频率窗口。
        lastOcrAtMillis = null
        ocrInFlight = false
        // 滚动位置键同样随会话重置：跨会话复用会造成「新滚动被误判为重复」。
        lastScrollPositionKey = null
        lastScrollDecision = RealtimeScrollDecision.NOT_SCROLL
        lastEventKind = null
        lastEventFromTargetWindow = null
        lastTextReadStatus = null
        lastOcrGateNote = null
        lastOcrStatusNote = null
        lastSubmissionAtMillis = null
        // 「文本明显不完整时的补采集」去重状态随会话复位，避免跨会话复用旧的去重键。
        lastIncompleteSupplementFrameKey = null
        lastIncompleteAccessibilityFrameKey = null
        lastIncomingAccessibilityIncomplete = false
        // 画面探测回退状态一并复位：关闭后不得再用上一段会话的样本 / 时间基线。
        lastTargetScrollAtMillis = null
        lastProbeAtMillis = null
        lastProbeDecision = null
        lastProbeSample = null
        if (enabled) {
            lastNote = "实时模式已开启，持续采集开始"
            lastResult = null
            lastSource = null
        } else {
            pool.clear()
            lastUpdateAtMillis = null
            lastObservedAtMillis = null
            lastResult = null
            lastSource = null
            lastNote = "实时模式已关闭，已停止采集并清空文本池"
        }
    }

    /**
     * 会话边界（R7）：显式重绑定到另一个目标应用时调用。
     * 清空累计题目上下文与滚动/事件证据，确保旧题内容不得串入新应用。
     *
     * 复位动作与 [setEnabled] 关闭分支保持一致（除 `enabled` 外），`enabled` 保持不变。
     */
    fun onSessionBoundary() = synchronized(lock) {
        pool.clear()
        // R8.3：推进序号，使「会话边界之前在途的旧应用 OCR 结果」（序号更小）在
        // [completeOcrAttempt] / [applyLocked] 中被判为乱序迟到而丢弃，绝不污染新题状态。
        acceptedSequence = ++sequence
        // 一并复位与「当前题目」绑定的证据，避免旧题证据影响新应用判定。
        lastOcrAtMillis = null
        ocrInFlight = false
        lastScrollPositionKey = null
        lastScrollDecision = RealtimeScrollDecision.NOT_SCROLL
        lastEventKind = null
        lastEventFromTargetWindow = null
        lastTextReadStatus = null
        lastOcrGateNote = null
        lastOcrStatusNote = null
        lastSubmissionAtMillis = null
        lastIncompleteSupplementFrameKey = null
        lastIncompleteAccessibilityFrameKey = null
        lastIncomingAccessibilityIncomplete = false
        lastTargetScrollAtMillis = null
        lastProbeAtMillis = null
        lastProbeDecision = null
        lastProbeSample = null
        lastUpdateAtMillis = null
        lastObservedAtMillis = null
        lastResult = null
        lastSource = null
        lastNote = "会话边界：已重绑定到新的目标应用，已清空累计题目上下文（旧题内容不得串入新应用）"
    }

    /**
     * 声明本地 OCR 能力（由 flavor 决定）。
     *
     * Lite flavor 不支持本地 ML Kit OCR，必须显式标记为能力降级：
     * 此后 [beginOcrAttempt] 一律拒绝，持续采集只使用无障碍节点树文本。
     */
    fun setOcrCapability(available: Boolean, degradedNote: String? = null) = synchronized(lock) {
        ocrAvailable = available
        this.degradedNote = degradedNote
    }

    /**
     * 主路径：提交一次无障碍节点树文本观察。
     *
     * 只更新本地文本池；**不**触发截图、OCR 或任何模型请求。
     * 返回结果供调用方决定是否需要补一次额外的证据（见 [shouldTriggerOcr]）。
     *
     * [scroll] 为本次事件**同帧复制**的滚动位置元数据（非滚动事件传 null）。
     * [eventKind] 为事件**原始类型**，[eventFromTargetWindow] 表示事件来源包名是否与本次读取到的
     * 目标窗口一致（用于排除本应用悬浮窗 / 输入法 / 其它窗口的滚动）。两者只用于诊断与
     * 「是否确为目标应用的滚动事件」的判定，不参与文本池合并。
     *
     * 采集器据此区分：
     * - [RealtimeScrollDecision.ADVANCED]：出现了新的**可靠**滚动位置；
     * - [RealtimeScrollDecision.REPEAT]：滚动位置未变（重复分发）；
     * - [RealtimeScrollDecision.UNKNOWN_LOCATION]：**确认发生了滚动、但精确位置未知**
     *   （事件未提供可用位置字段）—— 仍允许限频补一次受控 OCR，但**不虚构**滚动位置；
     * - [RealtimeScrollDecision.NOT_SCROLL]：**无法确认发生过滚动**（非滚动事件，或滚动来自
     *   非目标窗口）。
     */
    fun onAccessibilityText(
        text: String,
        windowKey: String? = null,
        atMillis: Long = clock(),
        scroll: RealtimeScrollSignal? = null,
        eventKind: RealtimeEventKind = RealtimeEventKind.UNKNOWN,
        eventFromTargetWindow: Boolean = true
    ): RealtimeCollectionOutcome = synchronized(lock) {
        if (!enabled) {
            lastScrollDecision = RealtimeScrollDecision.NOT_SCROLL
            return RealtimeCollectionOutcome(
                result = PoolUpdateResult.NO_CHANGE,
                applied = false,
                source = null,
                note = "实时模式未启用，忽略本次无障碍观察"
            )
        }
        lastEventKind = eventKind
        lastEventFromTargetWindow = eventFromTargetWindow
        // 记录「本次无障碍视口是否明显不完整」（一行选项标记都读不到）与**本帧**内容指纹（R8.4）：
        // 补采集判定必须看本帧，不能拿池里「曾经读到过选项」当当前屏完整的证据。
        lastIncomingAccessibilityIncomplete = accessibilityTextLacksOptionLines(text)
        lastIncompleteAccessibilityFrameKey = frameKeyOf(text).takeIf { it.isNotEmpty() }
        // 本次提交已到达：无论最终是否生效，都记录提交时间（与 updateCount 语义一致）。
        lastSubmissionAtMillis = atMillis
        val decision = scrollDecisionLocked(scroll, eventFromTargetWindow)
        lastScrollDecision = decision
        if (decision == RealtimeScrollDecision.ADVANCED) {
            lastScrollPositionKey = scroll?.positionKey
        }
        if (decision != RealtimeScrollDecision.NOT_SCROLL) {
            // 目标应用确实发生了滚动（可靠位置或位置未知均算）：记录时间戳，
            // 作为「画面变化探测回退」的抑制依据，避免与滚动触发的采集重复。
            lastTargetScrollAtMillis = atMillis
            // R8.3：目标应用确实滚动 → 可能出现新的题干 / 材料 / 选项，释放
            // 「同一不完整内容只补采一次」的去重键，使同题滚动后能继续按限频补采；
            // 真正的频率仍由 minOcrIntervalMillis（2 秒）+ 单飞保护限制。
            lastIncompleteSupplementFrameKey = null
        }
        val seq = ++sequence
        val fragment = accessibilityViewportFragmentOf(
            id = "a11y-$seq",
            text = text,
            capturedAtMillis = atMillis,
            windowKey = windowKey
        )
        applyLocked(seq, fragment, RealtimeCollectSource.ACCESSIBILITY, atMillis)
    }

    /**
     * 主路径（推荐）：提交一次无障碍观察，**文本允许为空**。
     *
     * 与 [onAccessibilityText] 的唯一差异（也是修复「后台实时采集未运行」的关键）：
     * 当本次**没有读到可用文本**时，仍然**完整保留**滚动证据（事件类型、来源是否为目标窗口、
     * 滚动位置元数据与时间戳），只是：
     * 1. 把[RealtimeCollectionOutcome.result] 明确记为 [PoolUpdateResult.OCR_FAILED]，
     *    `applied` 为 false —— **空文本绝不伪装成识别成功**，也**绝不**改动既有有效池内容；
     * 2. 记录 [RealtimeTextReadStatus]，让诊断能区分「目标窗口有效但视口无文本（EMPTY）」与
     *    「连有效目标窗口都没取到（FAILED）」；
     * 3. 仅当「确认发生了目标应用滚动」时，才把该次观察标记为值得补一次限频 OCR
     *    （见 [shouldTriggerOcr]）—— 因为节点树读不到文本时，文本相似度无法证明视口是否更新，
     *    此时唯一可用的证据就是真实滚动事件本身。
     *
     * [text] 为 null 或空白表示「本次没有读到可用文本」；[windowKey] 为 null 表示读取失败
     * （没有有效的第三方目标窗口）。
     */
    fun onAccessibilityObservation(
        text: String?,
        windowKey: String?,
        atMillis: Long = clock(),
        scroll: RealtimeScrollSignal? = null,
        eventKind: RealtimeEventKind = RealtimeEventKind.UNKNOWN,
        eventFromTargetWindow: Boolean = false
    ): RealtimeCollectionOutcome = synchronized(lock) {
        if (!enabled) {
            lastScrollDecision = RealtimeScrollDecision.NOT_SCROLL
            return RealtimeCollectionOutcome(
                result = PoolUpdateResult.NO_CHANGE,
                applied = false,
                source = null,
                note = "实时模式未启用，忽略本次无障碍观察"
            )
        }
        // 1) 先无条件保留事件与滚动证据（与文本是否读到无关）。
        lastEventKind = eventKind
        lastEventFromTargetWindow = eventFromTargetWindow
        // 本次提交已到达：无论文本是否读到、身份是否确认，都记录提交时间，
        // 使「累计提交次数」与「最近一次采集提交」始终一致（修复语义不一致）。
        lastSubmissionAtMillis = atMillis
        val decision = scrollDecisionLocked(scroll, eventFromTargetWindow)
        lastScrollDecision = decision
        if (decision == RealtimeScrollDecision.ADVANCED) {
            lastScrollPositionKey = scroll?.positionKey
        }
        if (decision != RealtimeScrollDecision.NOT_SCROLL) {
            lastTargetScrollAtMillis = atMillis
            // R8.3：与 [onAccessibilityText] 同理 —— 目标滚动意味着可能出现新的题干 / 材料 / 选项，
            // 释放「同一不完整内容只补采一次」的去重键（频率仍由 minOcrIntervalMillis + 单飞限制）。
            lastIncompleteSupplementFrameKey = null
        }
        // 2) 再判定本次文本读取状态。
        val readStatus = when {
            // 目标应用身份未确认（连有效第三方窗口 / 事件来源包名都没有）→ FAILED；
            // 身份已确认但本次视口没有可读文本 → EMPTY（**不等于**没有目标窗口）。
            windowKey == null -> RealtimeTextReadStatus.FAILED
            text.isNullOrBlank() -> RealtimeTextReadStatus.EMPTY
            else -> RealtimeTextReadStatus.SUCCESS
        }
        lastTextReadStatus = readStatus
        // 记录「本次无障碍视口是否明显不完整」（一行选项标记都读不到）：供 [shouldTriggerOcr] 判断
        // 「明显不完整的无障碍视口撞上已由 OCR 建立的更完整上下文」时不再重复补采。
        lastIncomingAccessibilityIncomplete =
            readStatus == RealtimeTextReadStatus.SUCCESS && accessibilityTextLacksOptionLines(text.orEmpty())
        // R8.4：同时记录**本帧**内容指纹，供「同一内容只补一次、内容变化即可再补」去重。
        // 未读到有效文本时清空 —— 不得拿上一帧的指纹替代本次读取结果。
        lastIncompleteAccessibilityFrameKey = text
            ?.takeIf { readStatus == RealtimeTextReadStatus.SUCCESS }
            ?.let { frameKeyOf(it) }
            ?.takeIf { it.isNotEmpty() }
        if (readStatus != RealtimeTextReadStatus.SUCCESS) {
            // 空文本 / 读取失败：不提交到文本池。滚动证据已在上面保留，供 shouldTriggerOcr 使用。
            updateCount++
            lastResult = PoolUpdateResult.OCR_FAILED
            lastSource = RealtimeCollectSource.ACCESSIBILITY
            lastNote = when (readStatus) {
                RealtimeTextReadStatus.EMPTY ->
                    "当前视口未读到无障碍文本（已确认目标应用身份，已保留滚动证据，文本池内容未变）"
                else ->
                    "未能确认有效的第三方目标应用窗口（无法判定事件归属，文本池内容未变）"
            }
            return RealtimeCollectionOutcome(
                result = PoolUpdateResult.OCR_FAILED,
                applied = false,
                source = RealtimeCollectSource.ACCESSIBILITY,
                note = lastNote
            )
        }
        // 3) 读到有效文本：走原有合并链路（与 onAccessibilityText 完全一致）。
        val seq = ++sequence
        val fragment = accessibilityViewportFragmentOf(
            id = "a11y-$seq",
            text = text.orEmpty(),
            capturedAtMillis = atMillis,
            windowKey = windowKey
        )
        applyLocked(seq, fragment, RealtimeCollectSource.ACCESSIBILITY, atMillis)
    }

    /**
     * 判定「本轮观察结果是否值得补一次本地 OCR」。
     *
     * 五处「确有必要」：
     * 1. 池判定为 [PoolUpdateResult.UNSTABLE]（节点树文本无法证明是继续滚动还是换了画面）；
     * 2. 池判定为 [PoolUpdateResult.NO_CHANGE] 且出现**新的可靠滚动位置**
     *    （[RealtimeScrollDecision.ADVANCED]）；
     * 3. 池判定为 [PoolUpdateResult.NO_CHANGE] 且**确认发生了滚动但位置未知**
     *    （[RealtimeScrollDecision.UNKNOWN_LOCATION]）—— 真机上刷题 App 常只分发
     *    `TYPE_VIEW_SCROLLED` 而位置字段不可用，此时「文本没变」不能证明「视口没变」，
     *    若一律禁止截图会让自动采集静默失效，故允许在 2 秒间隔 + 单飞保护下补一次 OCR。
     * 4. 池判定为 [PoolUpdateResult.OCR_FAILED] 且**本次没有读到可用无障碍文本**
     *    （[RealtimeTextReadStatus.EMPTY] / [RealtimeTextReadStatus.FAILED]，即节点树读不到文字）
     *    且**确认发生了目标应用滚动** —— 这是真机「后台实时采集未运行」的主要场景：
     *    题目由 Canvas / WebView / 自绘控件承载，节点树几乎没有文字，
     *    此时唯一的视口更新证据就是真实滚动事件本身。
     * 5. **文本质量驱动**（R8.2 / R8.3 / R8.4）：本轮读到了无障碍文本（[RealtimeTextReadStatus.SUCCESS]，
     *    即目标应用身份已确认），但**本帧**文本**明显不完整**（[accessibilityTextLacksOptionLines]：
     *    一行选项标记都读不到，说明节点树只读到了标题 / 题号 / 材料外壳）——
     *    真机粉笔资料分析页正是如此（只读到 36 字外壳）。此时「文本池已由文本更新」不能作为
     *    「无需补 OCR」的理由。R8.4 修复：判据只看**本帧**，并**不再**用「池里曾读到过选项结构」
     *    推断当前屏完整（真机因此长期跳过补采：池里是上一次 OCR 的 206 字，本帧只有 36 字外壳）。
     *    去重按**本帧内容指纹**（[lastIncompleteSupplementFrameKey]）：同一屏、同一段外壳文字只补一次，
     *    本帧内容变化、目标应用滚动或一次补采失败都会释放该键，因此不会永久锁死后继补采
     *    （频率仍由 [minOcrIntervalMillis] 限制）。
     *
     * 明确**不**触发的情形：非滚动事件（如 `TYPE_WINDOW_CONTENT_CHANGED`）、滚动来自非目标窗口、
     * 滚动位置未变的重复分发（[RealtimeScrollDecision.REPEAT]）、以及文本池已确认当前视口重复
     * （[PoolUpdateResult.DUPLICATE]）或本帧已读到选项（无需无意义重复识别）。
     *
     * 同时要求 OCR 能力可用、距上次 OCR 已超过最小间隔、且当前无在途任务；
     * 本方法只做判定，**不**触发任何截图，并把被拦截原因写入只读诊断。
     */
    fun shouldTriggerOcr(
        lastResult: PoolUpdateResult,
        atMillis: Long = clock()
    ): Boolean = synchronized(lock) {
        if (!enabled) return false
        val scrollEvidence = lastScrollDecision == RealtimeScrollDecision.ADVANCED ||
            lastScrollDecision == RealtimeScrollDecision.UNKNOWN_LOCATION
        val scrollEvidenceUnknown = lastScrollDecision == RealtimeScrollDecision.UNKNOWN_LOCATION
        // 「本次没有读到可用文本」但「确认目标应用发生了滚动」同样值得补一次 OCR：
        // 节点树读不到文本时文本相似度失效，唯一的视口更新证据就是真实滚动事件本身。
        val textUnavailable = lastTextReadStatus != null &&
            lastTextReadStatus != RealtimeTextReadStatus.SUCCESS
        // 「本轮读到了无障碍文本」并不等于「文本已完整」：粉笔资料分析页只读到 36 字外壳
        // （标题 / 题号 / 材料占位，一行选项标记都没有）。因此这条**质量驱动**的补采集分支
        // 只看**本帧**是否缺内容（[lastIncomingAccessibilityIncomplete]），并按「本帧内容指纹」
        // 去重；绝不拿池里曾经读到过的选项结构当作当前屏完整的证据。
        val incompleteSupplement = lastTextReadStatus == RealtimeTextReadStatus.SUCCESS &&
            incompleteSupplementWarrantedLocked()
        // 同一不完整内容只补一次：本帧内容指纹已命中补采键时，UNSTABLE 不再重复触发；
        // 目标滚动、本帧内容变化或补采失败都会释放该键（见 [onAccessibilityObservation] / [recordFailureLocked]），
        // 因此不会永久锁死后继补采。
        val supplementDeduped = lastIncompleteSupplementFrameKey != null &&
            lastIncompleteSupplementFrameKey == lastIncompleteAccessibilityFrameKey
        // R8.6：除「画面不稳定」外，「关联未知」（[PoolUpdateResult.UNPROVEN]）同样值得补一次受控 OCR ——
        // 这正是跨滚动长题的典型形态：本帧无障碍文本与既有内容既无连续重叠、也无足量同题证据，
        // 唯一的补齐手段就是一次受控 OCR。仍受「本帧内容指纹」去重约束，不会形成无界循环。
        val unstableWorthRetry = (lastResult == PoolUpdateResult.UNSTABLE ||
            lastResult == PoolUpdateResult.UNPROVEN) &&
            !(lastIncomingAccessibilityIncomplete && supplementDeduped)
        val needed = when {
            unstableWorthRetry -> true
            lastResult == PoolUpdateResult.NO_CHANGE && scrollEvidence -> true
            lastResult == PoolUpdateResult.OCR_FAILED && textUnavailable && scrollEvidence -> true
            incompleteSupplement -> true
            else -> false
        }
        if (!needed) {
            // 只记录**本帧**级别的去重原因：同一屏、同一段外壳文字已经补采过一次，屏幕内容变化前不重复截图。
            lastOcrGateNote = if (lastIncomingAccessibilityIncomplete && supplementDeduped) {
                "OCR 未触发：本帧无障碍视口明显不完整（未读到任何选项行），但同一内容已按限频补采过一次" +
                    "（本帧内容指纹未变化），屏幕内容变化后即可再次补采"
            } else {
                null
            }
            return false
        }
        if (!ocrAvailable) {
            lastOcrGateNote = "OCR 未触发：本地识别能力不可用（能力降级），仅使用无障碍节点树文本"
            return false
        }
        if (ocrInFlight) {
            lastOcrGateNote = "OCR 未触发：已有在途 OCR 任务（单飞）"
            return false
        }
        val lastOcrAt = lastOcrAtMillis
        if (lastOcrAt != null && atMillis - lastOcrAt < minOcrIntervalMillis) {
            lastOcrGateNote =
                "OCR 未触发：距上次截图 OCR 仅 ${atMillis - lastOcrAt} ms（< $minOcrIntervalMillis ms，频率限制）"
            return false
        }
        lastOcrGateNote = when {
            lastResult == PoolUpdateResult.OCR_FAILED && textUnavailable ->
                "OCR 已触发：当前视口未读到无障碍文本，但确认目标应用发生了滚动（按限频探测当前画面）"
            lastResult == PoolUpdateResult.UNSTABLE ->
                "OCR 已触发：无障碍文本无法证明视口是否更新（不确定）"
            lastResult == PoolUpdateResult.UNPROVEN ->
                "OCR 已触发：本帧无障碍文本与既有内容既无连续重叠也无足量同题证据（关联未知），按限频补一次受控 OCR"
            scrollEvidenceUnknown ->
                "OCR 已触发：确认发生了滚动但事件未提供可靠位置（不虚构位置，按限频探测当前画面）"
            incompleteSupplement ->
                "OCR 已触发：无障碍文本明显不完整（未读到任何选项行，可能只读到标题 / 题号 / 材料外壳），" +
                    "按限频补一次受控 OCR 以补齐题干 / 选项"
            else ->
                "OCR 已触发：出现新的滚动位置（${lastScrollPositionKey ?: "位置未知"}），而无障碍文本未变化"
        }
        true
    }

    /**
     * 「无障碍文本明显不完整」时的补采集判定（[shouldTriggerOcr] 与画面探测回退共用）。
     *
     * 只回答「是否值得再补一次 OCR」，**不**做能力 / 限频 / 单飞判定 —— 那些仍由既有门控完成。
     *
     * R8.4 修复：判据从「**池里**的文本缺选项」改为「**本帧**无障碍文本缺选项」。
     * 旧判据（`pool.currentQuestion()?.text` 无选项行才补采）把「池里曾经读到过选项结构」当成
     * 「当前屏幕已经完整识别」的证据：真机粉笔资料分析页因此长期跳过补采 —— 池里是上一次 OCR 的
     * 206 字，而本帧只读到 36 字外壳，题干与选项永远补齐不了。
     *
     * 去重按**本帧内容指纹**：同一屏、同一段外壳文字只补一次；本帧内容变化（用户滚动 / 题目正文出现）
     * 或一次补采失败即失效，因此「内容没有变化」不会永久锁死后继补采。
     */
    private fun incompleteSupplementWarrantedLocked(): Boolean {
        if (!enabled) return false
        if (!lastIncomingAccessibilityIncomplete) return false
        val frameKey = lastIncompleteAccessibilityFrameKey ?: return false
        if (lastIncompleteSupplementFrameKey == frameKey) return false
        return true
    }

    /**
     * 「薄外壳种子被更完整的 OCR 视口取代」判定（R8.2），返回**用于重建上下文的片段**或 null。
     *
     * 真机现象：无障碍先建立一个明显不完整的题目上下文（粉笔资料分析页只读到 28 字外壳），
     * 随后补采集 OCR 读到的是**完整一屏**（题干 + 选项）。此时分类器按「旧种子字符不足、
     * 与新视口无可靠重叠」判为 [PoolUpdateResult.UNSTABLE]，会把这次有效补采集**丢弃**，
     * 题目正文永远进不了池（已由单测 `supplement_ocr_full_question_enters_question_pool` 复现）。
     *
     * 既然「上一份种子明显不完整（一行选项标记都读不到）」已有独立判据确认，就不能把
     * 「无法与不完整种子对齐」当成「本次读取无证据」：应以更完整的本次视口**重建**题目上下文。
     *
     * 生效前提（缺一不可，故不影响既有正常合并路径）：
     * 1. 已能确认窗口身份（片段自身或池中已有 windowKey 非空）；
     * 2. 池中种子文本**明显不完整**（无选项行）；
     * 3. 本次 OCR 视口**明显更完整**（读到 ≥ 2 行选项，或去空白字数显著多于种子）。
     */
    private fun supersededFragmentIfAny(
        previous: QuestionContextSnapshot?,
        organization: OcrOrganization,
        effectiveWindowKey: String?,
        fragment: ViewportFragment
    ): ViewportFragment? {
        if (effectiveWindowKey == null) return null
        val seedText = previous?.text ?: return null
        if (!accessibilityTextLacksOptionLines(seedText)) return null
        val ocrText = organization.text
        if (ocrText.isBlank()) return null
        val seedChars = seedText.count { !it.isWhitespace() }
        val ocrChars = ocrText.count { !it.isWhitespace() }
        val ocrHasOptions = countOptionLikeLines(ocrText) >= 2
        if (!ocrHasOptions && ocrChars <= seedChars * 2) return null
        return fragment.copy(windowKey = effectiveWindowKey)
    }

    /**
     * 供「画面变化探测回退」使用的补充判定：当**画面未发生变化**（`changed=false`）时，
     * 若当前题目上下文仍**明显不完整**，仍应补一次受控 OCR。
     *
     * 语义（R8.2）：不得仅因 `changed=false` 就断定无需 OCR —— 画面可能确实没变，
     * 但此前提取的无障碍文本仍然不完整（真机粉笔资料分析页只读到 28 字外壳）。
     * 本方法只回答「是否值得补一次」；真正的截图仍须走 [beginOcrAttempt] 的限频 + 单飞保护，
     * 且调用方必须已确认目标应用位于前台（安全门控不变）。
     */
    fun shouldSupplementOcrForIncompletePool(): Boolean = synchronized(lock) {
        if (!ocrAvailable) return false
        incompleteSupplementWarrantedLocked()
    }

    /**
     * 预约一次截图 OCR 执行（单飞 + 频率限制）。
     *
     * 返回本次提交序号；返回 null 表示本次不执行（未启用 / 能力不可用 / 频率未到 / 已有在途任务）。
     */
    fun beginOcrAttempt(atMillis: Long = clock()): Long? = synchronized(lock) {
        if (!enabled) return null
        if (!ocrAvailable) {
            lastOcrGateNote = "OCR 未触发：本地识别能力不可用（能力降级），仅使用无障碍节点树文本"
            return null
        }
        if (ocrInFlight) {
            lastOcrGateNote = "OCR 未触发：已有在途 OCR 任务（单飞），本次观察合并到在途任务"
            return null
        }
        val previous = lastOcrAtMillis
        if (previous != null && atMillis - previous < minOcrIntervalMillis) {
            lastOcrGateNote =
                "OCR 未触发：距上次截图 OCR 仅 ${atMillis - previous} ms（< $minOcrIntervalMillis ms，频率限制）"
            return null
        }
        ocrInFlight = true
        lastOcrAtMillis = atMillis
        // 本次预约若正是「文本明显不完整」的补采集，记录**本帧内容指纹**作为去重键，
        // 避免对同一屏、同一段外壳文字反复补采（本帧内容变化或本次补采失败后即可再次补采）。
        if (incompleteSupplementWarrantedLocked()) {
            lastIncompleteSupplementFrameKey = lastIncompleteAccessibilityFrameKey
        }
        ++sequence
    }

    /**
     * 提交一次 OCR 采集结果。
     *
     * - [organization] 为 null 或整理后文本为空 → 记录失败，**绝不改动**池内容；
     * - [sequence] 早于已接受序号 → 判为乱序迟到并丢弃（不覆盖更新的池内容）；
     * - 其余情况交给 [ScreenContextPool.observe] 按变化分类与合并规则处理。
     */
    fun completeOcrAttempt(
        sequence: Long,
        organization: OcrOrganization?,
        rawOcrText: String? = null,
        windowKey: String? = null,
        atMillis: Long = clock()
    ): RealtimeCollectionOutcome = synchronized(lock) {
        ocrInFlight = false
        if (!enabled) {
            return RealtimeCollectionOutcome(
                result = PoolUpdateResult.NO_CHANGE,
                applied = false,
                source = RealtimeCollectSource.LOCAL_OCR,
                note = "实时模式已关闭，丢弃本次 OCR 结果"
            )
        }
        if (organization == null || organization.text.isBlank()) {
            return recordFailureLocked(
                source = RealtimeCollectSource.LOCAL_OCR,
                note = "本次 OCR 未返回有效文字（未改动文本池）"
            )
        }
        if (sequence < acceptedSequence) {
            return RealtimeCollectionOutcome(
                result = PoolUpdateResult.UNSTABLE,
                applied = false,
                source = RealtimeCollectSource.LOCAL_OCR,
                note = "本次 OCR 帧早于已入库内容（在途期间已有观察真正改变了文本池），" +
                    "已丢弃以免用旧画面覆盖更新的题目内容"
            )
        }
        val fragment = viewportFragmentOf(
            id = "ocr-$sequence",
            organization = organization,
            rawOcrText = rawOcrText.orEmpty(),
            capturedAtMillis = atMillis,
            windowKey = windowKey
        )
        // R8.2：若上一份题目上下文明显不完整（薄外壳种子），而本次 OCR 明显更完整且窗口身份可确认，
        // 则以本次更完整视口**重建**上下文，而不是让分类器把这次有效补采集判为 UNSTABLE 后丢弃。
        val priorSnapshot = pool.currentQuestion()
        val superseded = supersededFragmentIfAny(
            previous = priorSnapshot,
            organization = organization,
            effectiveWindowKey = windowKey ?: priorSnapshot?.windowKey,
            fragment = fragment
        )
        val outcome = if (superseded != null) {
            pool.clear()
            applyLocked(sequence, superseded, RealtimeCollectSource.LOCAL_OCR, atMillis)
        } else {
            applyLocked(sequence, fragment, RealtimeCollectSource.LOCAL_OCR, atMillis)
        }
        // 只记录结果状态的**非敏感摘要**（不写完整识别原文），与「触发 / 门控原因」分开。
        // 文案必须明确这是**本次截图**的识别行数（`organization.rowCount` 为本次 OCR 结果的
        // 视觉行数），而不是内容池累计行数：累计状态另有独立的「文本池 N 片段 / M 字」展示。
        lastOcrStatusNote = if (outcome.applied) {
            "成功：本次截图识别到 ${organization.rowCount} 行（结果 ${outcome.result.name}）"
        } else {
            "未生效：${outcome.note ?: outcome.result.name}"
        }
        return outcome
    }

    /**
     * 上报一次 OCR 执行失败（截图失败 / 识别抛异常 / 无会话等）。只记录状态，不改动池内容。
     *
     * [reason] 为可选的**具体阻断原因**（非敏感，如「截图会话不可用」）：用于让诊断准确标明
     * 「为何没有执行受控 OCR」，而不是把所有失败统一成一句泛化的失败说明。
     * 参数顺序保持与既有调用兼容（[atMillis] 在前，[reason] 为末尾可选参数）。
     */
    fun failOcrAttempt(
        sequence: Long,
        atMillis: Long = clock(),
        reason: String? = null
    ): RealtimeCollectionOutcome = synchronized(lock) {
        ocrInFlight = false
        if (!enabled) {
            return RealtimeCollectionOutcome(
                result = PoolUpdateResult.OCR_FAILED,
                applied = false,
                source = RealtimeCollectSource.LOCAL_OCR,
                note = "实时模式已关闭，忽略本次 OCR 失败"
            )
        }
        recordFailureLocked(
            source = RealtimeCollectSource.LOCAL_OCR,
            note = reason ?: "第 $sequence 次 OCR 执行失败（未改动文本池）"
        )
    }

    /**
     * 受控「画面变化探测回退」（修复 B）：判断当前是否**允许**发起一次探测采样。
     *
     * 仅在满足以下全部条件时为 true：
     * 1. 实时模式已启用，且本地 OCR 能力可用；
     * 2. 当前没有在途 OCR 任务（避免与滚动触发的 OCR 抢占截图管线）；
     * 3. 距上次探测已超过 [probeIntervalMillis]（限频）；
     * 4. **最近 [probeScrollSilenceMillis] 内没有收到过来自目标应用的滚动事件** ——
     *    这是「回退」语义的由来：目标应用只要能提供滚动事件，滚动侧（修复 A + 位置未知降级）
     *    就已能可靠触发采集，无需再周期性截图探测，避免耗电、画面闪烁与 MediaProjection 抖动。
     *
     * 本方法只做判定，**不**截图；调用方还必须自行确认「实时模式开启、无障碍服务正常、
     * 目标应用位于前台、截图会话可用」这些运行期条件。
     */
    fun shouldProbeScreen(atMillis: Long = clock()): Boolean = synchronized(lock) {
        if (!enabled || !ocrAvailable) return false
        if (ocrInFlight) return false
        val lastProbe = lastProbeAtMillis
        if (lastProbe != null && atMillis - lastProbe < probeIntervalMillis) return false
        val lastScroll = lastTargetScrollAtMillis
        if (lastScroll != null && atMillis - lastScroll < probeScrollSilenceMillis) return false
        true
    }

    /**
     * 提交一份画面探测样本，返回「是否应进入既有 OCR 单飞 + 频率限制流程」。
     *
     * - 首次采样只建立基线（返回 false）：没有可比对的历史样本，不得据此触发 OCR；
     * - 与上一份样本的差异未达到实质变化阈值 → 返回 false（避免被时钟 / 动画误触）；
     *   注意：返回 false **不等于**「无需 OCR」—— 调用方在画面未变化时仍应通过
     *   [shouldSupplementOcrForIncompletePool] 判断「当前题目上下文是否仍明显不完整」并据此补采（R8.2）；
     * - 差异达到阈值 → 返回 true，调用方随后必须走 [beginOcrAttempt]（单飞 + 频率限制）。
     *
     * 实时模式已关闭时直接返回 false 且不更新基线，避免关闭后仍残留探测状态。
     */
    fun onScreenProbe(sample: ScreenSample, atMillis: Long = clock()): Boolean = synchronized(lock) {
        if (!enabled) return false
        lastProbeAtMillis = atMillis
        val previous = lastProbeSample
        lastProbeSample = sample.cells
        lastProbeDecision = when {
            previous == null -> ScreenProbeDecision.FIRST_SAMPLE
            ScreenSampleComparison.substantiallyChanged(previous, sample.cells) -> ScreenProbeDecision.CHANGED
            else -> ScreenProbeDecision.UNCHANGED
        }
        lastProbeDecision == ScreenProbeDecision.CHANGED
    }

    /** 只读诊断状态。 */
    fun status(): RealtimeCollectionStatus = synchronized(lock) {
        val snapshot = pool.currentQuestion()
        RealtimeCollectionStatus(
            enabled = enabled,
            ocrAvailable = ocrAvailable,
            degradedNote = degradedNote,
            lastUpdateAtMillis = lastUpdateAtMillis,
            lastObservedAtMillis = lastObservedAtMillis,
            lastSubmissionAtMillis = lastSubmissionAtMillis,
            lastResult = lastResult,
            lastSource = lastSource,
            lastNote = lastNote,
            lastScrollDecision = lastScrollDecision,
            lastScrollPositionKey = lastScrollPositionKey,
            lastEventKind = lastEventKind,
            lastEventFromTargetWindow = lastEventFromTargetWindow,
            lastTextReadStatus = lastTextReadStatus,
            lastOcrGateNote = lastOcrGateNote,
            lastOcrStatusNote = lastOcrStatusNote,
            lastOcrAtMillis = lastOcrAtMillis,
            ocrInFlight = ocrInFlight,
            sequence = sequence,
            acceptedSequence = acceptedSequence,
            updateCount = updateCount,
            questionKey = snapshot?.questionKey,
            questionNumber = snapshot?.questionNumber,
            fragmentCount = pool.fragmentCount(),
            lastTargetScrollAtMillis = lastTargetScrollAtMillis,
            probeIntervalMillis = probeIntervalMillis,
            probeScrollSilenceMillis = probeScrollSilenceMillis,
            lastProbeAtMillis = lastProbeAtMillis,
            lastProbeDecision = lastProbeDecision,
            completeness = snapshot?.completeness ?: QuestionCompleteness.UNKNOWN,
            poolText = snapshot?.text.orEmpty()
        )
    }

    // ============================== 内部 ==============================

    /**
     * 判定本次观察携带的**目标应用滚动证据**。
     *
     * 先决条件 —— 只有「事件类型确为滚动事件且来源是目标窗口」才进入位置判定：
     * - 没有滚动元数据（`scroll == null`）→ [RealtimeScrollDecision.NOT_SCROLL]
     *   （**无法确认发生过滚动**，例如内容 / 文本 / 窗口变化事件）；
     * - 滚动来自非目标窗口（[fromTargetWindow] 为 false，如本应用悬浮窗）→
     *   [RealtimeScrollDecision.NOT_SCROLL]，绝不当作刷题页面滚动；
     *
     * 位置判定：
     * - 无可靠位置 → [RealtimeScrollDecision.UNKNOWN_LOCATION]（**滚动已发生、位置未知**，
     *   允许限频触发 OCR，但**不虚构位置**）；
     * - 位置与上次已接受的位置相同 → [RealtimeScrollDecision.REPEAT]（重复分发 / 位置未变）；
     * - 其余（位置键不同）→ [RealtimeScrollDecision.ADVANCED]。
     */
    private fun scrollDecisionLocked(
        scroll: RealtimeScrollSignal?,
        fromTargetWindow: Boolean
    ): RealtimeScrollDecision {
        if (scroll == null) return RealtimeScrollDecision.NOT_SCROLL
        if (!fromTargetWindow) return RealtimeScrollDecision.NOT_SCROLL
        val key = scroll.positionKey ?: return RealtimeScrollDecision.UNKNOWN_LOCATION
        return if (key == lastScrollPositionKey) {
            RealtimeScrollDecision.REPEAT
        } else {
            RealtimeScrollDecision.ADVANCED
        }
    }

    private fun applyLocked(
        sequence: Long,
        fragment: ViewportFragment,
        source: RealtimeCollectSource,
        atMillis: Long
    ): RealtimeCollectionOutcome {
        // 本次提交已到达：先记录提交时间（含随后被判为乱序丢弃 / 未读到文字的情形），
        // 保证「累计提交次数」与「最近一次采集提交」语义一致。
        lastSubmissionAtMillis = atMillis
        if (sequence < acceptedSequence) {
            return RealtimeCollectionOutcome(
                result = PoolUpdateResult.UNSTABLE,
                applied = false,
                source = source,
                note = "本次观察早于已入库内容（乱序迟到），已丢弃以免破坏视口顺序"
            )
        }
        val result = pool.observe(fragment)
        lastResult = result
        lastSource = source
        updateCount++
        if (result == PoolUpdateResult.OCR_FAILED) {
            // 没有有效文字：不刷新观察时间基线，也不改池内容。
            lastNote = "本次观察没有读到有效文字，保持既有文本池不变"
            return RealtimeCollectionOutcome(result, false, source, lastNote)
        }
        lastObservedAtMillis = atMillis
        lastNote = poolNoteOf(result, fragment)
        // R2 修复：只有**真正改变池内容**的观察才推进 acceptedSequence。
        //「无变化 / 不确定 / 重复视口」不携带任何新内容，无法与新题内容竞争；
        // 若它们也推进序号，一次在途 OCR 期间任意一条重复上报都会把 OCR 的序号顶掉，
        // 使有效 OCR 结果被误判为「乱序迟到」丢弃。真正改变内容的观察才可能让旧 OCR 帧过期。
        if (result.contentChanged) {
            acceptedSequence = sequence
            lastUpdateAtMillis = atMillis
        }
        return RealtimeCollectionOutcome(result, true, source, lastNote)
    }

    private fun poolNoteOf(result: PoolUpdateResult, fragment: ViewportFragment): String = when (result) {
        PoolUpdateResult.NEW_QUESTION -> "疑似新题：建立了新的题目上下文（不继承旧题文本）"
        PoolUpdateResult.APPENDED -> "新增视口：既有内容为空，整体采纳本次视口"
        PoolUpdateResult.OVERLAP_MERGED -> "合并视口：按相邻边界去重后追加新增内容"
        PoolUpdateResult.DUPLICATE -> "重复视口：本次内容已在池中，未追加"
        PoolUpdateResult.NO_CHANGE -> "无实质变化：与既有内容一致，未改动"
        PoolUpdateResult.NEW_SEGMENT -> buildString {
            append("新片段：无法证明与既有正文连续，但存在同题证据，")
            append("已作为独立片段保留（分段注入，未与既有正文拼接）")
            pool.lastDecisionNote()?.let { append("；判定原因：$it") }
        }
        PoolUpdateResult.UNPROVEN -> buildString {
            append("关联未知：本帧与既有内容既无连续重叠也无足量同题证据（本次 ${fragment.readingOrderLines.size} 行），")
            append("既有内容保持不变；本帧片段仅保留供核查、不注入最终请求")
            pool.lastDecisionNote()?.let { append("；判定原因：$it") }
        }
        PoolUpdateResult.UNSTABLE -> buildString {
            append("不确定：无可证明的同题重叠证据（本次 ${fragment.readingOrderLines.size} 行），保持既有内容不变")
            // 附上分类器的**具体**判定原因（含双方行数与去空白字数），用于真机定位「为什么没能合并」。
            pool.lastDecisionNote()?.let { append("；判定原因：$it") }
        }
        PoolUpdateResult.OCR_FAILED -> "本次没有读到有效文字，保持既有内容不变"
    }

    private fun recordFailureLocked(
        source: RealtimeCollectSource,
        note: String
    ): RealtimeCollectionOutcome {
        lastResult = PoolUpdateResult.OCR_FAILED
        lastSource = source
        lastNote = note
        lastOcrStatusNote = "失败：$note"
        // 一次补采集失败（截图 / 识别失败 / OCR 返回空）不得被当成「已经补过」而永久跳过：
        // 释放「本帧内容指纹」去重键，使下次观察仍可再补采；既有 minOcrIntervalMillis 仍保证不会高频重试。
        lastIncompleteSupplementFrameKey = null
        return RealtimeCollectionOutcome(PoolUpdateResult.OCR_FAILED, false, source, note)
    }

    companion object {
        /**
         * 两次截图 OCR 之间的最小间隔。
         *
         * 持续采集不得比「单次请求」更频繁地抢占截图管线（截图需要隐藏/恢复悬浮窗、
         * 并等待窗口隐藏后的新帧），因此这里给出下限；无障碍节点树文本不受该限制，
         * 其频率由既有的 300ms 事件防抖与 [ScreenChangeDetector.MIN_OBSERVATION_INTERVAL_MILLIS] 共同约束。
         */
        const val DEFAULT_MIN_OCR_INTERVAL_MILLIS = 2_000L

        /**
         * 「画面变化探测回退」的采样间隔。
         *
         * 该回退**不会常开**：只有当 [DEFAULT_PROBE_SCROLL_SILENCE_MILLIS] 内没有任何来自目标
         * 应用的滚动事件时才会激活（见 [shouldProbeScreen]），即仅用于「目标应用不发送可靠滚动
         * 事件」这一已知限制；间隔取较保守的值，避免连续截图带来的耗电与画面闪烁。
         */
        const val DEFAULT_SCREEN_PROBE_INTERVAL_MILLIS = 6_000L

        /**
         * 收到目标应用滚动事件后抑制「画面变化探测回退」的时长。
         *
         * 滚动事件（含「滚动已发生但位置未知」）本身已能触发采集，此时无需额外截图探测；
         * 只有持续一段时间收不到滚动事件，才认定目标应用确实不提供滚动元数据。
         */
        const val DEFAULT_PROBE_SCROLL_SILENCE_MILLIS = 10_000L
    }
}

/**
 * 迷你窗顶部「实时感知中」状态文案（纯函数，便于单元测试）。
 *
 * 关键修复：该文案**不再只绑定单次发送的 [ScreenReadReport] / 快照**，而是绑定后台持续采集器的
 * **实时状态**，从而把此前被统一显示成「未获取到屏幕内容」的四种完全不同情况分开：
 * 1. 实时模式未启用 → 后台采集**未启动**；
 * 2. 已启动但尚未收到任何无障碍事件（需检查无障碍服务是否连接 / 目标应用是否前台）；
 * 3. 收到了事件但**当前视口没有读到文本**（且已保留滚动证据，等待滚动后按限频识别）；
 * 4. 读到了文本 / 文本池已积累新内容 / 最近一次后台 OCR 成功或失败。
 *
 * 仍保留既有语义：最近一次**发送失败**必须优先展示其原因，不得被采集状态掩盖。
 */
internal fun realtimePerceptionStatusText(
    active: Boolean,
    snapshot: ScreenContextSnapshot?,
    report: ScreenReadReport?,
    collection: RealtimeCollectionStatus
): String {
    if (!active) return ""
    if (report?.status == ScreenReadStatus.SEND_FAILED) {
        return "实时感知中 · 本次发送失败：${failureStageText(report.failureStage)}"
    }
    val ocrSuffix = collection.lastOcrStatusNote?.let { "；后台 OCR：$it" }.orEmpty()
    return when {
        !collection.enabled -> "实时感知中 · 后台采集未启动（实时模式未开启）"
        collection.fragmentCount > 0 && collection.poolText.isNotBlank() ->
            "实时感知中 · 后台采集中（文本池 ${collection.fragmentCount} 片段 / " +
                "${collection.poolText.length} 字）$ocrSuffix"
        collection.lastEventKind == null && collection.lastTextReadStatus == null ->
            // 采集器尚未收到任何观察：此时沿用既有单次读取/快照语义（若有记录），
            // 否则明确说明「后台采集已启动但没有事件」，而不是含糊的「未获取到屏幕内容」。
            if (report == null && snapshot == null) {
                "实时感知中 · 后台采集已启动，尚未收到无障碍事件（请确认无障碍服务已连接）"
            } else {
                screenStatusText(active = active, snapshot = snapshot, report = report)
            }
        collection.lastTextReadStatus == RealtimeTextReadStatus.FAILED ->
            "实时感知中 · 无法确认目标应用窗口（后台采集未能确认属于第三方应用的窗口或事件）$ocrSuffix"
        collection.lastTextReadStatus == RealtimeTextReadStatus.EMPTY ->
            "实时感知中 · 已确认目标应用，但当前视口未读到文本（已保留滚动证据，等待滚动后按限频识别）$ocrSuffix"
        else -> "实时感知中 · 已读到无障碍文本（尚未形成文本池）$ocrSuffix"
    }
}

/** 把持续采集状态渲染为多行只读诊断文本（纯函数，供诊断页展示；**不含**完整 OCR 原文，也不写 Logcat）。 */
internal fun realtimeCollectionDiagnosticLines(status: RealtimeCollectionStatus): List<String> = buildList {
    add("实时采集: ${if (status.enabled) "已启用" else "未启用"}")
    add(
        "后台采集器: " + if (status.enabled) {
            "运行中（实时模式已启用，随无障碍事件与受控探测采集）"
        } else {
            "未运行（实时模式未启用，不会启动任何新的后台采集）"
        }
    )
    add("本地 OCR 能力: ${if (status.ocrAvailable) "可用" else "不可用（能力降级）"}")
    status.degradedNote?.let { add("能力降级说明: $it") }
    add("最近一次成功写入文本池: ${realtimeTimeText(status.lastUpdateAtMillis)}")
    add("最近一次被接受的观察（有效写入）: ${realtimeTimeText(status.lastObservedAtMillis)}")
    add(
        "最近一次采集提交: ${realtimeTimeText(status.lastSubmissionAtMillis)}" +
            "（结果 ${status.lastResult?.name ?: "-"} / 来源 ${status.lastSource?.name ?: "-"}）"
    )
    add("累计采集提交次数: ${status.updateCount}（每次无障碍观察 / OCR 提交均计数，含未读到文本与未能确认窗口的提交，**不代表成功写入次数**）")
    add("累计视口片段数量: ${status.fragmentCount}（上限 ${ScreenContextPool.DEFAULT_MAX_FRAGMENTS}）")
    add("当前题目上下文标识: ${status.questionKey ?: "-"}")
    add("当前题目题号: ${status.questionNumber?.let { "第 $it 题" } ?: "-"}")
    add("最近一次分类与合并结果: ${status.lastResult?.name ?: "-"}（来源 ${status.lastSource?.name ?: "-"}）")
    add("采集状态判定: ${realtimeCollectionStateText(status)}")
    status.lastNote?.let { add("最近一次结果说明: $it") }
    add("最近一次接收到的无障碍事件类型: ${status.lastEventKind?.name ?: "-"}")
    add("事件来源是否匹配目标窗口: " + when (status.lastEventFromTargetWindow) {
        null -> "-"
        true -> "是"
        false -> "否（本应用悬浮窗或其它窗口，其滚动已忽略，不当作刷题页面滚动）"
    })
    // 「当前视口没有读到文本」「后台采集没有运行」「最近一次 OCR 失败」三者必须分开显示，
    // 不能统一归为「未获取到屏幕内容」（见 lastTextReadStatus 的语义说明）。
    add("最近一次无障碍文本读取: " + when (status.lastTextReadStatus) {
        null -> "-（本次会话尚未收到任何无障碍事件）"
        RealtimeTextReadStatus.SUCCESS -> "成功（读到节点树文本）"
        RealtimeTextReadStatus.EMPTY -> "空文本（目标应用身份已确认，但本次视口没有可读文本；已保留滚动证据）"
        RealtimeTextReadStatus.FAILED -> "失败（未能确认有效的第三方目标应用窗口：窗口枚举与事件来源均无法判定归属）"
    })
    add("最近一次滚动信号: ${realtimeScrollDecisionText(status.lastScrollDecision)}")
    add("滚动位置是否可用: " + when (status.lastScrollDecision) {
        RealtimeScrollDecision.ADVANCED, RealtimeScrollDecision.REPEAT -> "可用（位置键见下）"
        RealtimeScrollDecision.UNKNOWN_LOCATION -> "未知（仅据「确实发生了滚动」按限频采集当前画面，不虚构位置）"
        RealtimeScrollDecision.NOT_SCROLL -> "-（未确认发生过滚动）"
    })
    status.lastScrollPositionKey?.let { add("最近一次滚动位置: $it") }
    add("最近一次 OCR 触发/门控: ${status.lastOcrGateNote ?: "-"}")
    add("最近一次 OCR 状态: ${status.lastOcrStatusNote ?: "-"}")
    add("（以上 OCR 统计均来自后台持续采集，与「最近一次手动发送请求」的识别结果相互独立）")
    add("最近一次 OCR 尝试: ${realtimeTimeText(status.lastOcrAtMillis)}（在途=${status.ocrInFlight}）")
    add(
        "采集观察序号: 当前 ${status.sequence} / 已接受 ${status.acceptedSequence}" +
            "（只有**真正改变池内容**的观察才推进「已接受」，因此重复上报不会顶掉在途 OCR 的序号）"
    )
    add("画面变化探测回退: ${realtimeProbeText(status)}")
    add(
        "目标应用最近一次滚动事件: ${realtimeTimeText(status.lastTargetScrollAtMillis)}" +
            "（收到滚动事件后在 ${status.probeScrollSilenceMillis} ms 内不启用画面探测）"
    )
    add(
        "当前文本池完整度: " + when (status.completeness) {
            QuestionCompleteness.COMPLETE -> "COMPLETE（已出现完整连续的选项结构）"
            QuestionCompleteness.INCOMPLETE -> "INCOMPLETE（尚未出现完整连续的选项结构）"
            QuestionCompleteness.UNKNOWN -> "UNKNOWN（尚未采集到任何视口片段）"
        }
    )
    add("当前文本池行数: ${status.poolLines.size}，字符数: ${status.poolText.length}")
}

/**
 * 把「最近一次采集」归入一个可读状态，便于诊断页一眼区分六类情况：
 * 无障碍文本带来新内容 / 文本未变但有有效滚动信号 / 文本未变且确认发生滚动但位置未知 /
 * OCR 已成功更新文本池 / OCR 失败或视口仍无法可靠合并（均不含题目正文）。
 */
private fun realtimeCollectionStateText(status: RealtimeCollectionStatus): String {
    val hasNewContent = status.lastResult == PoolUpdateResult.NEW_QUESTION ||
        status.lastResult == PoolUpdateResult.APPENDED ||
        status.lastResult == PoolUpdateResult.OVERLAP_MERGED
    val scrollConfirmed = status.lastScrollDecision == RealtimeScrollDecision.ADVANCED ||
        status.lastScrollDecision == RealtimeScrollDecision.UNKNOWN_LOCATION
    return when {
        !status.enabled -> "后台采集未运行（实时模式未启用）"
        status.lastTextReadStatus == null && status.lastResult == null ->
            "后台采集已启动，尚未收到任何无障碍事件"
        status.lastResult == PoolUpdateResult.OCR_FAILED &&
            status.lastTextReadStatus == RealtimeTextReadStatus.FAILED ->
            "未能确认目标应用：窗口枚举与事件来源均无法判定归属（与「当前视口没有文本」不同）"
        status.lastResult == PoolUpdateResult.OCR_FAILED &&
            status.lastTextReadStatus == RealtimeTextReadStatus.EMPTY && scrollConfirmed ->
            "已确认目标应用，但当前视口未读到无障碍文本，且确认发生了滚动（按限频进入 OCR）"
        status.lastResult == PoolUpdateResult.OCR_FAILED &&
            status.lastTextReadStatus == RealtimeTextReadStatus.EMPTY ->
            "已确认目标应用，但当前视口未读到无障碍文本（本次无滚动证据，暂不触发 OCR）"
        status.lastResult == PoolUpdateResult.NO_CHANGE &&
            status.lastScrollDecision == RealtimeScrollDecision.ADVANCED ->
            "无障碍文本未变化，但出现了有效的滚动信号"
        status.lastResult == PoolUpdateResult.NO_CHANGE &&
            status.lastScrollDecision == RealtimeScrollDecision.UNKNOWN_LOCATION ->
            "无障碍文本未变化，确认发生了滚动但事件未提供可靠位置（按限频探测当前画面）"
        status.lastSource == RealtimeCollectSource.LOCAL_OCR && hasNewContent ->
            "OCR 已启动并成功更新文本池"
        status.lastResult == PoolUpdateResult.OCR_FAILED -> "最近一次采集未读到有效文字（池内容未变）"
        status.lastResult == PoolUpdateResult.UNSTABLE -> "视口无法可靠合并（不确定，池内容保持原样）"
        hasNewContent -> "无障碍文本带来了新内容"
        else -> "-"
    }
}

/** 渲染「画面变化探测回退」（修复 B）的只读诊断说明。 */
private fun realtimeProbeText(status: RealtimeCollectionStatus): String {
    if (!status.enabled) return "未启用（实时模式关闭）"
    if (!status.ocrAvailable) return "未启用（本地 OCR 能力不可用）"
    val decision = when (status.lastProbeDecision) {
        null -> "尚无采样"
        ScreenProbeDecision.FIRST_SAMPLE -> "已建立基线（首次采样不触发 OCR）"
        ScreenProbeDecision.UNCHANGED -> "画面无实质变化（跳过 OCR）"
        ScreenProbeDecision.CHANGED -> "画面明显变化（进入 OCR 单飞 + 限频）"
    }
    return "采样间隔 ${status.probeIntervalMillis} ms；无目标滚动事件 ${status.probeScrollSilenceMillis} ms 后激活；" +
        "最近一次采样 ${realtimeTimeText(status.lastProbeAtMillis)}；判定 $decision"
}

private fun realtimeScrollDecisionText(decision: RealtimeScrollDecision): String = when (decision) {
    RealtimeScrollDecision.NOT_SCROLL -> "非滚动事件，或滚动来自非目标窗口（无法确认发生滚动）"
    RealtimeScrollDecision.UNKNOWN_LOCATION ->
        "滚动已发生、精确位置未知（允许限频触发 OCR，不虚构位置）"
    RealtimeScrollDecision.REPEAT -> "滚动位置未变化（重复分发，不触发 OCR）"
    RealtimeScrollDecision.ADVANCED -> "出现新的滚动位置"
}

private fun realtimeTimeText(millis: Long?): String =
    if (millis == null || millis <= 0L) "-"
    else SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(millis))
