package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class SseStreamClient(
    private val client: OkHttpClient
) {
    private val tag = "HanakoSseClient"

    internal data class StreamEventResult(
        val delta: String? = null,
        /**
         * 本事件是否表示「服务端已开始输出」（含推理增量）。
         *
         * 用于取消首字定时器：只要服务端真的在输出，就不应把这条请求判成「首字延迟超时」，
         * 从而实现「零事件 / 仅推理、无正文 / 正常正文」三态的真实区分。
         */
        val received: Boolean = false,
        val done: Boolean = false
    )

    suspend fun stream(
        request: Request,
        traceId: String? = null,
        firstDeltaTimeoutMillis: Long = 10_000L,
        onEvent: (eventSource: EventSource, type: String?, id: String?, data: String) -> StreamEventResult?,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val trace = traceId ?: "-"
        val startNanos = System.nanoTime()
        // 最远到达阶段：由下方阶段探针更新，供超时阶段分类使用。
        val stage = AtomicReference(SseRequestStage.UNKNOWN)

        fun elapsedMonotonicMs(): Long = (System.nanoTime() - startNanos) / 1_000_000

        // 阶段探针：
        // okhttp-sse 4.12 的 RealEventSource.connect() 会执行 newBuilder().eventListener(EventListener.NONE)，
        // 把外部注册的 EventListener 强制覆盖；而 newBuilder 会复制 Interceptor。
        // 因此探针只能用 Interceptor：应用拦截器观察「已进入调用链」，
        // 网络拦截器观察「连接已建立、请求头发出」与「响应头到达」，这三段是定位卡点的关键证据。
        val chainProbe = Interceptor { chain ->
            stage.set(SseRequestStage.CALL_START)
            AppDebugLogStore.i(
                tag,
                "trace=$trace stage=chain-enter elapsedMonotonicMs=${elapsedMonotonicMs()} url=${request.url}"
            )
            chain.proceed(chain.request())
        }
        val networkProbe = Interceptor { chain ->
            stage.set(SseRequestStage.REQUEST_HEADERS_START)
            AppDebugLogStore.i(
                tag,
                "trace=$trace stage=connected-request-headers elapsedMonotonicMs=${elapsedMonotonicMs()} url=${request.url}"
            )
            val response = chain.proceed(chain.request())
            stage.set(SseRequestStage.RESPONSE_HEADERS_END)
            AppDebugLogStore.i(
                tag,
                "trace=$trace stage=response-headers-end(code=${response.code}) elapsedMonotonicMs=${elapsedMonotonicMs()} url=${request.url}"
            )
            response
        }
        val probeClient = client.newBuilder()
            .addInterceptor(chainProbe)
            .addNetworkInterceptor(networkProbe)
            .build()

        suspendCancellableCoroutine { cont ->
            val builder = StringBuilder()
            val finished = AtomicBoolean(false)
            val receivedOutput = AtomicBoolean(false)
            val timerFired = AtomicBoolean(false)
            var eventCount = 0
            var eventSourceRef: EventSource? = null
            var timeoutJobRef: Job? = null

            fun finish(block: () -> Unit) {
                if (finished.compareAndSet(false, true)) {
                    timeoutJobRef?.cancel()
                    eventSourceRef?.cancel()
                    block()
                }
            }

            /** 服务端一旦开始输出（正文或推理），即取消首字定时器。 */
            fun markOutputReceived() {
                if (receivedOutput.compareAndSet(false, true)) {
                    timeoutJobRef?.cancel()
                }
            }

            fun stageLabel(): String = stage.get().name.lowercase().replace('_', '-')

            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    // 响应头阶段已由网络拦截器推进到 RESPONSE_HEADERS_END，此处只记录打开事件，不回退阶段。
                    AppDebugLogStore.i(tag, "trace=$trace stream opened code=${response.code} url=${request.url}")
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    try {
                        eventCount += 1
                        if (eventCount <= 5 || eventCount % 25 == 0) {
                            AppDebugLogStore.d(tag, "trace=$trace stream event#$eventCount type=$type id=$id dataLength=${data.length}")
                        }
                        val result = onEvent(eventSource, type, id, data)
                        val delta = result?.delta
                        if (!delta.isNullOrEmpty()) {
                            markOutputReceived()
                            builder.append(delta)
                            onDelta(delta)
                        }
                        if (result?.received == true) {
                            markOutputReceived()
                        }
                        if (result?.done == true) {
                            AppDebugLogStore.i(tag, "trace=$trace stream completed by protocol signal totalEvents=$eventCount outputLength=${builder.length} url=${request.url}")
                            finish { cont.resume(builder.toString()) }
                        }
                    } catch (t: Throwable) {
                        finish { cont.resumeWithException(t) }
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    response?.close()
                    val phase = streamTimeoutPhaseLabel(stage.get(), eventCount, receivedOutput.get())
                    val lateAfterFinish = isLateCancellationAfterFinish(finished.get())
                    // 真实 HTTP / 网络 / 解析错误必须保持原始原因（含状态码），不得统一伪装成首字延迟超时。
                    AppDebugLogStore.e(
                        tag,
                        "trace=$trace stream failure phase=$phase stage=${stageLabel()} " +
                            "code=${response?.code} firstDeltaTimerFired=${timerFired.get()} " +
                            "lateAfterFinish=$lateAfterFinish msg=${t?.message ?: "unknown"} url=${request.url}",
                        t
                    )
                    finish {
                        cont.resumeWithException(
                            t ?: IllegalStateException("Stream failed: ${response?.code ?: "unknown"}")
                        )
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    AppDebugLogStore.i(tag, "trace=$trace stream closed totalEvents=$eventCount outputLength=${builder.length} url=${request.url}")
                    finish { cont.resume(builder.toString()) }
                }
            }

            // 先注册首字定时器、再派发请求：本方法运行在 withContext 内，
            // 若「请求根本未进入网络调用」导致 onFailure 在 newEventSource 内部**同步**回调，
            // 此时必须已经有 timeoutJobRef 可供 finish() 取消；否则该定时器将空跑满超时时间，
            // 使调用方（结构化并发）被迫等待，违背「未入网必须立即上报」的语义。
            val timeoutJob = CoroutineScope(cont.context).launch {
                delay(firstDeltaTimeoutMillis)
                // 请求已终止或服务端已开始输出时直接退出，避免 dedupe 前抖动出多余的超时日志。
                if (finished.get() || receivedOutput.get()) return@launch
                val phase = streamTimeoutPhaseLabel(stage.get(), eventCount, receivedOutput.get())
                timerFired.set(true)
                AppDebugLogStore.e(
                    tag,
                    "trace=$trace stream first output timeout phase=$phase stage=${stageLabel()} " +
                        "eventCount=$eventCount timeoutMs=$firstDeltaTimeoutMillis firstDeltaTimerFired=true url=${request.url}",
                    null
                )
                finish {
                    cont.resumeWithException(
                        firstDeltaTimeoutException(phase, stageLabel(), firstDeltaTimeoutMillis)
                    )
                }
            }
            timeoutJobRef = timeoutJob
            val eventSource = EventSources.createFactory(probeClient).newEventSource(request, listener)
            eventSourceRef = eventSource
            // 若定时器抢先命中（或已同步失败），创建出的 EventSource 需立即取消，避免泄漏。
            if (finished.get()) eventSource.cancel()
            cont.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    timeoutJob.cancel()
                    eventSource.cancel()
                }
            }
        }
    }
}
