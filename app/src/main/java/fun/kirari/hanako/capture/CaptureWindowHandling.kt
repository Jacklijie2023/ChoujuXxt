package `fun`.kirari.hanako.capture

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 截图期间 Hanako 自有窗口（悬浮球 / 分析面板 / 迷你聊天窗）的可见性记账。
 *
 * 职责：在截图前记录「当前真正可见且仍挂在窗口系统上的窗口」，把它们临时隐藏；
 * 截图结束后**只恢复原本次前可见的窗口**，绝不把原本就隐藏的窗口强制显示，
 * 也绝不去改写已经从窗口系统移除（截图期间被关闭）的窗口。
 *
 * 与 Android View 解耦：可见性 / 挂载状态 / 写回可见性全部由调用方以函数注入，
 * 因此可在纯 JVM 单元测试中验证。
 */
internal class CaptureWindowVisibility<T : Any>(
    private val isVisible: (T) -> Boolean,
    private val isAttached: (T) -> Boolean,
    private val setVisible: (T, Boolean) -> Unit
) {
    /**
     * 本次截图前可见、已记录原状并在隐藏中的窗口 —— 键为窗口，值为该窗口的**原始可见性**。
     *
     * 恢复时按此处的原始值写回，而不是无条件设为可见；快照在 [restoreAll] 结束时清空，
     * 下一次 [hideAll] 也会先清空，因此任何失败路径都不会把旧快照用于下一次截图。
     */
    private val hiddenWindows = LinkedHashMap<T, Boolean>()

    /**
     * 记录并隐藏所有「可见且已挂载」的窗口，返回本次**被记录进待恢复集合的窗口数**。
     *
     * 记录动作先于状态改写：因此即便 [setVisible] 在某个窗口上中途抛异常，
     * 此前已记录（含正在改写的那一个）的窗口仍会在 [restoreAll] 中按原状恢复，
     * 不会因为「隐藏到一半失败」而遗留永久不可见的窗口。
     *
     * 不可见（本就隐藏）或未挂载的窗口一律跳过：既不隐藏，也不进入待恢复集合。
     */
    fun hideAll(windows: List<T>): Int {
        hiddenWindows.clear()
        windows.forEach { window ->
            if (!isAttached(window)) return@forEach
            val originalVisible = isVisible(window)
            if (!originalVisible) return@forEach
            hiddenWindows[window] = originalVisible
            setVisible(window, false)
        }
        return hiddenWindows.size
    }

    /**
     * 恢复本次被隐藏的窗口，返回**实际完成写回的窗口数**。
     *
     * 仅恢复「原本次前可见」且「此刻仍挂载」的窗口，写回其原始可见性；
     * 截图期间被关闭（已移除）的窗口不参与恢复，避免把已移除的 View 重新写回可见，
     * 也绝不把原本就隐藏的窗口强制显示。
     */
    fun restoreAll(): Int {
        var restored = 0
        hiddenWindows.forEach { (window, originalVisible) ->
            if (!isAttached(window)) return@forEach
            setVisible(window, originalVisible)
            restored += 1
        }
        hiddenWindows.clear()
        return restored
    }
}

/**
 * 以「隐藏 → 执行截图 → 恢复」的生命周期执行一次截图。
 *
 * 异常安全要求（本轮修复的核心）：
 * 1. `hide()` 在 `try` 之外单独保护——隐藏失败时**中止本次截图**（绝不带着
 *    「窗口仍可能进入截图」的风险继续取帧），但仍要尽力回滚已改动的窗口状态。
 * 2. 截图阶段无论成功、抛异常还是协程被取消，`finally` 都必须执行恢复。
 * 3. 恢复用 [NonCancellable]，避免协程取消导致恢复被跳过。
 * 4. 恢复自身的异常只通过 [onRestoreFailure] 上报，**不抛出**，
 *    因此不会静默覆盖/掩盖隐藏或截图的原始异常。
 *
 * 线程约束：本函数不关心线程，`hide` / `restore` 的**线程归属由调用方决定**
 * （Android 侧必须经 `Dispatchers.Main.immediate` 执行真正的 View / WindowManager 操作）。
 * 这样本函数保持纯逻辑、可在 JVM 单元测试中验证生命周期语义。
 */
