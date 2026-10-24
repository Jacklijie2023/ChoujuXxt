package `fun`.kirari.hanako.screencontext

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextManagerTest {

    private class MutableClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun manager(
        ttlMillis: Long = ScreenContextManager.DEFAULT_TTL_MILLIS,
        minOcrRefreshIntervalMillis: Long = ScreenContextManager.DEFAULT_MIN_OCR_REFRESH_INTERVAL_MILLIS,
        clock: () -> Long
    ) = ScreenContextManager(
        ttlMillis = ttlMillis,
        minOcrRefreshIntervalMillis = minOcrRefreshIntervalMillis,
        clock = clock
    )

    @Test
    fun normalizeCollapsesBlankLinesAndTruncates() {
        assertEquals("a\nb", normalizeScreenText("  a  \n\n  b  \n", 100))
        assertEquals("abc", normalizeScreenText("abcdef", 3))
        assertEquals("", normalizeScreenText("  \n \n ", 100))
    }

    @Test
    fun fingerprintIsStableAndDistinct() {
        assertEquals(fingerprintOf("same"), fingerprintOf("same"))
        assertNotEquals(fingerprintOf("a"), fingerprintOf("b"))
    }

    @Test
    fun updateIgnoredWhileInactive() {
        val m = manager(clock = { 0L })
        m.updateFromAccessibility("hello", "com.app")
        assertNull(m.snapshot.value)
    }

    @Test
    fun updateRefreshesTimestampWhenSameContentObserved() {
        val clock = MutableClock()
        val m = manager(clock = clock)
        m.start()
        clock.now = 1_000L
        m.updateFromAccessibility("hello", "com.app")
        val first = m.snapshot.value!!
        clock.now = 2_000L
        m.updateFromAccessibility("hello", "com.app")
        val second = m.snapshot.value!!
        assertEquals("hello", second.text)
        assertEquals(2_000L, second.updatedAtMillis)
        assertNotEquals(first.updatedAtMillis, second.updatedAtMillis)
    }

    @Test
    fun expiredQuestionRecoversWhenReObserved() {
        val clock = MutableClock()
        val m = manager(ttlMillis = 30_000L, clock = clock)
        m.start()
        clock.now = 0L
        m.updateFromAccessibility("question A", "com.app")
        clock.now = 30_001L
        assertNull(m.currentText())

        // 同一道题被再次有效观察，恢复为可用上下文，而非因超 TTL 永久不可用。
        clock.now = 31_000L
        m.updateFromAccessibility("question A", "com.app")
        assertEquals("question A", m.currentText())
    }

    @Test
    fun updateOverwritesOldQuestionWhenScreenSwitches() {
        val clock = MutableClock()
        val m = manager(clock = clock)
        m.start()
        clock.now = 1_000L
        m.updateFromAccessibility("question A", "com.app")
        clock.now = 2_000L
        m.updateFromAccessibility("question B", "com.other")
        assertEquals("question B", m.snapshot.value!!.text)
        assertEquals("com.other", m.snapshot.value!!.sourceApp)
        assertEquals(2_000L, m.snapshot.value!!.updatedAtMillis)
        assertNotEquals(
            fingerprintOf("question A"),
            m.snapshot.value!!.textFingerprint
        )
    }

    @Test
    fun updateIgnoredAfterStop() {
        val clock = MutableClock()
        val m = manager(clock = clock)
        m.start()
        clock.now = 0L
        m.updateFromAccessibility("before", "com.app")
        m.stop()
        clock.now = 1_000L
        m.updateFromAccessibility("after", "com.app")
        assertNull(m.snapshot.value)
    }

    @Test
    fun currentTextReturnsNullWhenExpired() {
        val clock = MutableClock()
        val m = manager(ttlMillis = 30_000L, clock = clock)
        m.start()
        clock.now = 0L
        m.updateFromAccessibility("hello", "com.app")
        clock.now = 29_999L
        assertEquals("hello", m.currentText())
        clock.now = 30_001L
        assertNull(m.currentText())
    }

    @Test
    fun stopClearsSnapshot() {
        val clock = MutableClock()
        val m = manager(clock = clock)
        m.start()
        m.updateFromAccessibility("hello", "com.app")
        m.stop()
        assertNull(m.snapshot.value)
    }

    @Test
    fun refreshViaOcrReturnsValidCacheWithoutInvokingOcr() = runBlocking {
        val clock = MutableClock()
        val m = manager(clock = clock)
        m.start()
        clock.now = 0L
        m.updateFromAccessibility("cached", "com.app")
        var ocrCalls = 0
        val text = m.refreshViaOcr {
            ocrCalls++
            "ocr"
        }
        assertEquals("cached", text)
        assertEquals(0, ocrCalls)
    }

    @Test
    fun refreshViaOcrFallsBackAndThrottles() = runBlocking {
        val clock = MutableClock()
        val m = manager(ttlMillis = 1_000L, minOcrRefreshIntervalMillis = 10_000L, clock = clock)
        m.start()
        var ocrCalls = 0

        clock.now = 0L
        assertEquals("ocr-text", m.refreshViaOcr { ocrCalls++; "ocr-text" })
        assertEquals(1, ocrCalls)

        // 已过期但仍在节流窗口内：不再发起 OCR，返回 null（未缓存有效内容）。
        clock.now = 2_000L
        assertNull(m.refreshViaOcr { ocrCalls++; "other" })
        assertEquals(1, ocrCalls)

        // 超过节流窗口后可再次发起 OCR。
        clock.now = 12_000L
        assertEquals("new-text", m.refreshViaOcr { ocrCalls++; "new-text" })
        assertEquals(2, ocrCalls)
    }

    @Test
    fun refreshViaOcrReturnsNullWhenOcrFailsAndNoCache() = runBlocking {
        val clock = MutableClock()
        val m = manager(clock = clock)
        m.start()
        assertNull(m.refreshViaOcr { null })
    }

    @Test
    fun refreshFreshIgnoresStaleCacheAndReadsCurrentScreen() = runBlocking {
        val clock = MutableClock()
        val m = manager(ttlMillis = 30_000L, minOcrRefreshIntervalMillis = 10_000L, clock = clock)
        m.start()
        // 上一屏的旧题目缓存仍在 TTL 内。
        clock.now = 0L
        m.updateFromAccessibility("old question", "com.app")

        // 无障碍当前读取失败，缓存尚在 30 秒内，但不得把旧题目当作当前题目。
        val text = m.refreshViaOcr(allowCached = false) { "new question" }
        assertEquals("new question", text)
    }

    @Test
    fun refreshFreshNotThrottledByRecentOcr() = runBlocking {
        val clock = MutableClock()
        val m = manager(ttlMillis = 30_000L, minOcrRefreshIntervalMillis = 10_000L, clock = clock)
        m.start()
        clock.now = 0L
        assertEquals("first", m.refreshViaOcr(allowCached = false) { "first" })

        // 节流窗口内的 fresh 读取必须照常执行：用户主动发送不能被后台节流无条件失败。
        clock.now = 1_000L
        assertEquals("second", m.refreshViaOcr(allowCached = false) { "second" })
    }

    @Test
    fun refreshFreshOcrFailureReturnsNullNotStaleCache() = runBlocking {
        val clock = MutableClock()
        val m = manager(ttlMillis = 30_000L, clock = clock)
        m.start()
        clock.now = 0L
        m.updateFromAccessibility("old question", "com.app")

        // fresh OCR 失败：返回 null，绝不把 TTL 内旧缓存冒充为本次读取结果。
        assertNull(m.refreshViaOcr(allowCached = false) { null })
    }

    @Test
    fun screenStatusShowsFailureOverSnapshot() {
        val snapshot = ScreenContextSnapshot(
            text = "old question",
            sourceApp = "com.app",
            updatedAtMillis = 0L,
            source = ScreenContextSource.ACCESSIBILITY,
            textFingerprint = fingerprintOf("old question")
        )
        val report = ScreenReadReport(
            traceId = "t",
            status = ScreenReadStatus.SEND_FAILED,
            failureStage = "ocr_empty",
            readAtMillis = 1L
        )
        val text = screenStatusText(active = true, snapshot = snapshot, report = report)
        assertTrue(text.contains("本次发送失败"))
        assertFalse(text.contains("已捕获当前屏幕内容"))
    }

    @Test
    fun screenStatusInjectedShowsSourceAndCharCount() {
        val report = ScreenReadReport(
            traceId = "t",
            status = ScreenReadStatus.SEND_INJECTED,
            source = ScreenContextSource.ACCESSIBILITY,
            charCount = 128,
            injected = true,
            readAtMillis = 1L
        )
        val text = screenStatusText(active = true, snapshot = null, report = report)
        assertTrue(text.contains("已注入"))
        assertTrue(text.contains("无障碍"))
        assertTrue(text.contains("128"))
    }

    @Test
    fun screenStatusAvailableShowsCapturedNotInjected() {
        // 已读到有效新鲜文本但最终 prompt 尚未构造：只显示「已捕获」，不得提前宣称「已注入」。
        val report = ScreenReadReport(
            traceId = "t",
            status = ScreenReadStatus.CONTEXT_AVAILABLE,
            source = ScreenContextSource.LOCAL_OCR,
            charCount = 64,
            readAtMillis = 1L
        )
        val text = screenStatusText(active = true, snapshot = null, report = report)
        assertTrue(text.contains("已捕获"))
        assertFalse(text.contains("已注入"))
        assertTrue(text.contains("本地OCR"))
        assertTrue(text.contains("64"))
    }

    // ===== classifyOcrFailureStage：截图/OCR 失败原因细分（纯函数） =====

    @Test
    fun classifyManagerInactiveNotReportedAsOcrExecuted() {
        // 上下文管理器未启动导致 refreshViaOcr 提前返回：不得误报为 OCR 空结果。
        assertEquals(
            "manager_inactive",
            classifyOcrFailureStage(
                managerActive = false,
                screenshotAttempted = false,
                screenshotSucceeded = false,
                ocrNotAvailable = false,
                ocrErrored = false
            )
        )
    }

    @Test
    fun classifyCaptureFailureDistinctFromOcrEmpty() {
        // 截图会话存在但实际截图失败：识别为截图失败，而非 OCR 空结果。
        assertEquals(
            "capture_failed",
            classifyOcrFailureStage(
                managerActive = true,
                screenshotAttempted = true,
                screenshotSucceeded = false,
                ocrNotAvailable = false,
                ocrErrored = false
            )
        )
    }

    @Test
    fun classifyOcrEmptyWhenScreenshotSucceededButBlank() {
        assertEquals(
            "ocr_empty",
            classifyOcrFailureStage(
                managerActive = true,
                screenshotAttempted = true,
                screenshotSucceeded = true,
                ocrNotAvailable = false,
                ocrErrored = false
            )
        )
    }

    @Test
    fun classifyOcrNotAvailableWhenUnsupported() {
        assertEquals(
            "ocr_not_available",
            classifyOcrFailureStage(
                managerActive = true,
                screenshotAttempted = true,
                screenshotSucceeded = true,
                ocrNotAvailable = true,
                ocrErrored = false
            )
        )
    }

    @Test
    fun classifyOcrFailedWhenRecognitionErrored() {
        assertEquals(
            "ocr_failed",
            classifyOcrFailureStage(
                managerActive = true,
                screenshotAttempted = true,
                screenshotSucceeded = true,
                ocrNotAvailable = false,
                ocrErrored = true
            )
        )
    }

    @Test
    fun classifyStaleFrameDistinctFromGenericCaptureFailure() {
        // 截图因「没取到窗口隐藏后的新帧」而失败：属于画面新鲜度问题，
        // 必须与线程/会话/编码等一般截图执行失败区分开。
        assertEquals(
            "frame_not_fresh",
            classifyOcrFailureStage(
                managerActive = true,
                screenshotAttempted = true,
                screenshotSucceeded = false,
                ocrNotAvailable = false,
                ocrErrored = false,
                staleFrame = true
            )
        )
        // 未标记 staleFrame 时仍是普通截图失败。
        assertEquals(
            "capture_failed",
            classifyOcrFailureStage(
                managerActive = true,
                screenshotAttempted = true,
                screenshotSucceeded = false,
                ocrNotAvailable = false,
                ocrErrored = false
            )
        )
    }

    @Test
    fun staleFrameFlagIgnoredWhenScreenshotActuallySucceeded() {
        // staleFrame 仅描述「截图未成功」时的画面新鲜度问题；
        // 截图已成功时该标记不得改写最终失败阶段。
        assertEquals(
            "ocr_empty",
            classifyOcrFailureStage(
                managerActive = true,
                screenshotAttempted = true,
                screenshotSucceeded = true,
                ocrNotAvailable = false,
                ocrErrored = false,
                staleFrame = true
            )
        )
    }

    @Test
    fun failureReasonTextForStaleFrameMentionsNewFrame() {
        assertTrue(failureReasonText("frame_not_fresh").contains("新画面"))
    }

    // ===== OCR 重试诊断指标（不含原始题目文本） =====

    @Test
    fun countScreenTextLinesOnlyCountsNonBlankLines() {
        assertEquals(3, countScreenTextLines("a\n\n  \nb\nc"))
        assertEquals(0, countScreenTextLines("  \n \n "))
    }

    @Test
    fun countOptionMarkersCountsStructureNotOriginalText() {
        val raw = "1. 下列关于 xx 的说法正确的是\nA. 选项一\nB、选项二\nC．选项三\nD）选项四\n随便一句话"
        assertEquals("行数应包含题干与四个选项及尾行", 6, countScreenTextLines(raw))
        assertEquals("应识别到 A/B/C/D 四个选项标记", 4, countOptionMarkers(raw))
        // 只有界面碎片、没有选项结构的识别结果：选项标记数应为 0。
        assertEquals(0, countOptionMarkers("返回 首页 设置 搜索"))
    }

    @Test
    fun ocrFingerprintIsStableShortAndDistinct() {
        assertEquals(ocrFingerprintOf("same"), ocrFingerprintOf("same"))
        assertNotEquals(ocrFingerprintOf("question A"), ocrFingerprintOf("question B"))
        assertEquals("指纹截断为 8 位十六进制", 8, ocrFingerprintOf("question").length)
    }

    @Test
    fun screenReadReportLinesIncludeOcrDetailAndFingerprint() {
        val report = ScreenReadReport(
            traceId = "t",
            status = ScreenReadStatus.SEND_INJECTED,
            ocrCharCount = 120,
            ocrLineCount = 6,
            optionMarkerCount = 4,
            ocrFingerprint = "abcd1234",
            readAtMillis = 1L
        )
        val lines = screenReadReportLines(report)
        assertTrue(
            "应展示不含原文的 OCR 明细（行数/选项标记）",
            lines.any { it.startsWith("OCR 明细:") && it.contains("行数=6") && it.contains("选项标记=4") }
        )
        assertTrue(lines.any { it == "OCR 指纹: abcd1234" })
    }

    @Test
    fun failureReasonTextDistinguishesReadFailureFromInject() {
        assertTrue(failureReasonText("capture_failed").contains("截图"))
        assertTrue(failureReasonText("ocr_empty").contains("未识别到屏幕文字"))
        assertTrue(failureReasonText("ocr_not_available").contains("不支持"))
        assertEquals("当前屏幕内容暂时无法获取，请切换回题目页面后重试。", failureReasonText(null))
    }

    // ===== reportBelongsToCurrentSend：诊断面板报告归属（纯函数） =====

    @Test
    fun reportBelongsToCurrentSendOnlyWhenTraceMatchesCurrentRequest() {
        val report = ScreenReadReport(
            traceId = "req-1",
            status = ScreenReadStatus.SEND_INJECTED,
            readAtMillis = 1L
        )
        assertTrue(reportBelongsToCurrentSend(report, "req-1"))
        // 上一次请求遗留的旧报告不得被当作本次发送的结果。
        assertFalse(reportBelongsToCurrentSend(report, "req-2"))
        // 尚无助手消息、或后台捕获（traceId 为空）都不得标为「本次发送」。
        assertFalse(reportBelongsToCurrentSend(report, null))
        assertFalse(reportBelongsToCurrentSend(report.copy(traceId = ""), "req-1"))
        assertFalse(reportBelongsToCurrentSend(null, "req-1"))
    }

    @Test
    fun screenReadReportLinesFormatsReadTimeAsClockInsteadOfRawMillis() {
        val zero = ScreenReadReport(
            traceId = "t",
            status = ScreenReadStatus.CAPTURED,
            readAtMillis = 0L
        )
        assertTrue(screenReadReportLines(zero).any { it == "读取时间: -" })

        val lines = screenReadReportLines(zero.copy(readAtMillis = 20_000L))
        assertTrue(lines.any { it.startsWith("读取时间: ") && !it.contains("20000") })
    }

    // ===== 实时互动运行期门控 + 截图会话缺失原因区分（纯函数） =====

    @Test
    fun realTimeModeSelectedWithoutRuntimeDoesNotResolveScreenContext() {
        // 场景 A：用户选了实时互动模式，但实时互动服务并未启动
        // → 不得进入屏幕读取流程，普通聊天按普通请求处理（不触发截图）。
        assertFalse(shouldResolveScreenContext(realTimeMode = true, realTimeRuntimeRunning = false))
    }

    @Test
    fun realTimeRuntimeRunningKeepsExistingScreenContextFlow() {
        // 场景 B：实时互动服务确实在运行 → 保持既有屏幕读取/OCR 行为，不得因本次修复被绕过。
        assertTrue(shouldResolveScreenContext(realTimeMode = true, realTimeRuntimeRunning = true))
    }

    @Test
    fun realTimeModeOffNeverResolvesEvenWhenOverlayRuns() {
        // 未选择实时互动时（即便悬浮球因其它模式在运行）一律按普通聊天。
        assertFalse(shouldResolveScreenContext(realTimeMode = false, realTimeRuntimeRunning = true))
        assertFalse(shouldResolveScreenContext(realTimeMode = false, realTimeRuntimeRunning = false))
    }

    @Test
    fun captureSessionMissingStageDistinguishesAuthorizationFromRuntimeLoss() {
        // 从未建立过会话 → 授权问题；曾建立过但当前缺失 → 运行期丢失。两者不得共用同一阶段。
        assertEquals("screenshot_not_authorized", classifyCaptureMissingStage(sessionEverEstablished = false))
        assertEquals("capture_session_lost", classifyCaptureMissingStage(sessionEverEstablished = true))
    }

    @Test
    fun captureMissingReasonsMatchTheRealCauseInsteadOfAlwaysBlamingPermission() {
        val lost = failureReasonText("capture_session_lost")
        val notAuthorized = failureReasonText("screenshot_not_authorized")
        assertNotEquals("运行期会话丢失的提示不得与尚未授权完全一致", notAuthorized, lost)
        assertTrue("运行期丢失提示应说明会话已在运行中失效", lost.contains("运行中失效"))
        assertTrue("尚未授权提示才引导完成投屏授权", notAuthorized.contains("投屏授权"))
        // 运行期丢失不属于权限问题：不得再出现「请先完成屏幕截图授权」这类归因。
        assertFalse("运行期丢失不得归因为用户没有授权", lost.contains("请先完成"))
    }
}