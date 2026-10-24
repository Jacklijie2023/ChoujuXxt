package `fun`.kirari.hanako.screencontext

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/** 屏幕上下文的提取来源。 */
enum class ScreenContextSource {
    /** 无障碍节点树提取。 */
    ACCESSIBILITY,

    /** 本地 OCR 兜底提取。 */
    LOCAL_OCR,

    /**
     * 后台持续采集累计的同题快照（跨屏合并后的当前题目上下文）。
     *
     * 仅在「已绑定目标应用 + 池当前题同应用 + 当前视口可证明同题」时注入；
     * 与单次 [ACCESSIBILITY] / [LOCAL_OCR] 的即时读取区分，便于诊断区分「即时读到」与「累计快照」。
     */
    POOL_SNAPSHOT
}

/** 最近一次屏幕读取/发送的结果状态（供状态条与诊断展示）。 */
enum class ScreenReadStatus {
    /** 后台捕获到了屏幕内容（尚未发送）。 */
    CAPTURED,

    /** 发送路径已读取到有效的新鲜文本（尚未构造成最终模型请求）。 */
    CONTEXT_AVAILABLE,

    /** 最近一次发送已把上下文构造成最终模型请求的一部分，即将调用真实模型。 */
    SEND_INJECTED,

    /** 最近一次发送因缺少有效上下文而失败，未注入模型请求。 */
    SEND_FAILED
}

/**
 * 最近一次屏幕读取/发送的诊断快照（仅内存，不落盘，不含完整屏幕文本）。
 */
data class ScreenReadReport(
    val traceId: String,
    val status: ScreenReadStatus,
    val source: ScreenContextSource? = null,
    val targetPackage: String? = null,
    val windowType: Int? = null,
    val windowLayer: Int? = null,
    val windowActive: Boolean = false,
    val windowFocused: Boolean = false,
    val a11yEnabled: Boolean? = null,
    val a11yConnected: Boolean? = null,
    val windowValid: Boolean = false,
    val screenshotAttempted: Boolean? = null,
    val screenshotSucceeded: Boolean? = null,
    val captureError: String? = null,
    val bitmapWidth: Int? = null,
    val bitmapHeight: Int? = null,
    val ocrExecuted: Boolean? = null,
    val ocrNotAvailable: Boolean = false,
    val ocrError: String? = null,
    val ocrCharCount: Int? = null,
    /** OCR 识别出的非空行数（不含原始文本，用于对比同题重复识别是否波动）。 */
    val ocrLineCount: Int? = null,
    /** OCR 结果指纹（SHA-256 前 8 位十六进制，不可逆，用于判定两次识别是否为同一内容）。 */
    val ocrFingerprint: String? = null,
    /** 形如「A. / B、」的选项标记数量（不含选项文字，用于判断是否真的识别到题目选项）。 */
    val optionMarkerCount: Int? = null,
    /**
     * 选项标签与正文的关联可信度（[OcrOptionAssociation] 的名称；null 表示未判定）。
     *
     * 单独暴露该字段，是为了在标签数量看似足够、但顺序/归属并不可靠时，
     * 让诊断明确写出「不确定」，而不是把错误关联后的文本当作可靠选项。
     */
    val optionAssociation: String? = null,
    val charCount: Int = 0,
    val injected: Boolean = false,
    /**
     * 本地 OCR 引擎自身拼接出的**原始文本**（未经排序、未做选项关联）。
     *
     * 与 [injectedText]（结构化整理后的最终注入文本）成对保留，
     * 便于在本机诊断界面里逐字对比「整理过程有没有擅自增删文字」。
     */
    val rawOcrText: String? = null,
    /**
     * 本次请求实际注入模型前的屏幕文本（仅内存，不落盘、不写 Logcat）。
     *
     * 供本机诊断面板展示「真正送进模型的题目文本」，而不是再跑一遍 OCR 的结果，
     * 从而能区分「没有 OCR 结果」「OCR 有文字但选项缺失」「请求未使用该 OCR」三种情况。
     */
    val injectedText: String? = null,
    val failureStage: String? = null,
    val readAtMillis: Long,
    /**
     * 本次请求的**内容质量门控**结论（[ScreenContentQuality] 的名称；null 表示未判定）。
     *
     * R8.4 新增：`charCount > 0` 不等于「识别到题目」。真机粉笔资料分析页只读到 36 字外壳
     * （标题 / 题号进度 / 重复「材料」），却仍被当作当前题目注入，因此必须单独记录
     * 质量结论与原因码，让「字数为 0 / 只有壳文字 / 身份未确认」三种情况在诊断里可区分。
     */
    val contentQuality: String? = null,
    /**
     * 本次请求是否携带了**视觉输入**（截图本体）。
     *
     * false 表示模型只看到了文本（无障碍或本地 OCR），**不得**声称模型看到了屏幕截图；
     * 与请求构造时实际图片数量一致（本流程为纯文本路径，不含图片）。
     */
    val visualInput: Boolean? = null
)

