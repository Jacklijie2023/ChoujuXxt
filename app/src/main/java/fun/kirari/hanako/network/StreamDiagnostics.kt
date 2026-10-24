package `fun`.kirari.hanako.network

import java.net.SocketTimeoutException

/**
 * SSE 请求「最远真实到达的网络阶段」（纯逻辑枚举，供超时阶段分类使用）。
 *
 * 说明：okhttp-sse 的 RealEventSource 会把外部注册的 OkHttp EventListener 强制覆盖为 NONE，
 * 因此阶段探针只能用 OkHttp Interceptor（newBuilder 会复制）实现，可观测到的最细粒度即
 * 「已进入调用链」「连接已建立、请求头发出」「响应头到达」三段；DNS/TLS 等更细阶段在此按
 * 语义归类，便于纯逻辑测试穷举覆盖每一个真实阶段。
 */
internal enum class SseRequestStage {
    UNKNOWN,
    CALL_START,
    DNS_START,
    DNS_END,
    CONNECT_START,
    CONNECT_END,
    TLS_START,
    TLS_END,
    REQUEST_HEADERS_START,
    REQUEST_HEADERS_END,
    RESPONSE_HEADERS_START,
    RESPONSE_HEADERS_END
}

/** 阶段分类标签：请求尚未真正进入网络调用。 */
internal const val SSE_PHASE_NOT_DISPATCHED = "not-dispatched"

/** 阶段分类标签：DNS 解析。 */
internal const val SSE_PHASE_DNS = "dns"

/** 阶段分类标签：TCP 建连。 */
internal const val SSE_PHASE_CONNECT = "connect"

/** 阶段分类标签：TLS 握手。 */
internal const val SSE_PHASE_TLS = "tls"

/** 阶段分类标签：请求已发出、等待 HTTP 响应头。 */
internal const val SSE_PHASE_AWAITING_RESPONSE_HEADERS = "awaiting-response-headers"

/** 阶段分类标签：响应头已到、等待首个 SSE 事件。 */
internal const val SSE_PHASE_AWAITING_FIRST_EVENT = "awaiting-first-event"

/** 阶段分类标签：已有 SSE 事件、但还没有任何正文。 */
internal const val SSE_PHASE_AWAITING_FIRST_DELTA = "awaiting-first-delta"

/** 阶段分类标签：服务端已开始输出（正常情形，不应由定时器中断）。 */
internal const val SSE_PHASE_COMPLETED = "completed"

/**
 * 把「最远到达阶段 + 已收到的 SSE 事件数 + 是否已有正文」映射为超时阶段分类标签。
 *
 * 保证：请求未真正进入网络调用绝不伪装成网络超时；响应头未到达绝不写成「首字延迟超时」。
 */
internal fun streamTimeoutPhaseLabel(stage: SseRequestStage, eventCount: Int, hasDelta: Boolean): String =
    when (stage) {
        SseRequestStage.UNKNOWN, SseRequestStage.CALL_START -> SSE_PHASE_NOT_DISPATCHED
        SseRequestStage.DNS_START -> SSE_PHASE_DNS
        SseRequestStage.DNS_END,
        SseRequestStage.CONNECT_START,
        SseRequestStage.CONNECT_END -> SSE_PHASE_CONNECT

        SseRequestStage.TLS_START, SseRequestStage.TLS_END -> SSE_PHASE_TLS
        SseRequestStage.REQUEST_HEADERS_START,
        SseRequestStage.REQUEST_HEADERS_END -> SSE_PHASE_AWAITING_RESPONSE_HEADERS

        SseRequestStage.RESPONSE_HEADERS_START -> SSE_PHASE_AWAITING_FIRST_EVENT
        SseRequestStage.RESPONSE_HEADERS_END -> when {
            hasDelta -> SSE_PHASE_COMPLETED
            eventCount > 0 -> SSE_PHASE_AWAITING_FIRST_DELTA
            else -> SSE_PHASE_AWAITING_FIRST_EVENT
        }
    }

/**
 * 依据超时真正到达的阶段生成可诊断的异常：
 * - 请求未进入网络调用 → [IllegalStateException]（不得伪装成网络超时）；
 * - 未收到 HTTP 响应头 → [SocketTimeoutException]（不得写成「首字延迟超时」）；
 * - 未收到首个 SSE 事件 / 已收到事件但未收到正文 → 「首字延迟超时」并按阶段区分。
 *
 * 不足 1 秒的超时以毫秒显示，避免出现「等待 0 秒」这种无法定位的文案。
 */
