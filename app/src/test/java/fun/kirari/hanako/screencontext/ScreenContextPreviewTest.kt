package `fun`.kirari.hanako.screencontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本机 OCR 预览判定的纯函数单元测试（不联网）。
 *
 * 核心约束：**字符数大于零不等于题目识别成功**。
 * 面板必须能区分「没有 OCR 结果」「OCR 有文字但请求未使用」「OCR 有文字但选项缺失」三种情况。
 */
class ScreenContextPreviewTest {

    private fun report(
        status: ScreenReadStatus = ScreenReadStatus.SEND_INJECTED,
        injected: Boolean = true,
        injectedText: String? = null,
        charCount: Int = 0,
        optionMarkerCount: Int? = null,
        optionAssociation: String? = null,
        failureStage: String? = null
    ) = ScreenReadReport(
        traceId = "trace-1",
        status = status,
        source = ScreenContextSource.LOCAL_OCR,
        charCount = charCount,
        injected = injected,
        injectedText = injectedText,
        optionMarkerCount = optionMarkerCount,
        optionAssociation = optionAssociation,
        failureStage = failureStage,
        readAtMillis = 1L
    )

    @Test
    fun null_report_is_reported_as_no_record() {
        assertEquals("没有屏幕读取记录", screenContextPreviewDiagnosis(null))
    }

    @Test
    fun empty_ocr_result_is_not_treated_as_success() {
        val diagnosis = screenContextPreviewDiagnosis(report(injected = true, injectedText = null, charCount = 0))
        assertTrue("空 OCR 结果必须明确说明没有 OCR 结果", diagnosis.contains("没有 OCR 结果"))
    }

    @Test
    fun text_read_but_not_injected_is_distinguished_from_injected() {
        val diagnosis = screenContextPreviewDiagnosis(
            report(injected = false, injectedText = "读取到的文字", charCount = 120)
        )
        assertTrue("读取到文字但请求未使用必须明确区分", diagnosis.contains("未使用该屏幕文本"))
        assertTrue(diagnosis.contains("120"))
    }

    @Test
    fun injected_without_option_markers_warns_instead_of_claiming_success() {
        val longText = "本题题干".repeat(100) // 400 字，无任何 A./B. 选项标记
        val diagnosis = screenContextPreviewDiagnosis(
            report(injected = true, injectedText = longText, charCount = longText.length, optionMarkerCount = 0)
        )
        assertTrue(diagnosis.contains("未识别到任何选项标记"))
        assertTrue("必须明确说明不能视为识别成功", diagnosis.contains("不能视为识别成功"))
    }

    @Test
    fun injected_with_option_markers_reports_marker_count() {
        val diagnosis = screenContextPreviewDiagnosis(
            report(injected = true, injectedText = "A.1 B.2 C.3 D.4", charCount = 17, optionMarkerCount = 4)
        )
        assertTrue(diagnosis.contains("选项标记 4"))
    }

    @Test
    fun injected_with_unreliable_option_association_warns_instead_of_claiming_reliable_options() {
        // 检出 4 个标签但关联不确定（例如标签与正文无法按坐标可靠对应）时，
        // 不能只报「选项标记 4」就当成功，必须明确说明关联不确定。
        val diagnosis = screenContextPreviewDiagnosis(
            report(
                injected = true,
                injectedText = "A.1 B.2 C.3 D.4",
                charCount = 17,
                optionMarkerCount = 4,
                optionAssociation = OcrOptionAssociation.PARTIAL.name
            )
        )
        assertTrue(diagnosis.contains("选项标记 4"))
        assertTrue("关联不确定必须明确提示", diagnosis.contains("关联不确定"))
        assertTrue(diagnosis.contains("不能当作可靠选项"))
    }

    @Test
    fun failed_send_reports_failure_stage_without_claiming_injection() {
        val diagnosis = screenContextPreviewDiagnosis(
            report(
                status = ScreenReadStatus.SEND_FAILED,
                injected = false,
                injectedText = null,
                charCount = 0,
                failureStage = "ocr_empty"
            )
        )
        assertTrue(diagnosis.contains("未注入任何屏幕文本"))
        assertTrue(diagnosis.contains("未识别到屏幕文字"))
    }

    @Test
    fun report_lines_include_preview_and_truncate_long_text() {
        val longText = "题干".repeat(500) // 1000 字
        val lines = screenReadReportLines(
            report(injected = true, injectedText = longText, charCount = longText.length, optionMarkerCount = 0)
        )
        val joined = lines.joinToString("\n")

        assertTrue("诊断行必须包含预览判定", joined.contains("预览判定:"))
        assertTrue("诊断行必须展示真正注入前的文本", joined.contains("注入文本（前 $PREVIEW_MAX_CHARS 字）:"))
        assertTrue("超长文本必须截断展示", joined.contains("已截断展示"))
        assertTrue(joined.contains("共 ${longText.length} 字"))
    }

