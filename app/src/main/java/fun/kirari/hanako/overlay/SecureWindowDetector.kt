package `fun`.kirari.hanako.overlay

import android.os.RemoteException
import `fun`.kirari.hanako.capture.ShizukuUserServiceClient
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 检测外部 FLAG_SECURE 窗口数量。
 * 通过 Shizuku 执行 `dumpsys window windows`，统计带有 SECURE 标志的窗口。
 * 排除自身包名 fun.kirari.hanako 的窗口。
 */
internal object SecureWindowDetector {
    private const val TAG = "SecureWindowDetector"
    private const val OWN_PACKAGE = "fun.kirari.hanako"

    /**
     * 获取外部 FLAG_SECURE 窗口数量（排除自身）。
     * @return 安全窗口数量，-1 表示 Shizuku 不可用或执行失败
     */
    suspend fun getExternalSecureWindowCount(): Int = withContext(Dispatchers.IO) {
        try {
            val service = ShizukuUserServiceClient.acquire()
            val rawBytes = service.exec(arrayOf("sh", "-c", "dumpsys window windows"))
            val stdout = rawBytes?.let { String(it, Charsets.UTF_8) } ?: return@withContext -1
            countSecureWindows(stdout)
        } catch (e: RemoteException) {
            AppDebugLogStore.e(TAG, "Shizuku remote exception", e)
            ShizukuUserServiceClient.invalidate()
            -1
        } catch (e: Exception) {
            AppDebugLogStore.e(TAG, "Failed to check secure windows", e)
            -1
        }
    }

    /**
     * 统计 dumpsys window windows 输出中 FLAG_SECURE 窗口数量。
     * 逻辑：找到以 "mOw" 开头的行，如果包含自身包名则跳过后续 3 行；
     * 否则检查后续行中以 "fl" 开头且包含 "SECURE" 的行。
     */
    private fun countSecureWindows(output: CharSequence): Int {
        var count = 0
        var i = 0
        val len = output.length
        var skipNextLines = 0

        while (i < len) {
            // 跳过换行符
            while (i < len && (output[i] == '\n' || output[i] == '\r')) i++
            if (i >= len) break

            val lineStart = i
            while (i < len && output[i] != '\n' && output[i] != '\r') i++

            if (skipNextLines > 0) {
                skipNextLines--
                continue
            }

            // 跳过行首空白
            var start = lineStart
            while (start < i && output[start].isWhitespace()) start++

            // 检查是否以 "mOw" 开头（WindowState 的 mOwnerUid 行）
            if (start + 3 <= i &&
                output[start] == 'm' && output[start + 1] == 'O' && output[start + 2] == 'w'
            ) {
                // 检查是否包含自身包名
                if (containsSubstring(output, start, i, OWN_PACKAGE)) {
                    skipNextLines = 3 // 跳过后续 3 行（自己的窗口）
                    continue
                }
            }

            // 检查是否以 "fl" 开头
            if (start + 2 <= i && output[start] == 'f' && output[start + 1] == 'l') {
                if (containsSubstring(output, start, i, "SECURE")) {
                    count++
                }
            }
        }
        return count
    }

    private fun containsSubstring(cs: CharSequence, start: Int, end: Int, target: String): Boolean {
        val targetLen = target.length
        for (idx in start..end - targetLen) {
            var match = true
            for (j in 0 until targetLen) {
                if (cs[idx + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }
}
