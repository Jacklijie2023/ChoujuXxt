package `fun`.kirari.hanako.capture

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图期间 Hanako 自有窗口可见性记账的单元测试（纯逻辑，不依赖 Android View）。
 *
 * 覆盖：正常恢复、原本隐藏不强制显示、已移除窗口不参与、截图期间被关闭不恢复。
 */
class CaptureWindowVisibilityTest {

    private class FakeWindow(visible: Boolean, attached: Boolean = true) {
        var visible: Boolean = visible
        var attached: Boolean = attached
    }

    private fun book() = CaptureWindowVisibility<FakeWindow>(
        isVisible = { it.visible },
        isAttached = { it.attached },
        setVisible = { window, visible -> window.visible = visible }
    )

    @Test
    fun hidesVisibleWindowsDuringCaptureAndRestoresThemAfterwards() {
        val bubble = FakeWindow(visible = true)
        val miniChat = FakeWindow(visible = true)
        val book = book()

        book.hideAll(listOf(bubble, miniChat))
        assertFalse("截图期间悬浮球应隐藏", bubble.visible)
        assertFalse("截图期间迷你聊天窗应隐藏", miniChat.visible)

        book.restoreAll()
        assertTrue("截图后悬浮球应恢复", bubble.visible)
        assertTrue("截图后迷你聊天窗应恢复", miniChat.visible)
    }

    @Test
    fun originallyHiddenWindowIsNotForcedVisibleAfterCapture() {
        val bubble = FakeWindow(visible = false)
        val miniChat = FakeWindow(visible = true)
        val book = book()

        book.hideAll(listOf(bubble, miniChat))
        assertFalse(bubble.visible)
        assertFalse(miniChat.visible)

        book.restoreAll()
        assertFalse("截图前本就隐藏的窗口不得被强制显示", bubble.visible)
        assertTrue(miniChat.visible)
    }

    @Test
    fun detachedWindowIsNeitherHiddenNorRestored() {
        val removed = FakeWindow(visible = true, attached = false)
        val visible = FakeWindow(visible = true)
        val book = book()

        book.hideAll(listOf(removed, visible))
        assertTrue("已从窗口系统移除的窗口不应被改写可见性", removed.visible)
        assertFalse(visible.visible)

        book.restoreAll()
        assertTrue(removed.visible)
        assertTrue(visible.visible)
    }

    @Test
    fun windowClosedDuringCaptureIsNotRestored() {
        val miniChat = FakeWindow(visible = true)
        val book = book()

        book.hideAll(listOf(miniChat))
        assertFalse(miniChat.visible)

        // 截图期间迷你聊天窗被关闭（从窗口系统移除）。
        miniChat.attached = false
        book.restoreAll()

        assertFalse("截图期间已关闭的窗口不应被重新显示", miniChat.visible)
    }

    @Test
    fun hideAllAndRestoreAllReportRealCounts() {
        val bubble = FakeWindow(visible = true)
        val hidden = FakeWindow(visible = false)
        val detached = FakeWindow(visible = true, attached = false)
        val book = book()

        // 只记录「可见且已挂载」的窗口：此处仅 bubble 满足。
        assertEquals(1, book.hideAll(listOf(bubble, hidden, detached)))
        assertEquals(1, book.restoreAll())
    }

