package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.delay

/**
 * 网络请求重试辅助类
 */
object RetryHelper {
    private const val TAG = "RetryHelper"

    /**
     * 带重试的执行
     * @param maxRetries 最大重试次数
     * @param delayMs 重试间隔（毫秒）
     * @param onRetry 重试时的回调
     * @param block 要执行的操作
     */
    suspend fun <T> execute(
        maxRetries: Int = 3,
        delayMs: Long = 1000L,
        onRetry: ((attempt: Int, error: Throwable) -> Unit)? = null,
        block: suspend () -> T
    ): T {
        var lastException: Throwable? = null
        for (attempt in 1..maxRetries) {
            try {
                return block()
            } catch (e: Throwable) {
                lastException = e
                if (attempt < maxRetries) {
                    AppDebugLogStore.w(TAG, "Attempt $attempt failed, retrying in ${delayMs}ms: ${e.message}")
                    onRetry?.invoke(attempt, e)
                    delay(delayMs * attempt) // 递增延迟
                } else {
                    AppDebugLogStore.e(TAG, "All $maxRetries attempts failed", e)
                }
            }
        }
        throw lastException!!
    }

    /**
     * 判断是否为可重试的错误
     */
    fun isRetryable(error: Throwable): Boolean {
        val message = error.message?.lowercase() ?: return false
        return message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("network") ||
                message.contains("socket") ||
                message.contains("503") ||
                message.contains("429") ||
                message.contains("502")
    }
}