/**
 * 计算实时感知状态条文案（纯函数，便于单元测试）。
 *
 * 规则：最近一次结果为发送失败时，优先展示失败原因，绝不用旧的成功快照覆盖；
 * 否则展示最近一次读取的来源与字数；无任何读取记录时回退到快照的粗略状态。
 */
internal fun screenStatusText(
    active: Boolean,
    snapshot: ScreenContextSnapshot?,
    report: ScreenReadReport?
): String {
    if (!active) return ""
    return when (report?.status) {
        ScreenReadStatus.SEND_FAILED ->
            "实时感知中 · 本次发送失败：${failureStageText(report.failureStage)}"
        ScreenReadStatus.SEND_INJECTED ->
            capturedLabel(report, "已注入")
        ScreenReadStatus.CONTEXT_AVAILABLE, ScreenReadStatus.CAPTURED ->
            capturedLabel(report, "已捕获")
        null -> if (snapshot?.text?.isNotBlank() == true) {
            "实时感知中 · 已捕获当前屏幕内容"
        } else {
            "实时感知中 · 未获取到屏幕内容"
        }
    }
}

private fun capturedLabel(report: ScreenReadReport, verb: String): String {
    val source = when (report.source) {
        ScreenContextSource.ACCESSIBILITY -> "无障碍"
        ScreenContextSource.LOCAL_OCR -> "本地OCR"
        ScreenContextSource.POOL_SNAPSHOT -> "同题累计快照"
        null -> "未知"
    }
    return if (report.charCount > 0) {
        "实时感知中 · $verb（$source · ${report.charCount} 字）"
    } else {
        "实时感知中 · $verb 当前屏幕内容"
    }
}

internal fun failureStageText(stage: String?): String = when (stage) {
    "a11y_disabled" -> "无障碍服务未启用"
    "a11y_not_connected" -> "无障碍服务未连接"
    "no_valid_window" -> "未找到有效的第三方应用窗口"
    "no_capture_session" -> "截图会话不可用，请先完成授权"
    "screenshot_not_authorized" -> "尚未获得屏幕截图授权"
    "capture_session_lost" -> "截图会话已在运行中失效"
    "manager_inactive" -> "实时上下文管理器未启动"
    "capture_failed" -> "屏幕截图执行失败"
    "frame_not_fresh" -> "截图未取到窗口隐藏后的新画面"
    "ocr_not_available" -> "本地 OCR 不可用"
    "ocr_failed" -> "本地识别失败"
    "ocr_empty" -> "未识别到屏幕文字"
    // R8.4：拿到了文字，但只有页面标题 / 导航 / 重复「材料」这类壳内容 → 不等于识别到题目。
    // R8.5：也包括「输入法键盘按键被拍进截图」（`GHI JKL MNO` 等）这一遮挡证据。
    "content_insufficient" -> "未识别出题目内容（只读到页面标题 / 导航 / 输入法键盘等非题目内容）"
    "target_identity_unconfirmed" -> "无法确认当前前台是已绑定的目标应用"
    else -> "当前屏幕内容暂时无法获取"
}