    @Test
    fun partialHideFailureStillRestoresEveryRecordedWindow() {
        // 需求：隐藏动作进行到一半（第二个窗口改写时）抛异常，必须回滚此前已改动的窗口状态，
        // 且不得使任一窗口遗留不可见。记录先于改写，因此 miniChat 在抛异常前已被记录。
        val bubble = FakeWindow(visible = true)
        val miniChat = FakeWindow(visible = true)
        var failuresLeft = 1
        val book = CaptureWindowVisibility<FakeWindow>(
            isVisible = { it.visible },
            isAttached = { it.attached },
            setVisible = { window, visible ->
                if (window === miniChat && !visible && failuresLeft > 0) {
                    failuresLeft -= 1
                    throw IllegalStateException("hide boom on miniChat")
                }
                window.visible = visible
            }
        )

        val failure = runCatching { book.hideAll(listOf(bubble, miniChat)) }
        assertTrue("隐藏中途失败应被调用方感知并中止截图", failure.isFailure)
        assertFalse("失败前已改写的窗口处于隐藏态，等待恢复", bubble.visible)

        // 恢复：两个窗口都按原始可见性写回（miniChat 已在改写前被记录）。
        assertEquals(2, book.restoreAll())
        assertTrue("隐藏中途失败不得使悬浮球遗留不可见", bubble.visible)
        assertTrue("隐藏中途失败不得使迷你窗遗留不可见", miniChat.visible)
    }
}

// android.view.View 的三态常量（JVM 单测不加载 Android 框架，故以同值常量复刻其语义）。
private const val TRI_STATE_VISIBLE = 0
private const val TRI_STATE_INVISIBLE = 4
private const val TRI_STATE_GONE = 8

/**
 * 三态可见性恢复的单元测试（纯逻辑，不依赖 Android View）。
 *
 * 生产接线（见 `OverlayService.captureWindowVisibility`，与 `setVisible` 一致）：
 * - `isVisible = { it.visibility == View.VISIBLE }`
 * - `setVisible = { view, visible -> view.visibility = if (visible) View.VISIBLE else View.GONE }`
 *
 * 由于只有 `visibility == VISIBLE` 的窗口会进入待恢复集合，INVISIBLE / GONE 窗口既不会被隐藏、
 * 也不会被恢复（`setVisible` 从未对它们调用），因此 `LinkedHashMap<T, Boolean>` 快照所存的值
 * 「原本次前是否可见」足以无损恢复原状，无需保存精确的三态。本类锁定该行为契约。
 */
class CaptureWindowVisibilityThreeStateTest {

    private class TriStateWindow(
        var visibility: Int,
        var attached: Boolean = true
    ) {
        /** 记录每次被 `setVisible` 写入的值，用于证明 INVISIBLE / GONE 窗口从未被触碰。 */
        val setVisibleCalls = mutableListOf<Boolean>()
    }

    /** 复刻生产接线：仅 VISIBLE 视为可见；隐藏写 GONE、显示写 VISIBLE。 */
    private fun book() = CaptureWindowVisibility<TriStateWindow>(
        isVisible = { it.visibility == TRI_STATE_VISIBLE },
        isAttached = { it.attached },
        setVisible = { window, visible ->
            window.setVisibleCalls += visible
            window.visibility = if (visible) TRI_STATE_VISIBLE else TRI_STATE_GONE
        }
    )

    @Test
    fun visibleWindowRoundTripsBackToVisibleExactly() {
        val bubble = TriStateWindow(TRI_STATE_VISIBLE)
        val book = book()

        assertEquals(1, book.hideAll(listOf(bubble)))
        assertEquals("生产接线把「隐藏」写为 GONE", TRI_STATE_GONE, bubble.visibility)

        assertEquals(1, book.restoreAll())
        assertEquals("恢复必须精确回到原始 VISIBLE，不得被压平成其它状态", TRI_STATE_VISIBLE, bubble.visibility)
        assertEquals(listOf(false, true), bubble.setVisibleCalls)
    }

    @Test
    fun invisibleWindowIsNeverHiddenNorRestored() {
        val panel = TriStateWindow(TRI_STATE_INVISIBLE)
        val book = book()

        assertEquals("INVISIBLE 窗口不得进入待恢复集合", 0, book.hideAll(listOf(panel)))
        assertEquals("INVISIBLE 必须保持 INVISIBLE（不得被改写为 GONE）", TRI_STATE_INVISIBLE, panel.visibility)
        assertTrue("INVISIBLE 窗口的 setVisible 不得被调用", panel.setVisibleCalls.isEmpty())

        assertEquals(0, book.restoreAll())
        assertEquals("恢复阶段同样不得触碰 INVISIBLE 窗口", TRI_STATE_INVISIBLE, panel.visibility)
        assertTrue(panel.setVisibleCalls.isEmpty())
    }