    @Test
    fun preview_lines_for_null_report_are_empty() {
        assertTrue(screenContextPreviewLines(null).isEmpty())
    }

    // ========================
    // 请求级快照：完整文本查看界面的数据来源
    // ========================

    @Test
    fun request_report_is_bound_to_its_trace_id_and_never_falls_back() {
        val manager = ScreenContextManager()
        val reportB = requestReport(traceId = "trace-B", injectedText = "题目B：下列哪个是质数？", charCount = 12)

        manager.recordRead(reportB)

        // 查看题目 B 时必须取到 B 自己那次请求的快照。
        assertSame(reportB, manager.reportFor("trace-B"))
        // 未记录过的 traceId 一律返回 null：界面据此显示「无记录」，不得回退展示当前屏幕或旧文本。
        assertNull(manager.reportFor("trace-A"))
        assertNull(manager.reportFor(null))
        assertNull(manager.reportFor(""))
        assertNull(manager.reportFor("   "))
    }

    @Test
    fun request_report_keeps_full_injected_text_without_preview_truncation() {
        val manager = ScreenContextManager()
        val fullText = "题干".repeat(500) // 1000 字，远超预览上限
        manager.recordRead(
            requestReport(traceId = "trace-full", injectedText = fullText, charCount = fullText.length)
        )

        val restored = manager.reportFor("trace-full")
        assertEquals("请求级快照必须保存完整注入文本，不得预先截断", fullText, restored?.injectedText)
        assertEquals(fullText.length, restored?.injectedText?.length)

        // 面板预览仍按上限截断（预览与完整文本职责不同，两者都保留）。
        val previewJoined = screenContextPreviewLines(restored).joinToString("\n")
        assertTrue(previewJoined.contains("已截断展示"))
    }

    @Test
    fun request_report_for_same_trace_id_keeps_latest_state() {
        val manager = ScreenContextManager()
        manager.recordRead(
            requestReport(traceId = "trace-1", status = ScreenReadStatus.CONTEXT_AVAILABLE, injectedText = "题目A")
        )
        manager.recordRead(
            requestReport(traceId = "trace-1", status = ScreenReadStatus.SEND_INJECTED, injectedText = "题目A")
        )

        assertEquals(ScreenReadStatus.SEND_INJECTED, manager.reportFor("trace-1")?.status)
    }

    @Test
    fun background_report_with_blank_trace_id_is_not_registered_as_a_request() {
        val manager = ScreenContextManager()
        // 后台捕获类报告的 traceId 为空串，不能被当成某次请求的结果。
        manager.recordRead(
            ScreenReadReport(
                traceId = "",
                status = ScreenReadStatus.CAPTURED,
                source = ScreenContextSource.ACCESSIBILITY,
                charCount = 30,
                readAtMillis = 1L
            )
        )

        assertNull(manager.reportFor(""))
        assertNull(manager.reportFor("trace-1"))
    }

    @Test
    fun request_report_registry_is_bounded_and_evicts_oldest() {
        val manager = ScreenContextManager()
        repeat(ScreenContextManager.REQUEST_REPORT_LIMIT + 4) { index ->
            manager.recordRead(requestReport(traceId = "trace-$index", injectedText = "题目$index"))
        }

        val newest = "trace-${ScreenContextManager.REQUEST_REPORT_LIMIT + 3}"
        assertEquals("最新的请求快照必须保留", "题目${ScreenContextManager.REQUEST_REPORT_LIMIT + 3}", manager.reportFor(newest)?.injectedText)
        assertNull("超出上限的最早请求快照应被淘汰", manager.reportFor("trace-0"))
        assertNull(manager.reportFor("trace-3"))
    }

    @Test
    fun request_reports_are_cleared_when_manager_stops() {
        val manager = ScreenContextManager()
        manager.start()
        manager.recordRead(requestReport(traceId = "trace-x", injectedText = "题目X"))

        manager.stop()

        assertNull("关闭实时感知后不得继续保留请求级屏幕文本", manager.reportFor("trace-x"))
        assertNull(manager.lastReport.value)
    }

    private fun requestReport(
        traceId: String,
        status: ScreenReadStatus = ScreenReadStatus.SEND_INJECTED,
        injectedText: String? = null,
        charCount: Int = injectedText?.length ?: 0
    ) = ScreenReadReport(
        traceId = traceId,
        status = status,
        source = ScreenContextSource.LOCAL_OCR,
        charCount = charCount,
        injected = status == ScreenReadStatus.SEND_INJECTED,
        injectedText = injectedText,
        optionMarkerCount = 0,
        readAtMillis = 1L
    )
}