/** 将失败阶段映射为面向用户的聊天内提示（带操作指引）。 */
internal fun failureReasonText(stage: String?): String = when (stage) {
    "a11y_disabled" -> "无障碍服务未启用，请前往系统设置开启「Hanako」无障碍服务。"
    "a11y_not_connected" -> "无障碍服务尚未连接，请稍后重试或重新开启。"
    "no_valid_window" -> "未找到有效的第三方应用窗口，请回到题目页面后重试。"
    "no_capture_session" -> "当前截图会话不可用，请先完成屏幕截图授权。"
    // 「从未建立过会话」属于授权问题，「建立过但运行中消失」属于运行期故障，文案必须区分，
    // 不得把所有会话缺失都解释成「用户没有授权」。
    "screenshot_not_authorized" -> "实时互动尚未获得屏幕截图授权，请重新启动悬浮球并完成投屏授权。"
    "capture_session_lost" -> "屏幕截图会话已在运行中失效，请重新启动悬浮球重新授权。"
    "manager_inactive" -> "实时屏幕感知尚未启动，请重新开启实时互动模式。"
    // 截图执行失败属于运行期故障（线程/会话/编码等），不能据此断言权限缺失；
    // 权限相关的指引由 "no_capture_session" 承载。
    "capture_failed" -> "屏幕截图失败，请稍后重试。"
    "frame_not_fresh" -> "截图未取到 Hanako 窗口隐藏后的新画面，请稍后重试。"
    "ocr_not_available" -> "当前版本不支持本地文字识别，请切换到完整版使用。"
    "ocr_failed" -> "本地文字识别失败，请重试。"
    "ocr_empty" -> "截图成功但未识别到屏幕文字，请切换到题目页面后重试。"
    // R8.4：识别确实拿到了文字，但不足以构成当前题目（只有标题 / 导航 / 重复「材料」外壳）。
    // R8.5：输入法键盘按键（`GHI JKL MNO` 等）同样只说明「遮挡窗口被拍进截图」，不是题目。
    // 必须如实告知识别失败，绝不能把壳文字 / 键盘内容当成「已看到题目」。
    "content_insufficient" ->
        "未能识别出当前题目内容（本次只读到页面标题 / 导航等外壳文字或输入法键盘，题干与选项都没读到）。" +
            "请回到题目页面、让题目正文显示在屏幕上后重试。"
    // 绑定的目标应用不在前台时不得强行使用旧内容，也不得把其它应用的内容当作当前题目。
    "target_identity_unconfirmed" ->
        "无法确认当前前台应用是已绑定的目标应用，请切回题目应用（或重新绑定目标应用）后重试。"
    else -> "当前屏幕内容暂时无法获取，请切换回题目页面后重试。"
}

/**
 * 实时互动「运行期」门控（纯函数，便于单元测试）。
 *
 * [realTimeMode] 只是用户在首页选择的**模式偏好**（见 `homeModeTransform`），
 * 它并不代表实时互动服务（悬浮球 / 迷你窗宿主）已经启动；
 * 只有 [realTimeRuntimeRunning] 为 true（宿主确实在运行）时才允许进入屏幕读取。
 *
 * 因此「选了实时互动但没启动服务」时返回 false，普通聊天按普通请求处理，
 * 不会仅因模式选择本身触发屏幕上下文请求，也不会被误报成截图授权失败。
 */
internal fun shouldResolveScreenContext(realTimeMode: Boolean, realTimeRuntimeRunning: Boolean): Boolean =
    realTimeMode && realTimeRuntimeRunning

/**
 * 截图会话缺失时的失败阶段判定（纯函数，便于单元测试）。
 *
 * [sessionEverEstablished] 为 true 说明本进程内曾成功建立过会话，
 * 当前的缺失属于「运行期会话丢失」；为 false 才是「尚未授权」。
 * 两者不得共用同一条「请先完成授权」的提示。
 */
internal fun classifyCaptureMissingStage(sessionEverEstablished: Boolean): String =
    if (sessionEverEstablished) "capture_session_lost" else "screenshot_not_authorized"