    @Test
    fun goneWindowIsNeverHiddenNorRestored() {
        val miniChat = TriStateWindow(TRI_STATE_GONE)
        val book = book()

        assertEquals("GONE 窗口不得进入待恢复集合", 0, book.hideAll(listOf(miniChat)))
        assertEquals("GONE 必须保持 GONE（不得被改写）", TRI_STATE_GONE, miniChat.visibility)
        assertTrue("GONE 窗口的 setVisible 不得被调用", miniChat.setVisibleCalls.isEmpty())

        assertEquals(0, book.restoreAll())
        assertEquals("恢复阶段同样不得触碰 GONE 窗口", TRI_STATE_GONE, miniChat.visibility)
        assertTrue(miniChat.setVisibleCalls.isEmpty())
    }

    @Test
    fun mixedStatesHideOnlyVisibleWindowAndRestoreEachStateExactly() {
        val bubble = TriStateWindow(TRI_STATE_VISIBLE)
        val panel = TriStateWindow(TRI_STATE_INVISIBLE)
        val miniChat = TriStateWindow(TRI_STATE_GONE)
        val book = book()

        assertEquals("只有 VISIBLE 窗口参与隐藏", 1, book.hideAll(listOf(bubble, panel, miniChat)))
        assertEquals(TRI_STATE_GONE, bubble.visibility)
        assertEquals(TRI_STATE_INVISIBLE, panel.visibility)
        assertEquals(TRI_STATE_GONE, miniChat.visibility)
        assertTrue(
            "非 VISIBLE 窗口不得被 setVisible",
            panel.setVisibleCalls.isEmpty() && miniChat.setVisibleCalls.isEmpty()
        )

        assertEquals(1, book.restoreAll())
        assertEquals("三态各自回到截图前的精确状态", TRI_STATE_VISIBLE, bubble.visibility)
        assertEquals(TRI_STATE_INVISIBLE, panel.visibility)
        assertEquals(TRI_STATE_GONE, miniChat.visibility)
    }

    @Test
    fun detachedWindowIsNeverHiddenNorReAddedOnRestore() {
        // 未附着（已从窗口系统移除）的窗口：既不参与隐藏，也不得在恢复阶段被重新写回可见/重新添加。
        val detached = TriStateWindow(TRI_STATE_VISIBLE, attached = false)
        val attached = TriStateWindow(TRI_STATE_VISIBLE)
        val book = book()

        assertEquals(1, book.hideAll(listOf(detached, attached)))
        assertTrue("未附着窗口的 setVisible 不得被调用", detached.setVisibleCalls.isEmpty())
        assertEquals(TRI_STATE_VISIBLE, detached.visibility)
        assertEquals(TRI_STATE_GONE, attached.visibility)

        // 截图期间已挂载的窗口被关闭（卸载）：恢复阶段必须跳过它，绝不把它重新写回可见。
        attached.attached = false
        assertEquals("记录在案但已卸载的窗口不参与恢复", 0, book.restoreAll())
        assertEquals("不得把已卸载的窗口重新写回可见", TRI_STATE_GONE, attached.visibility)
        assertEquals(listOf(false), attached.setVisibleCalls)
    }