internal fun firstDeltaTimeoutException(phase: String, stageLabel: String, timeoutMillis: Long): Throwable {
    val waitText = if (timeoutMillis >= 1000) "${timeoutMillis / 1000} 秒" else "${timeoutMillis}ms"
    return when (phase) {
        SSE_PHASE_NOT_DISPATCHED ->
            IllegalStateException("流式请求未进入网络调用（阶段=$stageLabel），未收到任何响应")

        SSE_PHASE_AWAITING_RESPONSE_HEADERS ->
            SocketTimeoutException("等待 $waitText 后仍未收到 HTTP 响应头（阶段=$stageLabel）")

        SSE_PHASE_AWAITING_FIRST_EVENT ->
            SocketTimeoutException("首字延迟超时：等待 $waitText 后仍未收到首个 SSE 事件（阶段=$stageLabel）")

        SSE_PHASE_AWAITING_FIRST_DELTA ->
            SocketTimeoutException("首字延迟超时：已收到 SSE 事件但未收到正文（等待 $waitText，阶段=$stageLabel）")

        else ->
            SocketTimeoutException("首字延迟超时：等待 $waitText（阶段=$stageLabel）")
    }
}

/**
 * 请求已正常终止（finished=true）后的迟到取消回调应被忽略；
 * 终止前的真实 HTTP/网络/解析错误必须照常上报。
 */
internal fun isLateCancellationAfterFinish(alreadyFinished: Boolean): Boolean = alreadyFinished

/**
 * 模型只返回推理内容（如 DeepSeek reasoning_content）、未返回最终正文时的可诊断专用错误。
 *
 * 不得把推理内容伪装成最终答案，也不得统一降级为「首字延迟超时」。
 */
internal class ReasoningOnlyStreamException(message: String) : Exception(message)

/**
 * 流式诊断采集器：承载 [LlmEvent] 无法表达的信号（尤其是推理增量）——
 * 由适配器在收到服务端增量时回填，供 [UnifiedLLMClient] 汇总成单条耗时摘要。
 *
 * 单独抽出而非给 [LlmEvent] 增加子类型：推理内容既不是最终答案、也无 UI 消费方，
 * 不应污染事件协议（避免迫使其它包的事件分支同步改动）。
 * 仅用于诊断，不参与任何业务逻辑；每个请求独立一份。
 */
internal class StreamDiagnostics {
    /** 首个被观测到的服务端 SSE 事件的 nanoTime。 */
    @Volatile
    var firstEventNanos: Long? = null

    /** 首个推理增量到达的 nanoTime。 */
    @Volatile
    var firstReasoningNanos: Long? = null

    /** 推理增量个数。 */
    @Volatile
    var reasoningChunks: Int = 0

    /** 记录一次「服务端首个事件」。仅首个生效。 */
    fun markEvent(nanos: Long) {
        if (firstEventNanos == null) firstEventNanos = nanos
    }

    /** 记录一次推理增量（个数 + 首个到达时刻）。 */
    fun markReasoning(nanos: Long) {
        if (firstReasoningNanos == null) firstReasoningNanos = nanos
        reasoningChunks += 1
    }
}

/**
 * 思考模式流式耗时摘要（纯函数，便于单元测试）。
 *
 * 各阶段耗时均以 [startNanos] 为基准、毫秒整数截断（`(a-b)/1_000_000`）；缺失阶段记为 -1。
 */
internal fun streamTimingSummary(
    traceId: String?,
    startNanos: Long,
    endNanos: Long,
    firstEventNanos: Long?,
    firstReasoningNanos: Long?,
    firstContentNanos: Long?,
    reasoningChunks: Int,
    contentChars: Int
): String {
    fun elapsedMs(nanos: Long?): Long = nanos?.let { (it - startNanos) / 1_000_000 } ?: -1L
    val totalMs = (endNanos - startNanos) / 1_000_000
    return "trace=${traceId ?: "-"} firstEventMs=${elapsedMs(firstEventNanos)} " +
        "firstReasoningMs=${elapsedMs(firstReasoningNanos)} firstContentMs=${elapsedMs(firstContentNanos)} " +
        "totalMs=$totalMs reasoningChunks=$reasoningChunks contentChars=$contentChars"
}