/**
 * 判定本次 OCR 兜底的最终失败阶段（纯函数，便于单元测试）。
 *
 * [managerActive] 为 false 说明刷新因实时上下文管理器未启动而根本没执行；
 * [screenshotAttempted]/[screenshotSucceeded] 区分「截图是否真正执行」「截图是否成功」；
 * [ocrNotAvailable]/[ocrErrored] 区分「识别能力不可用」与「识别抛异常」，
 * 其余（截图成功、识别执行且无异常）即为「识别返回空文本」。
 *
 * [staleFrame] 用于把「截图没取到窗口隐藏后的新帧」与其它截图执行失败区分开：
 * 这类失败是画面新鲜度问题，不是会话/线程/编码故障，用户提示与排查方向都不同。
 */
internal fun classifyOcrFailureStage(
    managerActive: Boolean,
    screenshotAttempted: Boolean,
    screenshotSucceeded: Boolean,
    ocrNotAvailable: Boolean,
    ocrErrored: Boolean,
    staleFrame: Boolean = false
): String = when {
    !managerActive -> "manager_inactive"
    !screenshotAttempted -> "no_capture_session"
    !screenshotSucceeded -> if (staleFrame) "frame_not_fresh" else "capture_failed"
    ocrNotAvailable -> "ocr_not_available"
    ocrErrored -> "ocr_failed"
    else -> "ocr_empty"
}

/** 手机诊断面板中注入文本预览的最大展示长度（避免超长题面撑爆面板；完整文本仍留在内存报告中）。 */
internal const val PREVIEW_MAX_CHARS = 400

/**
 * 判定本机 OCR 预览应如何解释当前报告（纯函数，便于单元测试）。
 *
 * 关键点：**字符数大于零不等于题目识别成功**。因此这里必须区分：
 * - 没有任何读取记录；
 * - 有读取记录，但本次请求根本没有使用屏幕文本（读取到文字却没有注入）；
 * - 已注入，但选项标记为 0（题干/选项可能未完整识别，甚至根本没有识别到题目）；
 * - 已注入且识别到选项标记，或识别到文本但选项无法据此断言。
 */
internal fun screenContextPreviewDiagnosis(report: ScreenReadReport?): String {
    if (report == null) return "没有屏幕读取记录"
    val text = report.injectedText
    if (!report.injected || text.isNullOrBlank()) {
        return if (report.charCount > 0) {
            "本次请求未使用该屏幕文本（读取到 ${report.charCount} 字但未注入）"
        } else if (report.status == ScreenReadStatus.SEND_FAILED) {
            "本次请求未注入任何屏幕文本：${failureStageText(report.failureStage)}"
        } else {
            "没有 OCR 结果（未读取到可用屏幕文本）"
        }
    }
    val markers = report.optionMarkerCount ?: 0
    return when {
        markers <= 0 ->
            "已注入本次请求（${text.length} 字），但未识别到任何选项标记（A./B./C./D.、单独字母或圆圈选项）—— 题干或选项可能不完整，不能视为识别成功"
        report.optionAssociation == OcrOptionAssociation.PARTIAL.name ->
            "已注入本次请求（${text.length} 字，选项标记 $markers 个），但选项标签与正文的关联不确定，不能当作可靠选项"
        else -> "已注入本次请求（${text.length} 字，选项标记 $markers 个）"
    }
}

/** 本机查看「本次请求真正注入前的屏幕题目文本」（纯函数，仅供 UI 面板，不写 Logcat）。 */
internal fun screenContextPreviewLines(report: ScreenReadReport?): List<String> {
    if (report == null) return emptyList()
    val text = report.injectedText
    return buildList {
        add("预览判定: ${screenContextPreviewDiagnosis(report)}")
        if (text.isNullOrBlank()) {
            add("注入文本: （空）")
        } else {
            add("注入文本（前 $PREVIEW_MAX_CHARS 字）:")
            add(text.take(PREVIEW_MAX_CHARS))
            if (text.length > PREVIEW_MAX_CHARS) {
                add("…（共 ${text.length} 字，已截断展示）")
            }
        }
    }
}