    @Test
    fun hideFailureMidwayRestoresModifiedWindowAndLeavesUntouchedStatesIntact() {
        // 第二个 VISIBLE 窗口改写时抛异常：先前已改写的窗口必须按原状恢复；
        // 从未被触碰的 INVISIBLE / GONE 窗口必须保持原状。
        val bubble = TriStateWindow(TRI_STATE_VISIBLE)
        val miniChat = TriStateWindow(TRI_STATE_VISIBLE)
        val panel = TriStateWindow(TRI_STATE_INVISIBLE)
        val gone = TriStateWindow(TRI_STATE_GONE)
        var failuresLeft = 1
        val book = CaptureWindowVisibility<TriStateWindow>(
            isVisible = { it.visibility == TRI_STATE_VISIBLE },
            isAttached = { it.attached },
            setVisible = { window, visible ->
                if (window === miniChat && !visible && failuresLeft > 0) {
                    failuresLeft -= 1
                    throw IllegalStateException("hide boom on miniChat")
                }
                window.setVisibleCalls += visible
                window.visibility = if (visible) TRI_STATE_VISIBLE else TRI_STATE_GONE
            }
        )

        val failure = runCatching { book.hideAll(listOf(bubble, miniChat, panel, gone)) }
        assertTrue("隐藏中途失败必须被调用方感知", failure.isFailure)
        assertEquals("失败前已改写的窗口处于 GONE，等待恢复", TRI_STATE_GONE, bubble.visibility)
        assertTrue("从未被触碰的非 VISIBLE 窗口保持原状", panel.setVisibleCalls.isEmpty() && gone.setVisibleCalls.isEmpty())

        assertEquals("已记录的窗口必须被恢复", 2, book.restoreAll())
        assertEquals("已被修改的窗口必须恢复到原始 VISIBLE", TRI_STATE_VISIBLE, bubble.visibility)
        assertEquals(TRI_STATE_VISIBLE, miniChat.visibility)
        assertEquals("未被触碰的 INVISIBLE 窗口保持原状", TRI_STATE_INVISIBLE, panel.visibility)
        assertEquals("未被触碰的 GONE 窗口保持原状", TRI_STATE_GONE, gone.visibility)
    }
}

/**
 * [runWithCaptureWindowsHidden] 的单元测试：成功、异常、协程取消三条路径都必须恢复窗口。
 */
class CaptureWindowsHiddenFlowTest {

    @Test
    fun restoresWindowsWhenCaptureSucceeds() = runBlocking {
        var hidden = false
        var restored = false

        val result = runWithCaptureWindowsHidden(
            hide = { hidden = true },
            restore = { restored = true },
            capture = { 42 }
        )

        assertEquals(42, result)
        assertTrue("截图前必须已隐藏", hidden)
        assertTrue("截图成功后必须恢复", restored)
    }

    @Test
    fun restoresWindowsWhenCaptureThrows() = runBlocking {
        var hidden = false
        var restored = false

        val failure = runCatching {
            runWithCaptureWindowsHidden(
                hide = { hidden = true },
                restore = { restored = true },
                capture = { throw IllegalStateException("capture boom") }
            )
        }

        assertTrue(hidden)
        assertTrue("截图抛异常后仍须恢复窗口", restored)
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun restoresWindowsWhenCaptureIsCancelled() = runBlocking {
        var hidden = false
        var restored = false

        val job = launch {
            runWithCaptureWindowsHidden(
                hide = { hidden = true },
                restore = { restored = true },
                capture = { delay(10_000) }
            )
        }
        delay(100)
        job.cancelAndJoin()

        assertTrue(hidden)
        assertTrue("协程取消后不得遗留窗口被隐藏的状态", restored)
    }

    @Test
    fun hideFailureAbortsCaptureAndStillRestores() = runBlocking {
        var captureRan = false
        var restored = false

        val failure = runCatching {
            runWithCaptureWindowsHidden(
                hide = { throw IllegalStateException("hide boom") },
                restore = { restored = true },
                capture = { captureRan = true; 1 }
            )
        }

        assertFalse("无法安全隐藏时必须中止本次截图", captureRan)
        assertTrue("隐藏失败后仍须回滚恢复已改动的窗口", restored)
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals("hide boom", failure.exceptionOrNull()?.message)
    }

    @Test
    fun restoreFailureDoesNotMaskCaptureFailure() = runBlocking {
        val restoreFailures = mutableListOf<Throwable>()

        val failure = runCatching {
            runWithCaptureWindowsHidden(
                hide = { },
                restore = { throw IllegalStateException("restore boom") },
                capture = { throw IllegalArgumentException("capture boom") },
                onRestoreFailure = { restoreFailures += it }
            )
        }

        assertEquals(
            "原始截图异常必须被保留，不得被恢复异常掩盖",
            "capture boom",
            failure.exceptionOrNull()?.message
        )
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, restoreFailures.size)
        assertEquals("restore boom", restoreFailures.single().message)
    }