internal suspend fun <T> runWithCaptureWindowsHidden(
    hide: suspend () -> Unit,
    restore: suspend () -> Unit,
    capture: suspend () -> T,
    onRestoreFailure: (Throwable) -> Unit = {}
): T {
    try {
        hide()
    } catch (hideError: Throwable) {
        // 隐藏失败：不执行截图；先尝试恢复，再把隐藏异常原样抛出（恢复异常不得掩盖它）。
        restoreQuietly(restore, onRestoreFailure)
        throw hideError
    }
    return try {
        capture()
    } finally {
        // 成功 / 失败 / 取消三条路径都恢复；恢复异常只上报，不覆盖原始异常。
        restoreQuietly(restore, onRestoreFailure)
    }
}

/**
 * 在 [NonCancellable] 下执行恢复，并把恢复自身的异常交给 [onRestoreFailure] 上报（不抛出）。
 * 保证「协程被取消时恢复仍会执行」且「恢复异常不会掩盖原始故障」。
 */
private suspend fun restoreQuietly(
    restore: suspend () -> Unit,
    onRestoreFailure: (Throwable) -> Unit
) {
    try {
        withContext(NonCancellable) { restore() }
    } catch (restoreError: Throwable) {
        onRestoreFailure(restoreError)
    }
}

/**
 * 截图生命周期互斥锁。
 *
 * 悬浮窗可见性记账实例在 OverlayService 中只有一个（[CaptureUiGate] 的 start / end 都作用于它），
 * 因此两次重叠的截图若并发进入，后进入者会清空前者记录的原状快照，导致窗口状态恢复错误、
 * 甚至永久隐藏。本锁保证「记录原状 → 隐藏 → 截取新帧 → 恢复」整段生命周期串行执行。
 */
internal object CaptureLifecycleLock {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * 一次截图的「新帧」等待上限（毫秒）。超时即返回 null，交调用方报明确失败，
 * 不把不可验证的旧帧当作成功。
 */
internal const val ACQUIRE_FRAME_MAX_WAIT_MILLIS: Long = 1_000L

/** 轮询新帧的间隔（毫秒）。 */
internal const val ACQUIRE_FRAME_POLL_INTERVAL_MILLIS: Long = 10L

/**
 * 帧新鲜度判定。
 *
 * [hideCompletedNanos] 是「Hanako 窗口已完成隐藏」的时刻；只有在该时刻**之后**产出的帧
 * 才能证明是隐藏后的新画面。帧时间戳不可用（`<= 0`，如 `Image.getTimestamp()` 返回 0
 * 或非法值）时无法证明新鲜，一律判为旧帧。
 */
internal fun isFrameFresh(frameTimestampNanos: Long, hideCompletedNanos: Long): Boolean {
    if (frameTimestampNanos <= 0L) return false
    return frameTimestampNanos >= hideCompletedNanos
}

/**
 * 「有限等待取新帧」：反复取帧，直到取到 `minFrameTimestampNanos` 之后产出的新帧为止。
 *
 * - `minFrameTimestampNanos == null` 表示本次没有真正隐藏任何窗口，屏幕布局无变化，
 *   此时最新一帧即为当前屏幕，直接采纳。
 * - 旧帧（时间戳早于隐藏完成时刻）必须**立即释放**并计入 [onDiscardedFrame]，
 *   不泄漏 `Image` 资源。
 * - 超过 [maxWaitMillis] 仍取不到新帧则返回 null，由调用方给出明确诊断失败，
 *   绝不把旧帧当成新画面交给 OCR。
 */
internal suspend fun <T : Any> acquireFreshFrame(
    minFrameTimestampNanos: Long?,
    acquire: () -> T?,
    frameTimestampOf: (T) -> Long,
    release: (T) -> Unit,
    onDiscardedFrame: (Long) -> Unit = {},
    maxWaitMillis: Long = ACQUIRE_FRAME_MAX_WAIT_MILLIS,
    pollIntervalMillis: Long = ACQUIRE_FRAME_POLL_INTERVAL_MILLIS
): T? {
    val startedAtNanos = System.nanoTime()
    while (true) {
        val candidate = acquire()
        if (candidate != null) {
            val timestamp = frameTimestampOf(candidate)
            if (minFrameTimestampNanos == null || isFrameFresh(timestamp, minFrameTimestampNanos)) {
                return candidate
            }
            release(candidate)
            onDiscardedFrame(timestamp)
        }
        if ((System.nanoTime() - startedAtNanos) / 1_000_000L >= maxWaitMillis) return null
        delay(pollIntervalMillis)
    }
}

/**
 * 截屏未能在限定时间内取得「窗口隐藏后产出的新帧」时抛出。
 *
 * 与「截屏完全不可用」区分：本异常表示拿到了帧但全部是隐藏前的旧帧，
 * 因此诊断上应标记为「有效帧校验失败」，而不是「没有截图」。
 */
internal class StaleFrameException(message: String) : IllegalStateException(message)