/** 将一次读取诊断渲染为多行文本（供手机上可展开的诊断区域展示，不含完整屏幕文本与 API Key）。 */
internal fun screenReadReportLines(report: ScreenReadReport?): List<String> {
    if (report == null) return emptyList()
    return buildList {
        add("Trace: ${report.traceId.ifBlank { "-" }}")
        add("状态: ${report.status.name}")
        add("来源: ${report.source?.name ?: "-"}")
        add("目标包名: ${report.targetPackage ?: "-"}")
        add("窗口类型: ${report.windowType ?: "-"}（有效: ${report.windowValid}）")
        add("无障碍: 启用=${report.a11yEnabled ?: "-"} 连接=${report.a11yConnected ?: "-"}")
        add("截图: 执行=${report.screenshotAttempted ?: "-"} 成功=${report.screenshotSucceeded ?: "-"}")
        if (report.bitmapWidth != null && report.bitmapHeight != null) {
            add("位图尺寸: ${report.bitmapWidth}x${report.bitmapHeight}")
        }
        add("OCR: 执行=${report.ocrExecuted ?: "-"} 识别字符=${report.ocrCharCount ?: "-"}")
        if (report.ocrLineCount != null || report.optionMarkerCount != null) {
            add("OCR 明细: 行数=${report.ocrLineCount ?: "-"} 选项标记=${report.optionMarkerCount ?: "-"}")
        }
        report.optionAssociation?.let { add("选项关联: $it") }
        report.ocrFingerprint?.let { add("OCR 指纹: $it") }
        addAll(screenContextPreviewLines(report))
        add("失败阶段: ${report.failureStage ?: "-"}")
        add("是否注入: ${report.injected}")
        add("读取时间: ${formatReportTime(report.readAtMillis)}")
    }
}

/** 将一次读取的时间戳渲染为手机可读的时钟时间（纯函数，便于单元测试）。 */
private fun formatReportTime(millis: Long): String {
    if (millis <= 0L) return "-"
    return java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
}

/**
 * 判断诊断面板中的屏幕读取报告是否属于「当前这次发送」（纯函数，便于单元测试）。
 *
 * [currentTraceId] 为当前会话最近一条助手消息 id（即本次发送的请求追踪 ID，见 `ChatSessionHolder`）。
 * 报告为空、traceId 空白（后台捕获）或与当前发送不一致时，一律视为「最近一次读取，非本次发送」，
 * 避免用户把上一次请求遗留的旧报告误当成本次发送的结果。
 */
internal fun reportBelongsToCurrentSend(report: ScreenReadReport?, currentTraceId: String?): Boolean {
    if (report == null) return false
    val trace = report.traceId
    return trace.isNotBlank() && currentTraceId != null && trace == currentTraceId
}

/**
 * 最新屏幕上下文快照。
 *
 * 仅保存在内存中（[ScreenContextManager] 内部 StateFlow），不落盘、不写入日志，
 * 不因每次屏幕变化持久化到聊天历史或文件。
 */
data class ScreenContextSnapshot(
    val text: String,
    val sourceApp: String?,
    val updatedAtMillis: Long,
    val source: ScreenContextSource,
    val textFingerprint: String
)

/**
 * 本地屏幕上下文管理器。
 *
 * 职责：
 * - 保存最新屏幕上下文快照，可观察（[snapshot] StateFlow）。
 * - 提供 [start] / [stop] / [currentText] / [refreshViaOcr] 等统一接口。
 * - 仅在实时模式开启（[active] 为 true）时接收更新。
 * - 通过指纹去重与 OCR 节流，避免高频事件重复遍历或逐帧 OCR。
 *
 * 本类不含任何 Android 依赖，核心逻辑可在本地单元测试中直接验证。
 */