    @Test
    fun restoreStillRunsAndIsReportedWhenCoroutineIsCancelled() = runBlocking {
        val restoreFailures = mutableListOf<Throwable>()
        var restoreAttempted = false

        val job = launch {
            runWithCaptureWindowsHidden(
                hide = { },
                restore = { restoreAttempted = true; throw IllegalStateException("restore boom") },
                capture = { delay(10_000) },
                onRestoreFailure = { restoreFailures += it }
            )
        }
        delay(100)
        job.cancelAndJoin()

        assertTrue("协程取消时恢复仍必须被执行（NonCancellable）", restoreAttempted)
        assertEquals("恢复异常只上报，不抛出", 1, restoreFailures.size)
    }

    @Test
    fun occludingCaptureRecordsHideCompletionTimeAndOnlyAcceptsFramesAfterIt() = runBlocking {
        // R8.5：用户请求触发采集时必须真正隐藏窗口，并把「隐藏完成」时刻作为帧新鲜度基点传给后端，
        // 否则可能拿到隐藏前（仍含 Hanako 浮窗）的旧帧 —— 真机 `minTs=- waitedMillis=0` 正是旧行为。
        // 本测试锁定 captureWithHiddenHanakoWindows 的编排契约：hide 先完成并记录基点，capture 才用基点取帧。
        var hideCompletedNanos: Long? = null

        val accepted = runWithCaptureWindowsHidden(
            hide = { hideCompletedNanos = System.nanoTime() },
            restore = { },
            capture = {
                val base = requireNotNull(hideCompletedNanos) { "隐藏完成后必须记录时间基点" }
                assertFalse("隐藏前产出的旧帧必须被拒绝", isFrameFresh(base - 5_000_000L, base))
                isFrameFresh(base + 5_000_000L, base)
            }
        )

        assertNotNull("隐藏完成后必须留下可传给后端的时间基点", hideCompletedNanos)
        assertTrue("隐藏之后产出的新帧必须被接纳", accepted)
    }

    @Test
    fun occludingCaptureStillRestoresWindowsWhenFrameAcquisitionThrows() = runBlocking {
        // R8.5 采集事务的失败安全：即便「取新帧」阶段因帧不新鲜（StaleFrameException）等方式失败，
        // 也必须恢复 Hanako 窗口，绝不把浮窗永久留在隐藏态。
        var hidden = false
        var restored = false

        val failure = runCatching {
            runWithCaptureWindowsHidden(
                hide = { hidden = true },
                restore = { restored = true },
                capture = { throw StaleFrameException("no frame after hide") }
            )
        }

        assertTrue(hidden)
        assertTrue("取帧失败后仍须恢复 Hanako 窗口", restored)
        assertTrue(failure.exceptionOrNull() is StaleFrameException)
    }
}

/**
 * 帧新鲜度判定与「有限等待取新帧」的单元测试（纯逻辑，不依赖 Android ImageReader）。
 *
 * 关键点：`ImageReader.acquireLatestImage()` 可能返回隐藏前产出的旧帧，
 * 必须用帧时间戳与「窗口隐藏完成时刻」比较，才能证明取到的是新画面。
 */
class CaptureFrameFreshnessTest {

    private class FakeFrame(val timestamp: Long) {
        var closed: Boolean = false
    }

    private fun acquire(
        minTs: Long?,
        frames: List<FakeFrame?>,
        discarded: MutableList<Long> = mutableListOf(),
        released: MutableList<FakeFrame> = mutableListOf()
    ): Pair<FakeFrame?, List<FakeFrame>> {
        val queue = ArrayDeque(frames)
        val result = runBlocking {
            acquireFreshFrame(
                minFrameTimestampNanos = minTs,
                acquire = { queue.removeFirstOrNull() },
                frameTimestampOf = { it.timestamp },
                release = { it.closed = true; released += it },
                onDiscardedFrame = { discarded += it },
                maxWaitMillis = 60L,
                pollIntervalMillis = 5L
            )
        }
        return result to released
    }

    @Test
    fun frameTimestampAtOrAfterHideCompletionCountsAsFresh() {
        assertTrue(isFrameFresh(1_000L, 1_000L))
        assertTrue(isFrameFresh(1_001L, 1_000L))
        assertFalse("隐藏前产出的帧不得判为新鲜", isFrameFresh(999L, 1_000L))
    }

    @Test
    fun frameWithUnavailableTimestampIsNeverFresh() {
        // Image.getTimestamp() 返回 0（或非法值）时无法证明「隐藏之后产出」，不得当作新帧。
        assertFalse(isFrameFresh(0L, 0L))
        assertFalse(isFrameFresh(-1L, 0L))
    }

    @Test
    fun acquireAcceptsFrameProducedAfterHide() {
        val fresh = FakeFrame(timestamp = 2_000L)
        val (result, released) = acquire(minTs = 1_000L, frames = listOf(fresh))

        assertEquals("隐藏后的新帧应被采纳", fresh, result)
        assertFalse("被采纳的新帧不得被释放", fresh.closed)
        assertTrue(released.isEmpty())
    }

    @Test
    fun acquireDiscardsStaleFramesAndReleasesThem() {
        val stale1 = FakeFrame(timestamp = 400L)
        val stale2 = FakeFrame(timestamp = 800L)
        val fresh = FakeFrame(timestamp = 1_500L)
        val discarded = mutableListOf<Long>()
        val released = mutableListOf<FakeFrame>()
        val queue = ArrayDeque(listOf<FakeFrame?>(stale1, stale2, fresh))

        val result = runBlocking {
            acquireFreshFrame(
                minFrameTimestampNanos = 1_000L,
                acquire = { queue.removeFirstOrNull() },
                frameTimestampOf = { it.timestamp },
                release = { it.closed = true; released += it },
                onDiscardedFrame = { discarded += it },
                maxWaitMillis = 200L,
                pollIntervalMillis = 5L
            )
        }

        assertEquals(fresh, result)
        assertTrue("隐藏前产出的旧帧必须被释放", stale1.closed)
        assertTrue("隐藏前产出的旧帧必须被释放", stale2.closed)
        assertEquals(listOf(400L, 800L), discarded)
        assertFalse(fresh.closed)
    }

    @Test
    fun acquireTimesOutAndReleasesEveryDiscardedFrame() {
        // 始终只拿到隐藏前的旧帧：必须在有限时间后返回 null（交由调用方报明确诊断失败），
        // 且每一个被丢弃的帧都要正确关闭，不泄漏 Image 资源。
        val frames = List(64) { FakeFrame(timestamp = 100L) }
        val discarded = mutableListOf<Long>()
        val released = mutableListOf<FakeFrame>()
        val queue = ArrayDeque<FakeFrame?>(frames)

        val result = runBlocking {
            acquireFreshFrame(
                minFrameTimestampNanos = 1_000L,
                acquire = { queue.removeFirstOrNull() },
                frameTimestampOf = { it.timestamp },
                release = { it.closed = true; released += it },
                onDiscardedFrame = { discarded += it },
                maxWaitMillis = 40L,
                pollIntervalMillis = 5L
            )
        }

        assertNull("始终取不到新帧时必须返回 null", result)
        assertTrue("发生过丢弃", released.isNotEmpty())
        assertTrue("每个被丢弃的旧帧都必须被释放", released.all { it.closed })
        assertEquals(released.size, discarded.size)
    }