class ScreenContextManager(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxTextLength: Int = DEFAULT_MAX_TEXT_LENGTH,
    private val minOcrRefreshIntervalMillis: Long = DEFAULT_MIN_OCR_REFRESH_INTERVAL_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _snapshot = MutableStateFlow<ScreenContextSnapshot?>(null)
    val snapshot: StateFlow<ScreenContextSnapshot?> = _snapshot.asStateFlow()

    private val _lastReport = MutableStateFlow<ScreenReadReport?>(null)
    val lastReport: StateFlow<ScreenReadReport?> = _lastReport.asStateFlow()

    private var lastOcrRefreshAtMillis: Long? = null

    /**
     * 请求级诊断快照（turnTraceId → 该次请求实际使用的 [ScreenReadReport]）。
     *
     * [lastReport] 只保留「最近一次」读取/发送，无法回答「上一次请求当时到底注入了什么文本」；
     * 而排查 OCR 质量与切题残留恰恰需要按请求回溯。这里按 traceId 保存本次请求的报告
     * （含 [ScreenReadReport.injectedText] 的**完整**文本），有上限、仅内存、不落盘、不写 Logcat。
     *
     * 只登记 traceId 非空的报告：后台捕获类报告的 traceId 为空串（见 [capturedReport]），
     * 不应被当作某次请求的结果，否则会把「最近一次读取」误绑到某次发送上。
     */
    private val requestReports = LinkedHashMap<String, ScreenReadReport>()
    private val requestReportsLock = Any()

    /** 记录一次屏幕读取/发送结果（供状态条、诊断展示与「按请求回溯完整文本」使用）。 */
    fun recordRead(report: ScreenReadReport) {
        _lastReport.value = report
        val trace = report.traceId
        if (trace.isNotBlank()) {
            synchronized(requestReportsLock) {
                requestReports.remove(trace)
                requestReports[trace] = report
                while (requestReports.size > REQUEST_REPORT_LIMIT) {
                    val oldest = requestReports.keys.firstOrNull() ?: break
                    requestReports.remove(oldest)
                }
            }
        }
    }

    /**
     * 查询某次请求（[traceId]）实际使用的屏幕读取报告。
     *
     * 无记录、[traceId] 为空或空白时一律返回 null——调用方必须据此显示「本次请求未注入」状态，
     * **不得**回退到当前屏幕上下文或任何旧文本冒充本次结果。
     */
    fun reportFor(traceId: String?): ScreenReadReport? {
        if (traceId.isNullOrBlank()) return null
        return synchronized(requestReportsLock) { requestReports[traceId] }
    }


    /** 开启持续观察（由实时模式开关驱动）。 */
    fun start() {
        _active.value = true
    }

    /** 关闭持续观察并释放内存快照。 */
    fun stop() {
        _active.value = false
        _snapshot.value = null
        _lastReport.value = null
        lastOcrRefreshAtMillis = null
        synchronized(requestReportsLock) { requestReports.clear() }
    }

    /** 无障碍事件驱动的上下文更新。 */
    fun updateFromAccessibility(rawText: String, sourceApp: String?) =
        update(rawText, sourceApp, ScreenContextSource.ACCESSIBILITY)

    /** 本地 OCR 兜底的上下文更新。 */
    fun updateFromOcr(rawText: String, sourceApp: String?) =
        update(rawText, sourceApp, ScreenContextSource.LOCAL_OCR)

    private fun update(rawText: String, sourceApp: String?, source: ScreenContextSource) {
        if (!_active.value) return
        val normalized = normalizeScreenText(rawText, maxTextLength)
        if (normalized.isBlank()) return
        val now = clock()
        val current = _snapshot.value
        if (current != null && current.text == normalized && current.source == source && current.sourceApp == sourceApp) {
            // 相同内容被再次有效观察：文本不变，仅刷新有效性时间，
            // 避免缓存仅因超过 TTL 就把同屏内容误判为不可用。
            _snapshot.value = current.copy(updatedAtMillis = now)
            _lastReport.value = capturedReport(source, sourceApp, normalized.length, now)
            return
        }
        _snapshot.value = ScreenContextSnapshot(normalized, sourceApp, now, source, fingerprintOf(normalized))
        _lastReport.value = capturedReport(source, sourceApp, normalized.length, now)
    }

    private fun capturedReport(
        source: ScreenContextSource,
        targetPackage: String?,
        charCount: Int,
        now: Long
    ): ScreenReadReport = ScreenReadReport(
        traceId = "",
        status = ScreenReadStatus.CAPTURED,
        source = source,
        targetPackage = targetPackage,
        charCount = charCount,
        readAtMillis = now
    )

    /** 返回当前有效（未过期）的上下文文本；未捕获或已过期返回 null。 */
    fun currentText(nowMillis: Long = clock()): String? {
        val s = _snapshot.value ?: return null
        if (nowMillis - s.updatedAtMillis > ttlMillis) return null
        return s.text
    }

    /** 当前上下文是否已过期（含尚未捕获任何上下文）。 */
    fun isExpired(nowMillis: Long = clock()): Boolean = currentText(nowMillis) == null

    /**
     * 通过本地 OCR 兜底刷新一次上下文（受最小间隔节流）。
     *
     * [allowCached] 为 true 时（默认），若既有上下文仍在 TTL 内可直接复用，避免重复 OCR；
     * 为 false 时表示「必须读取当前屏幕」，绝不回退到仅凭 TTL 判定的旧缓存——用于发送指令时
     * 无障碍窗口读取失败、无法确认缓存仍对应当前屏幕的场景，避免把上一道题误注入模型。
     * 失败返回 null（调用方据此判断信息不足）。
     */
    suspend fun refreshViaOcr(
        allowCached: Boolean = true,
        ocr: suspend () -> String?
    ): String? {
        if (!_active.value) return null
        val now = clock()
        if (allowCached) {
            currentText(now)?.let { return it }
        }
        val last = lastOcrRefreshAtMillis
        // 仅后台复用类调用（allowCached=true）才受最小间隔节流；用户主动发送
        // （allowCached=false）必须执行一次可靠的新鲜读取，不能被刚结束的读取限流。
        if (allowCached && last != null && now - last < minOcrRefreshIntervalMillis) {
            return currentText(now)
        }
        lastOcrRefreshAtMillis = now
        val raw = try {
            ocr()
        } catch (_: Throwable) {
            null
        }
        val normalized = raw
            ?.let { normalizeScreenText(it, maxTextLength) }
            ?.takeIf { it.isNotBlank() }
        if (normalized != null) {
            updateFromOcr(normalized, null)
            return normalized
        }
        return if (allowCached) currentText(now) else null
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 30_000L
        const val DEFAULT_MAX_TEXT_LENGTH = 6000
        const val DEFAULT_MIN_OCR_REFRESH_INTERVAL_MILLIS = 2_000L

        /** 请求级诊断快照的内存上限（超出后淘汰最早的一条，避免长期运行无限增长）。 */
        const val REQUEST_REPORT_LIMIT = 16
    }
}