    @Test
    fun acquireAcceptsAnyFrameWhenNoWindowWasHidden() {
        // 本次没有真正隐藏任何窗口（minTs 为 null）：屏幕布局无可见变化，最新一帧即当前屏幕。
        val only = FakeFrame(timestamp = 10L)
        val (result, released) = acquire(minTs = null, frames = listOf(only))

        assertEquals(only, result)
        assertFalse(only.closed)
        assertTrue(released.isEmpty())
    }

    @Test
    fun acquireReturnsNullWhenNoFrameEverAvailable() {
        val result = runBlocking {
            acquireFreshFrame<FakeFrame>(
                minFrameTimestampNanos = 1_000L,
                acquire = { null },
                frameTimestampOf = { it.timestamp },
                release = { },
                maxWaitMillis = 30L,
                pollIntervalMillis = 5L
            )
        }
        assertNull(result)
    }
}

/**
 * [CaptureLifecycleLock] 的单元测试：重叠的截图任务必须串行执行完整的
 * 「记录原始可见性 → 隐藏 → 截取新帧 → 恢复」生命周期，
 * 后一个任务不得在第一个任务恢复窗口之前清空/覆盖其共享的可见性快照。
 */
class CaptureLifecycleLockTest {

    private class FakeWindow(visible: Boolean) {
        var visible: Boolean = visible
    }

    @Test
    fun overlappingCaptureLifecyclesAreSerializedAndDoNotClobberSharedSnapshot() = runBlocking {
        val bubble = FakeWindow(visible = true)
        val windows = listOf(bubble)
        // 与生产一致：OverlayService 只有一个共享的可见性记账实例
        // （CaptureUiGate 的 start/end 都作用于它），因此重叠截图会互相覆盖快照。
        val sharedBook = CaptureWindowVisibility<FakeWindow>(
            isVisible = { it.visible },
            isAttached = { true },
            setVisible = { window, visible -> window.visible = visible }
        )

        var secondEnteredWhileFirstHeldSnapshot = false
        var hideCallCount = 0

        val first = launch {
            CaptureLifecycleLock.runExclusive {
                sharedBook.hideAll(windows)
                hideCallCount += 1
                assertFalse("第一个任务截图期间悬浮球应隐藏", bubble.visible)
                delay(80)
                sharedBook.restoreAll()
                assertTrue("第一个任务必须恢复悬浮球", bubble.visible)
            }
        }
        // 让第一个任务先拿到锁并进入隐藏态。
        delay(25)
        val second = launch {
            CaptureLifecycleLock.runExclusive {
                // 若在第一个任务恢复前进入，hideAll() 的 snapshot.clear() 会丢掉第一个任务
                // 记录的原始可见性，导致第一个任务恢复时把窗口状态改错或永久隐藏。
                if (!bubble.visible) secondEnteredWhileFirstHeldSnapshot = true
                sharedBook.hideAll(windows)
                hideCallCount += 1
                delay(20)
                sharedBook.restoreAll()
            }
        }
        first.join()
        second.join()

        assertFalse(
            "第二个截图任务不得在第一个任务恢复前进入（否则会清空共享可见性快照）",
            secondEnteredWhileFirstHeldSnapshot
        )
        assertEquals(2, hideCallCount)
        assertTrue("两个重叠截图任务结束后悬浮球必须恢复可见（未被永久隐藏）", bubble.visible)
    }
}