/** 规范化屏幕文本：去除首尾空白、折叠空行、限制最大长度。 */
internal fun normalizeScreenText(raw: String, maxLength: Int): String {
    val cleaned = raw
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()
    return if (cleaned.length <= maxLength) cleaned else cleaned.take(maxLength)
}

/** 计算文本指纹（SHA-256），用于内容变更去重。 */
internal fun fingerprintOf(normalized: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/**
 * OCR 结果指纹：截断到 8 位十六进制（不可逆、不泄漏原文）。
 * 用于判断同一道题重复识别时两次结果是否为同一内容。
 */
internal fun ocrFingerprintOf(raw: String): String = fingerprintOf(raw).take(8)

/** OCR 识别出的非空行数（纯函数）。 */
internal fun countScreenTextLines(raw: String): Int = raw.split('\n').count { it.isNotBlank() }

/**
 * 统计形如「A. / B、 / C：」的选项标记数量（纯函数，仅计数不含选项文字）。
 *
 * 用于判断本次 OCR 是否真的读到了题目选项：只有字符数、没有选项标记时，
 * 通常意味着识别到的仍是界面文字碎片而非题目本身。
 */
internal fun countOptionMarkers(raw: String): Int =
    raw.split('\n').count { OPTION_MARKER_REGEX.containsMatchIn(it) }

private val OPTION_MARKER_REGEX = Regex("^\\s*[A-Da-d]\\s*[.、．:：)）]")