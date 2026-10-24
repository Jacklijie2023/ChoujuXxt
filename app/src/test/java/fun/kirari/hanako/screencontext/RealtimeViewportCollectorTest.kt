package `fun`.kirari.hanako.screencontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 2「接入持续采集与文本池」的纯逻辑单元测试。
 *
 * 覆盖范围（全部**不联网、不依赖真机截图、不依赖 Android 运行时**）：
 * 实时模式门控、采集频率与 OCR 单飞、乱序结果丢弃、跨题隔离、失败不覆盖、
 * 采集本身不触发 OCR / 模型请求、能力降级与只读诊断。
 *
 * 注意：本文件只验证**接入层逻辑**。「原有单次 OCR 聊天链路保持不变」属于接线属性，
 * 由源码核查 + 既有测试/构建共同保证，不在此处伪造断言。
 */
class RealtimeViewportCollectorTest {

    private val pkg = "com.example.questionapp"

    private val screen1 = listOf(
        "下列关于光合作用的说法，正确的是",
        "A 叶绿体是光合作用的场所",
        "B 呼吸作用只在夜间进行",
        "C 光合作用释放二氧化碳",
        "D 光合作用需要光照"
    ).joinToString("\n")

    private val unrelatedScreen = "计算该物体在水平方向上的加速度大小"

    /** 相邻边界连续重叠 3 行的两屏（用于验证「同题滚动 → 重叠合并、不重复」）。 */
    private val page1 = listOf("第 3/10 题", "下列说法正确的是", "A 选项甲", "B 选项乙").joinToString("\n")
    private val page2 = listOf("下列说法正确的是", "A 选项甲", "B 选项乙", "C 选项丙", "D 选项丁").joinToString("\n")

    /** 两道正向证据明确不同的题目（题号不同）。 */
    private val questionOne = listOf(
        "第 1/10 题", "下列关于光合作用的说法，正确的是", "A 选项甲", "B 选项乙"
    ).joinToString("\n")
    private val questionTwo = listOf(
        "第 2/10 题", "关于细胞呼吸的叙述，错误的是", "A 选项丙", "B 选项丁"
    ).joinToString("\n")

    private fun collector(): RealtimeViewportCollector = RealtimeViewportCollector()

    /** 构造一个带坐标的 OCR 整理结果（顺序自上而下，保证 comparable=true）。 */
    private fun organizationOf(vararg lines: String): OcrOrganization {
        val items = lines.mapIndexed { index, text ->
            OcrTextLine(
                text = text,
                left = 20,
                top = 100 + index * 40,
                right = 600,
                bottom = 130 + index * 40
            )
        }
        return organizeOcrText(OcrResult(rawText = lines.joinToString("\n"), lines = items))
    }

    // ============================ 1. 实时模式门控 ============================

    @Test
    fun realtime_enabled_allows_pool_updates() {
        val collector = collector()
        collector.setEnabled(true)

        val outcome = collector.onAccessibilityText(screen1, pkg, 1_000L)

        assertEquals(PoolUpdateResult.NEW_QUESTION, outcome.result)
        assertTrue("实时模式启用时本次观察必须真正作用到文本池", outcome.applied)
        assertEquals(RealtimeCollectSource.ACCESSIBILITY, outcome.source)
        assertEquals(1, collector.status().fragmentCount)
        assertTrue(collector.status().poolText.contains("光合作用"))
    }

    @Test
    fun realtime_disabled_rejects_new_collection_tasks() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityText(screen1, pkg, 1_000L)

        collector.setEnabled(false)

        // 关闭后：既有池内容释放，后续观察被拒绝，且不会再启动新的 OCR 采集任务。
        assertEquals(0, collector.status().fragmentCount)
        assertTrue(collector.status().poolText.isBlank())
        val rejected = collector.onAccessibilityText(questionTwo, pkg, 9_000L)
        assertFalse("实时模式停止后不得再更新文本池", rejected.applied)
        assertFalse(collector.isEnabled())
        assertNull("实时模式停止后不得再启动新的 OCR 采集任务", collector.beginOcrAttempt(9_000L))
        assertFalse(collector.shouldTriggerOcr(PoolUpdateResult.UNSTABLE, 9_000L))
    }

    // ====================== 2. 重复事件不重复触发 OCR ======================

    @Test
    fun repeated_accessibility_events_do_not_retrigger_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        val first = collector.onAccessibilityText(screen1, pkg, 1_000L)
        assertEquals(PoolUpdateResult.NEW_QUESTION, first.result)
        assertFalse("首次进池本身不是「确有必要」的 OCR 理由", collector.shouldTriggerOcr(first.result, 1_000L))

        // 同一画面重复上报：无实质变化，既不追加内容，也不要求补 OCR。
        val repeated = collector.onAccessibilityText(screen1, pkg, 5_000L)
        assertEquals(PoolUpdateResult.NO_CHANGE, repeated.result)
        assertFalse("无实质变化不得触发 OCR", collector.shouldTriggerOcr(repeated.result, 5_000L))
        assertEquals("重复上报不得让池无限增长", 1, collector.status().fragmentCount)

        // 只有「无法证明同题重叠 / 关联未知」的结果才值得补一次 OCR。
        val unstable = collector.onAccessibilityText(unrelatedScreen, pkg, 9_000L)
        assertEquals(PoolUpdateResult.UNPROVEN, unstable.result)
        assertTrue("关联未知结果才是「确有必要」的 OCR 触发条件", collector.shouldTriggerOcr(unstable.result, 9_000L))
    }

    // ==================== 3. OCR 串行（单飞）与频率限制 ====================

    @Test
    fun ocr_runs_serially_and_respects_min_interval() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        val first = collector.beginOcrAttempt(1_000L)
        assertNotNull(first)
        assertNull(
            "单飞：同一时刻只允许一个在途 OCR 任务",
            collector.beginOcrAttempt(1_000L)
        )

        collector.failOcrAttempt(first!!, 1_500L)

        assertNull(
            "频率限制：距上次截图 OCR 不足最小间隔时不得再次触发",
            collector.beginOcrAttempt(2_000L)
        )
        assertNotNull(
            "超过最小间隔后允许再次触发",
            collector.beginOcrAttempt(1_000L + RealtimeViewportCollector.DEFAULT_MIN_OCR_INTERVAL_MILLIS)
        )
    }

    // ======================= 4. 乱序结果不得破坏视口顺序 =======================

    @Test
    fun out_of_order_ocr_results_do_not_overwrite_newer_content() {
        val collector = collector()
        collector.setEnabled(true)

        // 较新序号（2）先到：建立当前内容。
        val newer = collector.completeOcrAttempt(
            2L, organizationOf("甲组内容", "乙组内容", "丙组内容"), null, pkg, 2_000L
        )
        assertEquals(PoolUpdateResult.NEW_QUESTION, newer.result)
        assertTrue(newer.applied)
        val newerText = collector.status().poolText

        // 较早序号（1）迟到：必须丢弃，绝不覆盖已经更新的池内容。
        val stale = collector.completeOcrAttempt(
            1L, organizationOf("丁组内容", "戊组内容", "己组内容"), null, pkg, 3_000L
        )
        assertFalse("乱序迟到的旧结果不得作用到文本池", stale.applied)
        assertEquals("池内容必须保持较新序号的那一份", newerText, collector.status().poolText)
        assertFalse(collector.status().poolText.contains("丁组内容"))
    }

    // ============ 4.1 在途 OCR 只被「真正改变池内容」的观察挤掉（R2） ============

    @Test
    fun in_flight_ocr_is_not_dropped_by_unrelated_accessibility_events() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        // 无障碍先建立上下文（这一次观察**真正改变**了池内容）。
        assertEquals(PoolUpdateResult.NEW_QUESTION, collector.onAccessibilityText(screen1, pkg, 1_000L).result)

        // 取得在途 OCR 序号后，同题的无障碍观察继续上报：一次「无实质变化」、一次「关联未知」。
        // 两者都没有携带任何新内容，不得把在途 OCR 的序号顶掉。
        val sequence = collector.beginOcrAttempt(2_000L)
        assertNotNull(sequence)
        assertEquals(PoolUpdateResult.NO_CHANGE, collector.onAccessibilityText(screen1, pkg, 2_500L).result)
        assertEquals(PoolUpdateResult.UNPROVEN, collector.onAccessibilityText(unrelatedScreen, pkg, 3_000L).result)

        val completed = collector.completeOcrAttempt(
            sequence!!,
            organizationOf(
                "下列关于光合作用的说法，正确的是",
                "A 叶绿体是光合作用的场所",
                "B 呼吸作用只在夜间进行",
                "C 光合作用释放二氧化碳",
                "D 光合作用需要光照",
                "E 光合作用不需要水"
            ),
            null,
            pkg,
            3_100L
        )

        assertEquals(PoolUpdateResult.OVERLAP_MERGED, completed.result)
        assertTrue("同题的重复上报不得把在途 OCR 挤掉", completed.applied)
        assertTrue("本次 OCR 必须真正改变文本池内容", completed.poolContentChanged)
        assertTrue(collector.status().poolText.contains("光合作用不需要水"))
    }

    @Test
    fun in_flight_ocr_is_dropped_when_the_question_changed_while_it_was_running() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(questionOne, pkg, 1_000L)
        // 为「第 1/10 题」启动的这一次 OCR。
        val sequence = collector.beginOcrAttempt(2_000L)
        assertNotNull(sequence)

        // 在途期间换题：这一次无障碍观察**真正改变**了池内容（建立新题）。
        assertEquals(PoolUpdateResult.NEW_QUESTION, collector.onAccessibilityText(questionTwo, pkg, 3_000L).result)

        val completed = collector.completeOcrAttempt(
            sequence!!,
            organizationOf("第 1/10 题", "下列关于光合作用的说法，正确的是", "A 选项甲", "B 选项乙"),
            null,
            pkg,
            3_100L
        )

        assertFalse("旧题的 OCR 帧不得覆盖已经切换后的新题内容", completed.applied)
        assertFalse(completed.poolContentChanged)
        assertFalse("不得把旧题内容混入新题", collector.status().poolText.contains("光合作用"))
        assertTrue(
            "必须能区分「因过期被丢弃」与其他情况",
            completed.note?.contains("已丢弃") == true
        )
    }

    @Test
    fun ocr_failure_stale_drop_and_normal_accept_are_distinguishable() {
        val collector = collector()
        collector.setEnabled(true)

        // ① 正常接受：真正改变池内容。
        val accepted = collector.completeOcrAttempt(
            1L, organizationOf("甲组内容", "乙组内容", "丙组内容"), null, pkg, 1_000L
        )
        assertEquals(PoolUpdateResult.NEW_QUESTION, accepted.result)
        assertTrue(accepted.applied)
        assertTrue(accepted.poolContentChanged)
        assertEquals(RealtimeCollectSource.LOCAL_OCR, accepted.source)

        // ② 因过期被丢弃：序号早于「已接受序号」（在途期间池内容已被真正改变）。
        val stale = collector.completeOcrAttempt(
            0L, organizationOf("丁组内容", "戊组内容"), null, pkg, 2_000L
        )
        assertEquals(PoolUpdateResult.UNSTABLE, stale.result)
        assertFalse("过期丢弃不得作用到文本池", stale.applied)
        assertFalse(stale.poolContentChanged)
        assertTrue(stale.note?.contains("已丢弃") == true)

        // ③ OCR 失败：根本没有识别结果。
        val failed = collector.completeOcrAttempt(999L, null, null, pkg, 3_000L)
        assertEquals(PoolUpdateResult.OCR_FAILED, failed.result)
        assertFalse(failed.applied)
        assertFalse(failed.poolContentChanged)

        // 三者必须可以区分：接受 / 过期丢弃 / 失败。
        assertTrue("正常接受与过期丢弃必须不同", accepted.applied && !stale.applied)
        assertTrue("过期丢弃与识别失败必须不同", stale.result != failed.result)
        assertFalse("失败不得补写文字", collector.status().poolText.contains("丁组内容"))
    }

    // ============================ 5. 跨题隔离 ============================

    @Test
    fun new_question_does_not_inherit_previous_pool_content() {
        val collector = collector()
        collector.setEnabled(true)

        collector.onAccessibilityText(questionOne, pkg, 1_000L)
        val second = collector.onAccessibilityText(questionTwo, pkg, 5_000L)

        assertEquals(PoolUpdateResult.NEW_QUESTION, second.result)
        val poolText = collector.status().poolText
        assertTrue("新题内容应进入文本池", poolText.contains("细胞呼吸"))
        assertFalse("新题不得继承旧题文本", poolText.contains("光合作用"))
        assertEquals("换题后应重置片段集合", 1, collector.status().fragmentCount)
        assertEquals(2, collector.status().questionNumber)
    }

    @Test
    fun adjacent_viewports_of_the_same_question_merge_without_duplication() {
        val collector = collector()
        collector.setEnabled(true)

        collector.onAccessibilityText(page1, pkg, 1_000L)
        val merged = collector.onAccessibilityText(page2, pkg, 5_000L)

        assertEquals(PoolUpdateResult.OVERLAP_MERGED, merged.result)
        val lines = collector.status().poolLines
        assertEquals("题干 + 四个选项应连续且不重复", 6, lines.size)
        assertEquals(1, lines.count { it.contains("A 选项甲") })
        assertTrue(lines.last().contains("D 选项丁"))
    }

    // ======================= 6. 失败不得覆盖有效文本 =======================

    @Test
    fun ocr_failure_does_not_overwrite_existing_valid_text() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityText(screen1, pkg, 1_000L)
        val before = collector.status().poolText

        val sequence = collector.beginOcrAttempt(2_000L)
        assertNotNull(sequence)
        val failed = collector.failOcrAttempt(sequence!!, 2_100L)
        assertEquals(PoolUpdateResult.OCR_FAILED, failed.result)
        assertFalse(failed.applied)
        assertEquals("OCR 执行失败不得改动既有有效文本", before, collector.status().poolText)

        // OCR 返回空/无结果同样只记录失败，不伪装成一次成功更新。
        val empty = collector.completeOcrAttempt(100L, null, null, pkg, 3_000L)
        assertEquals(PoolUpdateResult.OCR_FAILED, empty.result)
        assertFalse(empty.applied)
        assertEquals(before, collector.status().poolText)
    }

    @Test
    fun blank_accessibility_text_does_not_pretend_to_be_latest_text() {
        val collector = collector()
        collector.setEnabled(true)
        collector.onAccessibilityText(screen1, pkg, 1_000L)
        val before = collector.status().poolText

        val blank = collector.onAccessibilityText("   \n\t  ", pkg, 5_000L)

        assertEquals(PoolUpdateResult.OCR_FAILED, blank.result)
        assertFalse("没读到有效文字不得当作一次成功更新", blank.applied)
        assertEquals(before, collector.status().poolText)
        assertFalse(
            "没有可证明的证据时不得强制合并",
            collector.onAccessibilityText(unrelatedScreen, pkg, 9_000L).result == PoolUpdateResult.APPENDED
        )
    }

    // ================= 7. 更新文本池不会触发采集以外的副作用 =================

    @Test
    fun pool_update_never_triggers_ocr_or_model_request() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        val outcome = collector.onAccessibilityText(screen1, pkg, 1_000L)

        assertTrue(outcome.applied)
        // 采集器只更新本地文本池：进池动作本身不发起截屏 OCR，
        // 更不可能发起模型请求（本类无任何网络 / 模型 / 聊天依赖，属结构性保证）。
        assertNull("进池不得自动触发截图 OCR", collector.status().lastOcrAtMillis)
        assertFalse(collector.status().ocrInFlight)
        assertEquals("进池只累积文本，不产生多余的空行", 5, collector.status().poolLines.size)
    }

    // ======================= 8. Lite flavor 能力降级 =======================

    @Test
    fun lite_degradation_marks_collection_degraded_and_skips_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(false, "当前版本不支持本地文字识别（Lite），持续采集仅使用无障碍节点树文本")

        // 能力不可用时：即使池判定为不确定，也不得调用不存在的本地 OCR 实现。
        assertFalse(collector.shouldTriggerOcr(PoolUpdateResult.UNSTABLE, 1_000L))
        assertNull(collector.beginOcrAttempt(1_000L))

        val status = collector.status()
        assertFalse(status.ocrAvailable)
        assertNotNull(status.degradedNote)
        // 降级只影响 OCR 分支，无障碍节点树文本主路径仍应正常积累。
        assertTrue(collector.onAccessibilityText(screen1, pkg, 1_000L).applied)
    }

    // ============================ 9. 只读诊断 ============================

    @Test
    fun diagnostic_lines_reflect_collection_state() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(false, "Lite 降级说明")
        collector.onAccessibilityText(screen1, pkg, 1_000L)

        val text = realtimeCollectionDiagnosticLines(collector.status()).joinToString("\n")

        assertTrue(text.contains("实时采集: 已启用"))
        assertTrue(text.contains("本地 OCR 能力: 不可用（能力降级）"))
        assertTrue(text.contains("Lite 降级说明"))
        assertTrue(text.contains("累计视口片段数量: 1"))
        assertTrue(text.contains("当前题目上下文标识: q-1"))
        assertTrue(text.contains("最近一次分类与合并结果: NEW_QUESTION"))
        assertTrue(text.contains("当前文本池行数: 5"))
        assertTrue(text.contains("当前文本池完整度: INCOMPLETE"))
    }

    @Test
    fun diagnostic_lines_report_disabled_state_after_shutdown() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setEnabled(false)

        val text = realtimeCollectionDiagnosticLines(collector.status()).joinToString("\n")

        assertTrue(text.contains("实时采集: 未启用"))
        assertTrue(text.contains("累计视口片段数量: 0"))
        assertTrue(text.contains("当前文本池完整度: UNKNOWN（尚未采集到任何视口片段）"))
    }

    // ============ 10. 滚动事件元数据（验收前补充：滚动但无障碍文本未刷新） ============

    /** 列表型滚动位置（提供 fromIndex / toIndex，位置可靠）。 */
    private fun scrollItems(fromIndex: Int, toIndex: Int, itemCount: Int = 100) =
        RealtimeScrollSignal(fromIndex, toIndex, itemCount, scrollY = 0, maxScrollY = 0)

    /** 像素型滚动位置（提供 scrollY / maxScrollY，位置可靠）。 */
    private fun scrollPixels(scrollY: Int, maxScrollY: Int) =
        RealtimeScrollSignal(fromIndex = -1, toIndex = -1, itemCount = 0, scrollY = scrollY, maxScrollY = maxScrollY)

    /** 缺少可靠位置信息的滚动事件（安全降级用例）。 */
    private val scrollWithoutLocation = RealtimeScrollSignal(-1, -1, 0, 0, 0)

    @Test
    fun scroll_signal_with_unchanged_text_allows_controlled_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        val first = collector.onAccessibilityText(screen1, pkg, 1_000L, scrollItems(0, 4))
        assertEquals(PoolUpdateResult.NEW_QUESTION, first.result)
        assertFalse("首次进池本身不是补 OCR 的理由", collector.shouldTriggerOcr(first.result, 1_000L))

        // 用户确实滚动（位置 0-4 → 5-9），但无障碍文本与上一轮完全相同。
        val scrolled = collector.onAccessibilityText(screen1, pkg, 4_000L, scrollItems(5, 9))
        assertEquals(PoolUpdateResult.NO_CHANGE, scrolled.result)
        assertEquals(RealtimeScrollDecision.ADVANCED, collector.status().lastScrollDecision)
        assertTrue(
            "真实滚动 + 无障碍文本未变化时，应允许通过受控 OCR 获取当前画面",
            collector.shouldTriggerOcr(scrolled.result, 4_000L)
        )
        assertNotNull(collector.beginOcrAttempt(4_000L))
    }

    @Test
    fun unchanged_text_without_scroll_signal_does_not_trigger_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L)
        val again = collector.onAccessibilityText(screen1, pkg, 4_000L)

        assertEquals(PoolUpdateResult.NO_CHANGE, again.result)
        assertEquals(RealtimeScrollDecision.NOT_SCROLL, collector.status().lastScrollDecision)
        assertFalse(
            "没有真实滚动信号且文本未变化时不得触发 OCR",
            collector.shouldTriggerOcr(again.result, 4_000L)
        )
    }

    @Test
    fun repeated_same_scroll_event_does_not_retrigger_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L, scrollItems(0, 4))
        val advanced = collector.onAccessibilityText(screen1, pkg, 4_000L, scrollItems(5, 9))
        assertTrue(collector.shouldTriggerOcr(advanced.result, 4_000L))

        // 同一滚动位置被重复分发：不得反复启动 OCR。
        val repeat1 = collector.onAccessibilityText(screen1, pkg, 4_500L, scrollItems(5, 9))
        val repeat2 = collector.onAccessibilityText(screen1, pkg, 6_000L, scrollItems(5, 9))

        assertEquals(RealtimeScrollDecision.REPEAT, collector.status().lastScrollDecision)
        assertFalse("位置未变的重复分发不得触发 OCR", collector.shouldTriggerOcr(repeat1.result, 4_500L))
        assertFalse("位置未变的重复分发不得触发 OCR", collector.shouldTriggerOcr(repeat2.result, 6_000L))
    }

    @Test
    fun scroll_triggered_ocr_respects_min_interval_and_single_flight() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L, scrollItems(0, 4))
        val s1 = collector.onAccessibilityText(screen1, pkg, 4_000L, scrollItems(5, 9))
        assertTrue(collector.shouldTriggerOcr(s1.result, 4_000L))
        val sequence = collector.beginOcrAttempt(4_000L)
        assertNotNull(sequence)

        // 单飞：已有在途任务时不得再次触发。
        val s2 = collector.onAccessibilityText(screen1, pkg, 4_500L, scrollItems(10, 14))
        assertFalse("已有在途 OCR 任务时不得再次触发", collector.shouldTriggerOcr(s2.result, 4_500L))
        assertNull("单飞：同一时刻只允许一个在途 OCR", collector.beginOcrAttempt(4_500L))

        collector.failOcrAttempt(sequence!!, 4_600L)

        // 频率限制：距上次截图 OCR 不足 2 秒时不得再次触发。
        val s3 = collector.onAccessibilityText(screen1, pkg, 5_000L, scrollItems(15, 19))
        assertFalse("频率限制对滚动触发的 OCR 同样有效", collector.shouldTriggerOcr(s3.result, 5_000L))
        assertNotNull(
            "超过最小间隔后允许再次触发",
            collector.beginOcrAttempt(4_000L + RealtimeViewportCollector.DEFAULT_MIN_OCR_INTERVAL_MILLIS)
        )
    }

    @Test
    fun scroll_triggered_ocr_failure_keeps_pool_intact() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L, scrollItems(0, 4))
        val before = collector.status().poolText

        val s1 = collector.onAccessibilityText(screen1, pkg, 4_000L, scrollItems(5, 9))
        assertTrue(collector.shouldTriggerOcr(s1.result, 4_000L))
        val sequence = collector.beginOcrAttempt(4_000L)
        assertNotNull(sequence)

        val failed = collector.failOcrAttempt(sequence!!, 4_100L)
        assertEquals(PoolUpdateResult.OCR_FAILED, failed.result)
        assertFalse(failed.applied)
        assertEquals("滚动触发的 OCR 失败不得改动既有有效文本", before, collector.status().poolText)
    }

    @Test
    fun unknown_scroll_location_still_allows_rate_limited_ocr_without_fabricating_position() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L)
        // 目标应用确实发出了 TYPE_VIEW_SCROLLED，但事件未带可用的位置字段（真机资料分析题常见）。
        val s1 = collector.onAccessibilityText(
            screen1, pkg, 4_000L, scrollWithoutLocation,
            RealtimeEventKind.VIEW_SCROLLED, eventFromTargetWindow = true
        )

        assertEquals(PoolUpdateResult.NO_CHANGE, s1.result)
        assertEquals(RealtimeScrollDecision.UNKNOWN_LOCATION, collector.status().lastScrollDecision)
        assertNull("事件未提供可靠位置时不得虚构滚动位置", collector.status().lastScrollPositionKey)
        assertTrue(
            "确认发生了滚动但精确位置未知时，应允许按限频补一次 OCR（否则自动采集会静默失效）",
            collector.shouldTriggerOcr(s1.result, 4_000L)
        )
        assertNull("判定过后依然不得虚构滚动位置", collector.status().lastScrollPositionKey)
        assertNotNull("位置未知的滚动同样只允许单飞一次", collector.beginOcrAttempt(4_000L))
    }

    @Test
    fun non_scroll_event_is_distinguished_from_scroll_with_unknown_location() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L)
        // 内容变化事件（没有滚动元数据）：**无法确认发生过滚动**。
        val contentChanged = collector.onAccessibilityText(
            screen1, pkg, 4_000L, scroll = null,
            eventKind = RealtimeEventKind.WINDOW_CONTENT_CHANGED
        )
        assertEquals(RealtimeScrollDecision.NOT_SCROLL, collector.status().lastScrollDecision)
        assertEquals(RealtimeEventKind.WINDOW_CONTENT_CHANGED, collector.status().lastEventKind)
        assertFalse("无法确认发生滚动时不得触发 OCR", collector.shouldTriggerOcr(contentChanged.result, 4_000L))

        // 滚动事件但位置不可用：**确认发生滚动、位置未知** → 允许限频 OCR（与上面严格区分）。
        val scrolled = collector.onAccessibilityText(
            screen1, pkg, 9_000L, scrollWithoutLocation, RealtimeEventKind.VIEW_SCROLLED
        )
        assertEquals(RealtimeScrollDecision.UNKNOWN_LOCATION, collector.status().lastScrollDecision)
        assertTrue("两种「无位置」必须区分开", collector.shouldTriggerOcr(scrolled.result, 9_000L))
    }

    @Test
    fun repeated_unknown_location_scroll_events_are_merged_by_single_flight_and_rate_limit() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L)
        val a = collector.onAccessibilityText(screen1, pkg, 4_000L, scrollWithoutLocation)
        assertTrue(collector.shouldTriggerOcr(a.result, 4_000L))
        val first = collector.beginOcrAttempt(4_000L)
        assertNotNull(first)

        // 在途任务期间继续到达的滚动事件：单飞保护，不得启动第二个 OCR。
        val b = collector.onAccessibilityText(screen1, pkg, 4_200L, scrollWithoutLocation)
        assertFalse(collector.shouldTriggerOcr(b.result, 4_200L))
        assertNull("单飞：同一时刻只允许一个在途 OCR 任务", collector.beginOcrAttempt(4_200L))

        // 在途任务结束后，2 秒内的新滚动事件仍被频率限制合并掉。
        collector.failOcrAttempt(first!!, 4_300L)
        val c = collector.onAccessibilityText(screen1, pkg, 5_000L, scrollWithoutLocation)
        assertFalse(collector.shouldTriggerOcr(c.result, 5_000L))
        assertNull("频率限制：短时间重复滚动不得反复启动 OCR", collector.beginOcrAttempt(5_000L))

        // 超过最小间隔后恢复触发能力（是限频而不是永久禁用）。
        assertNotNull(
            collector.beginOcrAttempt(4_000L + RealtimeViewportCollector.DEFAULT_MIN_OCR_INTERVAL_MILLIS)
        )
    }

    @Test
    fun scroll_from_non_target_window_does_not_trigger_question_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L)
        // 丑橘自身悬浮窗的滚动：带滚动元数据，但来源不是目标刷题窗口。
        val ownScroll = collector.onAccessibilityText(
            screen1, pkg, 4_000L, scrollItems(5, 9),
            RealtimeEventKind.VIEW_SCROLLED, eventFromTargetWindow = false
        )

        assertEquals(PoolUpdateResult.NO_CHANGE, ownScroll.result)
        assertEquals(
            "非目标窗口的滚动不得被当作刷题页面滚动",
            RealtimeScrollDecision.NOT_SCROLL,
            collector.status().lastScrollDecision
        )
        assertNull("非目标窗口的滚动不得被记录为滚动位置", collector.status().lastScrollPositionKey)
        assertNull(
            "非目标窗口的滚动不得成为画面探测的抑制依据（它不算目标应用滚动）",
            collector.status().lastTargetScrollAtMillis
        )
        // 采集链路是「先过 shouldTriggerOcr，再预约 beginOcrAttempt」，因此这一步为 false 即代表不会截图。
        assertFalse("非目标窗口的滚动不得触发题目 OCR", collector.shouldTriggerOcr(ownScroll.result, 4_000L))

        val text = realtimeCollectionDiagnosticLines(collector.status()).joinToString("\n")
        assertTrue(text.contains("事件来源是否匹配目标窗口: 否"))
    }

    @Test
    fun pixel_scroll_position_is_accepted_as_reliable() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L, scrollPixels(0, 4_000))
        val scrolled = collector.onAccessibilityText(screen1, pkg, 4_000L, scrollPixels(800, 4_000))

        assertEquals(RealtimeScrollDecision.ADVANCED, collector.status().lastScrollDecision)
        assertTrue("像素型滚动位置同样可作为可靠证据", collector.shouldTriggerOcr(scrolled.result, 4_000L))
        assertEquals("px:800/4000", collector.status().lastScrollPositionKey)
    }

    @Test
    fun new_question_and_cross_question_isolation_unchanged_with_scroll_evidence() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(questionOne, pkg, 1_000L, scrollItems(0, 4))
        val switched = collector.onAccessibilityText(questionTwo, pkg, 5_000L, scrollItems(5, 9))

        assertEquals(PoolUpdateResult.NEW_QUESTION, switched.result)
        val poolText = collector.status().poolText
        assertTrue("新题内容应进入文本池", poolText.contains("细胞呼吸"))
        assertFalse("换题不得继承旧题文本（含滚动证据场景）", poolText.contains("光合作用"))
        assertEquals(1, collector.status().fragmentCount)
        assertFalse("换题已由正向证据确认，无需额外 OCR", collector.shouldTriggerOcr(switched.result, 5_000L))
    }

    @Test
    fun text_extension_still_merges_and_needs_no_ocr_with_scroll_signal() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(page1, pkg, 1_000L, scrollItems(0, 3))
        val merged = collector.onAccessibilityText(page2, pkg, 5_000L, scrollItems(4, 8))

        assertEquals(PoolUpdateResult.OVERLAP_MERGED, merged.result)
        assertEquals("题干 + 四个选项应连续且不重复", 6, collector.status().poolLines.size)
        assertFalse("文本已带来新增内容时无需补 OCR", collector.shouldTriggerOcr(merged.result, 5_000L))
    }

    @Test
    fun lite_degradation_skips_scroll_triggered_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(false, "Lite 降级")

        collector.onAccessibilityText(screen1, pkg, 1_000L, scrollItems(0, 4))
        val scrolled = collector.onAccessibilityText(screen1, pkg, 4_000L, scrollItems(5, 9))

        assertEquals(RealtimeScrollDecision.ADVANCED, collector.status().lastScrollDecision)
        assertFalse("能力降级时即使出现新滚动位置也不得触发 OCR", collector.shouldTriggerOcr(scrolled.result, 4_000L))
    }

    @Test
    fun diagnostic_lines_distinguish_scroll_states() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L, scrollItems(0, 4))
        collector.onAccessibilityText(screen1, pkg, 4_000L, scrollItems(5, 9))
        collector.shouldTriggerOcr(PoolUpdateResult.NO_CHANGE, 4_000L)

        val text = realtimeCollectionDiagnosticLines(collector.status()).joinToString("\n")
        assertTrue(text.contains("无障碍文本未变化，但出现了有效的滚动信号"))
        assertTrue(text.contains("最近一次滚动信号: 出现新的滚动位置"))
        assertTrue(text.contains("最近一次滚动位置: items:5-9/100"))
        assertTrue(text.contains("滚动位置是否可用: 可用"))
        assertTrue(text.contains("最近一次 OCR 触发/门控: OCR 已触发"))
        assertTrue(text.contains("最近一次接收到的无障碍事件类型:"))
        assertTrue(text.contains("画面变化探测回退:"))
    }

    @Test
    fun diagnostic_lines_report_frequency_limited_scroll_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L, scrollItems(0, 4))
        val s1 = collector.onAccessibilityText(screen1, pkg, 4_000L, scrollItems(5, 9))
        val sequence = collector.beginOcrAttempt(4_000L)
        assertNotNull(sequence)
        collector.failOcrAttempt(sequence!!, 4_100L)

        val s2 = collector.onAccessibilityText(screen1, pkg, 4_500L, scrollItems(10, 14))
        assertFalse(collector.shouldTriggerOcr(s2.result, 4_500L))

        val text = realtimeCollectionDiagnosticLines(collector.status()).joinToString("\n")
        assertTrue("诊断应能区分「滚动后 OCR 被频率限制拦截」", text.contains("频率限制"))
        assertEquals("限频原因与结果状态必须分开记录", "失败", collector.status().lastOcrStatusNote?.substringBefore("："))
    }

    // ========== 11. 受控画面变化探测回退（修复 B：目标应用不发滚动事件时的兜底） ==========

    /** 32 × 32 的亮度基线样本（模拟一整屏内容）。 */
    private fun sampleOf(value: Int): ScreenSample = ScreenSample(List(32 * 32) { value })

    @Test
    fun controlled_screen_probe_triggers_ocr_when_image_changes_substantially() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        // 目标应用不发送任何滚动事件：只有一次无障碍文本观察，且此后文本不再变化。
        collector.onAccessibilityText(screen1, pkg, 1_000L)

        assertTrue("无目标滚动事件且已过探测间隔时，应允许发起一次画面探测", collector.shouldProbeScreen(3_000L))
        assertFalse("首次采样只建立基线，不触发 OCR", collector.onScreenProbe(sampleOf(120), 3_000L))
        assertFalse("距上次探测不足采样间隔时不得重复探测", collector.shouldProbeScreen(4_000L))
        assertTrue(
            "超过采样间隔后允许再次探测",
            collector.shouldProbeScreen(3_000L + RealtimeViewportCollector.DEFAULT_SCREEN_PROBE_INTERVAL_MILLIS)
        )

        // 画面发生实质变化（整屏亮度显著不同 → 全部格子超阈值）。
        assertTrue(
            "画面明显变化时应进入 OCR 单飞 + 限频流程",
            collector.onScreenProbe(sampleOf(60), 9_000L)
        )
        assertEquals(ScreenProbeDecision.CHANGED, collector.status().lastProbeDecision)
        assertNotNull("画面变化后仍必须走既有的单飞 + 频率限制预约", collector.beginOcrAttempt(9_000L))

        // 采样本身不写入文本池（只有 OCR 结果才会），因此探测过程不会污染题目上下文。
        assertTrue(collector.status().poolText.contains("光合作用"))
    }

    @Test
    fun screen_probe_skips_ocr_when_image_is_unchanged() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        assertFalse(collector.onScreenProbe(sampleOf(120), 3_000L))

        // 极小面积变化（如状态栏时钟数字）：远达不到实质性变化阈值。
        val minorChange = List(32 * 32) { index -> if (index == 0) 255 else 120 }
        assertFalse("画面无实质变化时应跳过 OCR", collector.onScreenProbe(ScreenSample(minorChange), 9_000L))
        assertEquals(ScreenProbeDecision.UNCHANGED, collector.status().lastProbeDecision)

        // 全局亮度微抖（动画 / 悬浮窗状态）：同样不应触发。
        val dither = List(32 * 32) { 130 }
        assertFalse("亮度微抖不得累计成实质变化", collector.onScreenProbe(ScreenSample(dither), 15_000L))
        assertEquals(ScreenProbeDecision.UNCHANGED, collector.status().lastProbeDecision)
    }

    @Test
    fun screen_probe_is_suppressed_while_target_app_sends_scroll_events() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        // 目标应用刚分发过滚动事件（位置未知）：滚动侧已能触发采集，无需画面探测。
        collector.onAccessibilityText(screen1, pkg, 1_000L, scrollWithoutLocation)
        assertEquals(1_000L, collector.status().lastTargetScrollAtMillis)
        assertFalse(
            "收到目标应用滚动事件后不得启用画面探测回退",
            collector.shouldProbeScreen(1_000L + RealtimeViewportCollector.DEFAULT_PROBE_SCROLL_SILENCE_MILLIS - 1)
        )
        assertTrue(
            "持续收不到滚动事件后才启用探测回退",
            collector.shouldProbeScreen(1_000L + RealtimeViewportCollector.DEFAULT_PROBE_SCROLL_SILENCE_MILLIS)
        )
    }

    @Test
    fun realtime_disabled_stops_screen_probe_and_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        assertTrue(collector.shouldProbeScreen(1_000L))
        assertFalse(collector.onScreenProbe(sampleOf(120), 1_000L))

        collector.setEnabled(false)

        assertFalse("实时模式关闭后不得再发起画面探测", collector.shouldProbeScreen(20_000L))
        assertFalse("实时模式关闭后不得再提交探测样本", collector.onScreenProbe(sampleOf(0), 20_000L))
        assertNull("实时模式关闭后不得再启动 OCR", collector.beginOcrAttempt(20_000L))
        assertNull("实时模式关闭后探测判定应被复位", collector.status().lastProbeDecision)
    }

    // ========== 12. 可观测性：事件类型 / 来源匹配 / OCR 状态 / 探测状态 ==========

    @Test
    fun diagnostic_lines_report_event_type_source_and_ocr_status() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityText(screen1, pkg, 1_000L)
        val scrolled = collector.onAccessibilityText(
            screen1, pkg, 4_000L, scrollWithoutLocation,
            RealtimeEventKind.VIEW_SCROLLED, eventFromTargetWindow = true
        )
        assertTrue(collector.shouldTriggerOcr(scrolled.result, 4_000L))
        val sequence = collector.beginOcrAttempt(4_000L)
        assertNotNull(sequence)
        // 位置未知时补的这一次 OCR 读到了另一个目标窗口的内容（窗口标识变化 → 新题），
        // 池被更新，因此 OCR 结果状态应记为「成功」。
        collector.completeOcrAttempt(
            sequence!!,
            organizationOf("第 2/10 题", "关于细胞呼吸的叙述，错误的是", "A 选项丙", "B 选项丁"),
            null,
            "com.example.otherreader",
            4_200L
        )

        val text = realtimeCollectionDiagnosticLines(collector.status()).joinToString("\n")
        assertTrue(text.contains("最近一次接收到的无障碍事件类型: VIEW_SCROLLED"))
        assertTrue(text.contains("事件来源是否匹配目标窗口: 是"))
        assertTrue(text.contains("滚动位置是否可用: 未知"))
        assertTrue(text.contains("最近一次 OCR 触发/门控: OCR 已触发：确认发生了滚动但事件未提供可靠位置"))
        assertTrue(text.contains("最近一次 OCR 状态: 成功"))
        // 诊断行只给状态与计数，不得出现识别原文。
        assertFalse(text.contains("细胞呼吸"))
    }

    // ====== 13. 真机验收修复：空文本不得丢弃已确认的目标滚动证据（情况 A / B / D / E） ======

    @Test
    fun enabled_realtime_collector_is_ready_for_background_collection() {
        val collector = collector()
        assertFalse("未启用时不得进入可执行的采集状态", collector.isEnabled())
        assertFalse(collector.shouldProbeScreen(1_000L))

        collector.setEnabled(true)
        collector.setOcrCapability(true)

        assertTrue(collector.isEnabled())
        assertTrue("实时模式启用后后台采集必须进入可执行状态", collector.shouldProbeScreen(1_000L))
        assertTrue(collector.status().enabled)
    }

    @Test
    fun blank_accessibility_text_keeps_confirmed_target_scroll_evidence() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation(screen1, pkg, 1_000L)
        val before = collector.status()

        // 节点树读不到任何文本（Canvas / WebView 承载题目），但目标应用确实发生了滚动。
        val blank = collector.onAccessibilityObservation(
            text = "   \n\t ",
            windowKey = pkg,
            atMillis = 4_000L,
            scroll = scrollItems(5, 9),
            eventKind = RealtimeEventKind.VIEW_SCROLLED,
            eventFromTargetWindow = true
        )

        assertFalse("空文本不得伪装成成功识别", blank.applied)
        assertEquals(PoolUpdateResult.OCR_FAILED, blank.result)
        assertEquals(RealtimeTextReadStatus.EMPTY, collector.status().lastTextReadStatus)
        assertEquals(
            "空文本时仍必须保留已确认的目标滚动证据",
            RealtimeScrollDecision.ADVANCED,
            collector.status().lastScrollDecision
        )
        assertEquals(RealtimeEventKind.VIEW_SCROLLED, collector.status().lastEventKind)
        assertEquals("空文本不得覆盖既有有效池内容", before.poolText, collector.status().poolText)
        assertEquals(before.fragmentCount, collector.status().fragmentCount)
    }

    @Test
    fun debounced_blank_target_scroll_reaches_collector_and_triggers_rate_limited_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation(screen1, pkg, 1_000L)
        // 先消耗掉一次在途 OCR 预约，确保后面测的是「频率限制已满足」的情形。
        collector.beginOcrAttempt(1_000L)?.let { collector.failOcrAttempt(it, 1_000L) }

        val blank = collector.onAccessibilityObservation(
            text = null,
            windowKey = pkg,
            atMillis = 5_000L,
            scroll = scrollItems(5, 9),
            eventKind = RealtimeEventKind.VIEW_SCROLLED,
            eventFromTargetWindow = true
        )

        assertEquals(RealtimeTextReadStatus.EMPTY, collector.status().lastTextReadStatus)
        assertTrue(
            "空文本 + 确认目标滚动必须能到达采集器并允许受控 OCR",
            collector.shouldTriggerOcr(blank.result, 5_000L)
        )
        val lines = realtimeCollectionDiagnosticLines(collector.status()).joinToString("\n")
        assertTrue(lines.contains("最近一次 OCR 触发/门控: OCR 已触发：当前视口未读到无障碍文本"))
        assertNotNull("确认后应能在限频与单飞保护下预约一次截图 OCR", collector.beginOcrAttempt(5_000L))
    }

    @Test
    fun blank_text_without_target_scroll_does_not_trigger_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation(screen1, pkg, 1_000L)

        val blank = collector.onAccessibilityObservation(
            text = "  ",
            windowKey = pkg,
            atMillis = 4_000L,
            scroll = null,
            eventKind = RealtimeEventKind.WINDOW_CONTENT_CHANGED,
            eventFromTargetWindow = true
        )

        assertEquals(RealtimeScrollDecision.NOT_SCROLL, collector.status().lastScrollDecision)
        assertFalse("只有空文本、没有滚动证据时不得触发 OCR", collector.shouldTriggerOcr(blank.result, 4_000L))
    }

    @Test
    fun blank_text_from_non_target_window_does_not_trigger_ocr() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation(screen1, pkg, 1_000L)

        val blank = collector.onAccessibilityObservation(
            text = "",
            windowKey = "com.example.otherreader",
            atMillis = 4_000L,
            scroll = scrollItems(5, 9),
            eventKind = RealtimeEventKind.VIEW_SCROLLED,
            eventFromTargetWindow = false
        )

        assertEquals(RealtimeScrollDecision.NOT_SCROLL, collector.status().lastScrollDecision)
        assertNull("非目标窗口的滚动不得写入目标滚动时间戳", collector.status().lastTargetScrollAtMillis)
        assertFalse("不得为了让 OCR 启动而绕过目标窗口过滤", collector.shouldTriggerOcr(blank.result, 4_000L))
    }

    @Test
    fun read_failure_is_distinguished_from_empty_viewport_text() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        // 情况 1：连有效目标窗口都没取到（无障碍未连接 / 当前只有本应用悬浮窗）。
        val failed = collector.onAccessibilityObservation(text = null, windowKey = null, atMillis = 1_000L)
        assertEquals(RealtimeTextReadStatus.FAILED, collector.status().lastTextReadStatus)
        assertEquals(PoolUpdateResult.OCR_FAILED, failed.result)
        var lines = realtimeCollectionDiagnosticLines(collector.status()).joinToString("\n")
        assertTrue(lines.contains("最近一次无障碍文本读取: 失败（未能确认有效的第三方目标应用窗口"))

        // 情况 2：目标窗口有效，但当前视口没有可读文本 —— 两者必须分开显示。
        collector.onAccessibilityObservation(text = "", windowKey = pkg, atMillis = 3_000L)
        assertEquals(RealtimeTextReadStatus.EMPTY, collector.status().lastTextReadStatus)
        lines = realtimeCollectionDiagnosticLines(collector.status()).joinToString("\n")
        assertTrue(lines.contains("最近一次无障碍文本读取: 空文本（目标应用身份已确认"))
        assertFalse(lines.contains("最近一次无障碍文本读取: 失败"))
    }

    @Test
    fun screen_probe_runs_when_target_app_never_sends_scroll_events() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        // 目标应用不发送任何滚动事件，甚至连无障碍文本都没有。
        assertNull(collector.status().lastTargetScrollAtMillis)

        assertTrue("无滚动事件时探测任务应能按条件运行", collector.shouldProbeScreen(1_000L))
        assertFalse("首次探测只建立基线，不进入 OCR", collector.onScreenProbe(sampleOf(120), 1_000L))
        assertEquals(ScreenProbeDecision.FIRST_SAMPLE, collector.status().lastProbeDecision)

        assertTrue(
            "超过探测间隔后可再次探测",
            collector.shouldProbeScreen(1_000L + RealtimeViewportCollector.DEFAULT_SCREEN_PROBE_INTERVAL_MILLIS)
        )
        assertTrue("检测到明显画面变化后必须进入 OCR 调用链", collector.onScreenProbe(sampleOf(20), 9_000L))
        assertEquals(ScreenProbeDecision.CHANGED, collector.status().lastProbeDecision)
        assertNotNull("画面变化后仍走既有单飞 + 限频预约", collector.beginOcrAttempt(9_000L))
        assertNull("单飞：同一时刻只允许一个在途 OCR", collector.beginOcrAttempt(9_000L))
    }

    @Test
    fun ocr_success_updates_pool_while_ocr_failure_keeps_existing_content() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation(screen1, pkg, 1_000L)
        val before = collector.status()

        // 1) 空文本 + 目标滚动 → 预约 OCR → 本次 OCR 执行失败。
        val blank = collector.onAccessibilityObservation(
            "", pkg, 4_000L, scrollItems(5, 9), RealtimeEventKind.VIEW_SCROLLED, true
        )
        assertTrue(collector.shouldTriggerOcr(blank.result, 4_000L))
        val failedSeq = collector.beginOcrAttempt(4_000L)!!
        collector.failOcrAttempt(failedSeq, 4_200L)

        assertEquals("OCR 失败必须保留原有效内容", before.poolText, collector.status().poolText)
        assertEquals(before.fragmentCount, collector.status().fragmentCount)
        assertTrue(collector.status().lastOcrStatusNote!!.startsWith("失败"))

        // 2) 再次空文本 + 目标滚动 → 预约 OCR → 本次 OCR 成功并扩展了视口。
        val blank2 = collector.onAccessibilityObservation(
            "", pkg, 8_000L, scrollItems(10, 14), RealtimeEventKind.VIEW_SCROLLED, true
        )
        assertTrue(collector.shouldTriggerOcr(blank2.result, 8_000L))
        val okSeq = collector.beginOcrAttempt(8_000L)!!
        collector.completeOcrAttempt(
            okSeq,
            organizationOf(
                "C 光合作用释放二氧化碳",
                "D 光合作用需要光照",
                "E 光合作用不需要水"
            ),
            null,
            pkg,
            8_200L
        )

        assertTrue(
            "OCR 成功后应把本次视口的新内容并入文本池",
            collector.status().poolText.contains("光合作用不需要水")
        )
        assertTrue(collector.status().lastOcrStatusNote!!.startsWith("成功"))
    }

    @Test
    fun perception_status_text_distinguishes_not_started_empty_read_and_ocr_failure() {
        val collector = collector()

        // 1) 实时模式未启用 → 后台采集未启动。
        assertEquals(
            "实时感知中 · 后台采集未启动（实时模式未开启）",
            realtimePerceptionStatusText(active = true, snapshot = null, report = null, collection = collector.status())
        )

        collector.setEnabled(true)
        collector.setOcrCapability(true)
        // 2) 已启动但尚未收到任何无障碍事件。
        val waiting = realtimePerceptionStatusText(true, null, null, collector.status())
        assertTrue(waiting.contains("尚未收到无障碍事件"))
        assertFalse("不得把「已启动但没收到事件」显示成「未获取到屏幕内容」", waiting.contains("未获取到屏幕内容"))

        // 3) 目标窗口有效，但当前视口没有文本。
        collector.onAccessibilityObservation("", pkg, 1_000L)
        assertTrue(
            realtimePerceptionStatusText(true, null, null, collector.status()).contains("当前视口未读到文本")
        )

        // 4) 连有效目标窗口都没取到 → 读取失败。
        collector.onAccessibilityObservation(null, null, 3_000L)
        assertTrue(
            realtimePerceptionStatusText(true, null, null, collector.status())
                .contains("无法确认目标应用窗口")
        )

        // 5) 文本池已有内容 → 顶部应反映已积累的片段与字数。
        collector.onAccessibilityObservation(screen1, pkg, 5_000L)
        assertTrue(
            realtimePerceptionStatusText(true, null, null, collector.status()).contains("文本池 1 片段")
        )
    }

    @Test
    fun disabling_realtime_stops_background_collection() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation(screen1, pkg, 1_000L)
        assertTrue(collector.status().enabled)

        collector.setEnabled(false)

        assertFalse(collector.status().enabled)
        assertFalse("关闭后不得再发起画面探测", collector.shouldProbeScreen(20_000L))
        val rejected = collector.onAccessibilityObservation(
            "", pkg, 20_000L, scrollItems(5, 9), RealtimeEventKind.VIEW_SCROLLED, true
        )
        assertFalse("关闭后不得再提交任何后台采集观察", rejected.applied)
        assertFalse(collector.shouldTriggerOcr(PoolUpdateResult.OCR_FAILED, 20_000L))
        assertEquals(0, collector.status().fragmentCount)
        assertEquals(
            "实时感知中 · 后台采集未启动（实时模式未开启）",
            realtimePerceptionStatusText(true, null, null, collector.status())
        )
    }

    // ============ 14. 三态分离：身份确认、文本可读性与滚动归属彼此独立 ============

    /** 本应用自身窗口包名（用于「非目标 / 本应用悬浮窗」场景）。 */
    private val ownOverlay = "fun.kirari.hanako"

    @Test
    fun confirmed_target_with_empty_text_preserves_scroll_evidence() {
        // 目标应用位于前台（身份由事件来源包名确认），但无障碍文本为空：
        // 第 1 类验收 —— 滚动证据必须被保留，仍可进入受控 OCR，且不得凭空产生片段。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        val outcome = collector.onAccessibilityObservation(
            text = null,
            windowKey = "com.example.quiz",
            atMillis = 1_000L,
            scroll = scrollItems(5, 9),
            eventKind = RealtimeEventKind.VIEW_SCROLLED,
            eventFromTargetWindow = true
        )
        assertEquals(RealtimeTextReadStatus.EMPTY, collector.status().lastTextReadStatus)
        assertEquals(RealtimeScrollDecision.ADVANCED, collector.status().lastScrollDecision)
        assertFalse("未读到文本时本次提交不得作用于文本池", outcome.applied)
        assertTrue("确认目标滚动后仍应进入受控 OCR", collector.shouldTriggerOcr(outcome.result, 1_000L))
        assertEquals("空文本不得凭空产生片段", 0, collector.status().fragmentCount)
    }

    @Test
    fun identity_confirmed_but_text_extraction_failed_is_not_treated_as_no_window() {
        // 第 2 类验收：目标应用窗口存在但文本提取失败 → 记 EMPTY（身份已确认），绝不记 FAILED。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation(null, "com.example.quiz", 1_000L)
        assertEquals(RealtimeTextReadStatus.EMPTY, collector.status().lastTextReadStatus)
        assertFalse(
            realtimeCollectionDiagnosticLines(collector.status()).joinToString("\n")
                .contains("失败（未能确认有效的第三方目标应用窗口")
        )
        // 只有在真正无法确认身份（windowKey 为空）时才记 FAILED。
        collector.onAccessibilityObservation(null, null, 3_000L)
        assertEquals(RealtimeTextReadStatus.FAILED, collector.status().lastTextReadStatus)
    }

    @Test
    fun own_overlay_or_non_target_scroll_never_triggers_question_ocr() {
        // 第 3 类验收：本应用悬浮窗 / 非目标窗口的滚动一律不得触发题目 OCR。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        val own = collector.onAccessibilityObservation(
            text = null,
            windowKey = ownOverlay,
            atMillis = 1_000L,
            scroll = scrollItems(5, 9),
            eventKind = RealtimeEventKind.VIEW_SCROLLED,
            eventFromTargetWindow = false
        )
        assertEquals(RealtimeScrollDecision.NOT_SCROLL, collector.status().lastScrollDecision)
        assertNull("非目标窗口的滚动不得写入目标滚动时间戳", collector.status().lastTargetScrollAtMillis)
        assertFalse(collector.shouldTriggerOcr(own.result, 1_000L))
    }

    @Test
    fun capture_session_unavailable_reports_explicit_reason() {
        // 第 5 类验收：捕获会话不可用时，诊断必须准确标明阻断原因（不得统一成泛化失败）。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        val seq = collector.beginOcrAttempt(1_000L)!!
        collector.failOcrAttempt(
            sequence = seq,
            atMillis = 1_100L,
            reason = "截图会话不可用（active=false，hasSession=false，未改动文本池）"
        )
        val status = collector.status()
        assertTrue(status.lastOcrStatusNote!!.contains("截图会话不可用"))
        assertTrue(
            realtimeCollectionDiagnosticLines(status).joinToString("\n").contains("截图会话不可用")
        )
        assertEquals("截图会话不可用不得清空既有文本池", 0, status.fragmentCount)
    }

    @Test
    fun submission_count_semantics_stay_consistent_with_last_submission() {
        // 第 8 类验收：后台观察次数、最近观察状态与片段数量语义一致且明确。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        // 三次「读不到文本」的提交：次数递增、提交时间更新，但从未有效写入。
        collector.onAccessibilityObservation(null, null, 1_000L)
        collector.onAccessibilityObservation(null, null, 2_000L)
        collector.onAccessibilityObservation(null, null, 3_000L)
        val afterFailures = collector.status()
        assertEquals(3, afterFailures.updateCount)
        assertEquals("每次提交（含失败）都要有提交时间", 3_000L, afterFailures.lastSubmissionAtMillis)
        assertNull("未生效的提交不得被当作已接受的观察", afterFailures.lastObservedAtMillis)
        assertEquals(0, afterFailures.fragmentCount)
        val lines = realtimeCollectionDiagnosticLines(afterFailures).joinToString("\n")
        assertTrue(lines.contains("累计采集提交次数: 3（"))
        assertTrue(lines.contains("不代表成功写入次数"))
        assertTrue(lines.contains("最近一次采集提交: "))

        // 一次有效写入后，「被接受的观察」时间才出现，片段数才增长。
        collector.onAccessibilityObservation(screen1, pkg, 5_000L)
        val afterSuccess = collector.status()
        assertNotNull(afterSuccess.lastObservedAtMillis)
        assertEquals(4, afterSuccess.updateCount)
        assertEquals(1, afterSuccess.fragmentCount)
    }

    @Test
    fun disabling_realtime_resets_collection_and_probe_status_semantics() {
        // 第 9 类验收：实时模式关闭后采集与探测任务停止，且诊断字段被复位。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation("", pkg, 1_000L)
        assertNotNull(collector.status().lastSubmissionAtMillis)

        collector.setEnabled(false)
        val status = collector.status()
        assertFalse(status.enabled)
        assertNull("关闭后必须复位提交时间基线", status.lastSubmissionAtMillis)
        assertFalse(collector.shouldProbeScreen(30_000L))
        assertFalse("关闭后不得再执行画面探测", collector.onScreenProbe(sampleOf(20), 30_000L))
    }

    // ====== 15. 目标应用会话绑定边界：未绑定 / 非目标窗口不得建立、污染或覆盖题目上下文 ======

    /** 用户建立会话绑定的目标刷题应用（真机验收目标：粉笔 com.fenbi.android.servant）。 */
    private val selectedTargetApp = "com.fenbi.android.servant"

    /** 桌面 / 其它非目标窗口，用于验证「不得被当作目标刷题应用」。 */
    private val desktopWindow = "com.miui.home"

    /** 本应用包名；自身窗口永远不得写池。 */
    private val ownPackage = "fun.kirari.hanako"

    /** 系统桌面集合（桌面不是刷题应用，必须排除）。 */
    private val homePackages = setOf(desktopWindow, "com.android.launcher3")

    /**
     * 复现无障碍侧的 R7 绑定门控：只有「**已建立会话绑定**（bound 非空）+ 与绑定目标身份一致」
     * 的观察才带 windowKey；未绑定、桌面、其它应用一律按「身份未确认」提交（windowKey = null）。
     *
     * 关键：`bound == null`（仅选择应用、尚未建立绑定）时，任何包名都不得写池 —— 对应 S1。
     */
    private fun gatedWindowKey(packageName: String?, bound: String? = selectedTargetApp): String? =
        packageName?.takeIf { isPoolWriteAllowed(it, bound, ownPackage, homePackages) }

    @Test
    fun no_binding_means_no_pool_write_even_for_target_app() {
        // S5① / S1：boundPackage == null 时（仅选择应用 ≠ 已建立绑定），即便前台就是目标应用也不得写池。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        assertNull("未绑定时不得确认目标身份", gatedWindowKey(selectedTargetApp, bound = null))
        val outcome = collector.onAccessibilityObservation(
            screen1, gatedWindowKey(selectedTargetApp, bound = null), 1_000L
        )

        assertEquals(PoolUpdateResult.OCR_FAILED, outcome.result)
        assertFalse("未绑定时不得作用于文本池", outcome.applied)
        assertTrue("未绑定时池内容必须保持为空", collector.status().poolText.isBlank())
        assertEquals(0, collector.status().fragmentCount)
    }

    @Test
    fun selected_target_app_establishes_question_context() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        assertNotNull("已选定的目标应用必须能被确认为目标身份", gatedWindowKey(selectedTargetApp))
        val outcome = collector.onAccessibilityObservation(
            screen1, gatedWindowKey(selectedTargetApp), 1_000L
        )

        assertEquals(PoolUpdateResult.NEW_QUESTION, outcome.result)
        assertTrue("已选定目标应用应能建立题目上下文", outcome.poolContentChanged)
        assertEquals(1, collector.status().fragmentCount)
    }

    @Test
    fun desktop_window_never_establishes_or_overwrites_question_context() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation(screen1, gatedWindowKey(selectedTargetApp), 1_000L)
        val before = collector.status()

        // 用户回到桌面：窗口包名不再是目标应用 → 身份门控后 windowKey 为 null。
        assertNull("桌面窗口不得被确认为目标应用", gatedWindowKey(desktopWindow))
        val desktop = collector.onAccessibilityObservation(
            text = "相机\n相册\n设置\n时钟",
            windowKey = gatedWindowKey(desktopWindow),
            atMillis = 2_000L,
            scroll = scrollItems(0, 3),
            eventKind = RealtimeEventKind.VIEW_SCROLLED,
            eventFromTargetWindow = false
        )

        assertEquals(PoolUpdateResult.OCR_FAILED, desktop.result)
        assertFalse("桌面观察不得作用于文本池", desktop.applied)
        assertEquals(RealtimeTextReadStatus.FAILED, collector.status().lastTextReadStatus)
        assertEquals("桌面内容不得建立新题，也不得覆盖既有题目上下文", before.poolText, collector.status().poolText)
        assertEquals(before.fragmentCount, collector.status().fragmentCount)
        assertNull("桌面滚动不得成为目标应用滚动证据", collector.status().lastTargetScrollAtMillis)
    }

    @Test
    fun unselected_other_app_never_pollutes_question_pool() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation(screen1, gatedWindowKey(selectedTargetApp), 1_000L)
        val before = collector.status()

        // 切到一个未选定的第三方应用：它的正文既不得进入文本池，也不得被当作新题。
        assertNull("未选定应用不得被确认为目标应用", gatedWindowKey("com.example.notes"))
        val other = collector.onAccessibilityObservation(
            "待办清单\n今天要买牛奶\n明天开会",
            gatedWindowKey("com.example.notes"),
            2_000L
        )

        assertFalse(other.applied)
        assertEquals(PoolUpdateResult.OCR_FAILED, other.result)
        assertEquals("未选定应用的内容不得污染题目内容池", before.poolText, collector.status().poolText)
        assertEquals(before.fragmentCount, collector.status().fragmentCount)
    }

    @Test
    fun returning_to_selected_target_app_resumes_observation() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityObservation(
            page1, gatedWindowKey(selectedTargetApp), 1_000L, scrollItems(0, 3),
            RealtimeEventKind.VIEW_SCROLLED, true
        )
        // 中途离开目标应用（桌面）：池内容保持不变。
        collector.onAccessibilityObservation(
            "相机\n相册\n设置", gatedWindowKey(desktopWindow), 2_000L
        )
        val afterLeaving = collector.status()

        // 回到已选定的目标应用后应能恢复观察，并按既有合并规则继续累积。
        val back = collector.onAccessibilityObservation(
            page2, gatedWindowKey(selectedTargetApp), 3_000L, scrollItems(4, 8),
            RealtimeEventKind.VIEW_SCROLLED, true
        )

        assertTrue("返回目标应用后应继续观察", back.applied)
        assertEquals(PoolUpdateResult.OVERLAP_MERGED, back.result)
        assertEquals("返回目标应用后应继续累积同一题的视口内容", 6, collector.status().poolLines.size)
        assertTrue("返回前的原题内容必须保留", collector.status().poolText.contains("第 3/10 题"))
        assertTrue("返回后新视口的内容必须并入", collector.status().poolText.contains("C 选项丙"))
        assertFalse("离开目标应用期间的桌面内容不得混入题目内容池", collector.status().poolText.contains("相册"))
        assertTrue("离开目标应用期间既有题目上下文不得被替换", afterLeaving.poolText.contains("第 3/10 题"))
        assertFalse("离开目标应用期间桌面内容不得进入池", afterLeaving.poolText.contains("相册"))
    }

    @Test
    fun unconfirmed_identity_never_establishes_or_overwrites_question_context() {
        // 身份安全门控仍然有效：未选定目标应用（或非目标窗口）时，即使读到文本也不得建立题目上下文。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        val outcome = collector.onAccessibilityObservation(screen1, windowKey = null, atMillis = 1_000L)

        assertEquals(PoolUpdateResult.OCR_FAILED, outcome.result)
        assertEquals(RealtimeTextReadStatus.FAILED, collector.status().lastTextReadStatus)
        assertTrue("身份未确认时不得写入任何题目内容", collector.status().poolText.isBlank())
        assertEquals(0, collector.status().fragmentCount)
    }

    // ============ 16. R8.2：无障碍读到的文本「明显不完整」时的补采集门控 ============

    /**
     * 真机证据复刻：粉笔「专项智能练习（资料分析）」页无障碍只返回 28 字外壳
     * （标题 / 题号 / 材料占位），一行选项标记都读不到。
     */
    private val fenbiShell = listOf(
        "专项智能练习（资料分析）",
        "10 / 15",
        "材料",
        "材料",
        "材料"
    ).joinToString("\n")

    /** OCR 读到的一屏完整资料分析题（题干 + 四个选项），用于验证补采集结果能否进池。 */
    private val fenbiOcrQuestion = listOf(
        "专项智能练习（资料分析）",
        "材料一：2023年上半年，全国规模以上工业企业实现利润总额33884亿元，同比下降16.8%。",
        "问题：2023年上半年全国规模以上工业企业实现利润总额同比约减少多少亿元？",
        "A 约5600亿元",
        "B 约6800亿元",
        "C 约7200亿元",
        "D 约8400亿元"
    ).joinToString("\n")

    @Test
    fun accessibilityTextLacksOptionLines_only_flags_shell_like_text() {
        // 纯函数判据：一行选项标记都读不到才算「明显不完整」；本身简短但已含题干 + 选项不算。
        assertTrue("28 字外壳（无选项行）应判为明显不完整", accessibilityTextLacksOptionLines(fenbiShell))
        assertFalse("完整题目（含 A–D 选项行）不得被判为不完整", accessibilityTextLacksOptionLines(screen1))
        assertFalse("简短但已含选项的读屏结果不得被判为不完整", accessibilityTextLacksOptionLines(questionOne))
        assertFalse("空文本不是「明显不完整」的证据（另有 EMPTY 状态表达）", accessibilityTextLacksOptionLines("  \n "))
        assertEquals(4, countOptionLikeLines(screen1))
        assertEquals(0, countOptionLikeLines(fenbiShell))
    }

    @Test
    fun short_incomplete_accessibility_text_with_confirmed_target_triggers_supplement_ocr() {
        // 场景 1：无障碍返回 28 字短文本、且目标应用身份可靠 → 必须按设计触发补充 OCR。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        val outcome = collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 1_000L)
        assertEquals(PoolUpdateResult.NEW_QUESTION, outcome.result)
        assertEquals(RealtimeTextReadStatus.SUCCESS, collector.status().lastTextReadStatus)
        assertTrue(
            "文本非空但明显不完整（无选项行）时，必须补一次受控 OCR，而不是把外壳当成完整题目",
            collector.shouldTriggerOcr(outcome.result, 1_000L)
        )
        val note = collector.status().lastOcrGateNote ?: ""
        assertTrue("门控原因应说明「文本明显不完整」而非「无需补 OCR」", note.contains("明显不完整"))
        assertNotNull("限频与单飞保护下应能预约一次截图 OCR", collector.beginOcrAttempt(1_000L))
    }

    @Test
    fun complete_accessibility_text_does_not_retrigger_supplement_ocr() {
        // 场景 2：无障碍返回完整文本（含选项行）→ 不得无意义地重复 OCR。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        val first = collector.onAccessibilityText(screen1, pkg, 1_000L)
        assertFalse("已含选项的完整文本不得触发补采集 OCR", collector.shouldTriggerOcr(first.result, 1_000L))
        val repeated = collector.onAccessibilityText(screen1, pkg, 5_000L)
        assertFalse("完整文本重复上报也不得触发 OCR", collector.shouldTriggerOcr(repeated.result, 5_000L))
        assertFalse("完整文本下不存在「明显不完整」补采集需求", collector.shouldSupplementOcrForIncompletePool())
    }

    @Test
    fun unchanged_screen_does_not_permanently_skip_supplement_for_incomplete_pool() {
        // 场景 3：屏幕没有变化（画面探测 UNCHANGED / 事件非滚动），但已采集文本仍不完整 →
        // 不得仅凭 changed=false 就永久跳过补采集；失败后仍可再次补采。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        val first = collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 1_000L)
        assertTrue(collector.shouldTriggerOcr(first.result, 1_000L))

        // 画面探测判定「未变化」→ 仍应允许补一次受控 OCR（不得因 changed=false 直接放弃）。
        assertFalse("未建立基线时不应触发（首次采样只建立基线）", collector.onScreenProbe(sampleOf(20), 1_000L))
        assertFalse("与基线相同 → 画面未变化", collector.onScreenProbe(sampleOf(20), 3_000L))
        assertTrue(
            "画面未变化但题库明显不完整时，仍应允许补一次受控 OCR",
            collector.shouldSupplementOcrForIncompletePool()
        )

        // 预约一次补采集后置入去重键：同一不完整内容不得高频反复补采。
        val seq = collector.beginOcrAttempt(3_000L)
        assertNotNull(seq)
        assertFalse("同一不完整内容已补采过一次，先去重", collector.shouldSupplementOcrForIncompletePool())

        // 但一次补采集**失败**后不得被当成「已经补过」而永久跳过：仍可再次补采（受既有 2 秒限频保护）。
        collector.failOcrAttempt(seq!!, 3_100L, reason = "截图失败（本次观察不改动文本池）")
        assertFalse(
            "补采失败（OCR_FAILED + 非滚动）本身不构成新的触发理由",
            collector.shouldTriggerOcr(PoolUpdateResult.OCR_FAILED, 3_200L)
        )
        val again = collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 5_000L)
        assertTrue("补采失败后，同一不完整页面仍应能再次补采", collector.shouldTriggerOcr(again.result, 5_000L))
    }

    @Test
    fun supplement_ocr_is_deduped_once_pool_content_changes() {
        // 去重语义：OCR 真正补进新内容（acceptedSequence 推进）后，才允许对更完整的内容继续补采。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 1_000L)
        val seq = collector.beginOcrAttempt(1_000L)!!
        assertFalse("已预约一次补采后先去重", collector.shouldSupplementOcrForIncompletePool())

        // 一次「未带来新内容」的 OCR 结果：池内容不变 → 仍保持去重，不得高频补采。
        val noChange = collector.completeOcrAttempt(
            seq, organizationOf(fenbiShell), null, selectedTargetApp, 1_200L
        )
        assertFalse("OCR 未带来新内容时不得认为池已更新", noChange.poolContentChanged)
        assertFalse("内容未变化时不得高频重复补采", collector.shouldSupplementOcrForIncompletePool())
    }

    @Test
    fun unconfirmed_target_identity_never_triggers_supplement_nor_writes_pool() {
        // 场景 4：目标应用身份无法确认（windowKey 为空）→ 不得写入题目池，也不得触发补采集 OCR。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        val outcome = collector.onAccessibilityObservation(fenbiShell, windowKey = null, atMillis = 1_000L)

        assertEquals(PoolUpdateResult.OCR_FAILED, outcome.result)
        assertEquals(RealtimeTextReadStatus.FAILED, collector.status().lastTextReadStatus)
        assertTrue("身份未确认时任何内容都不得进池", collector.status().poolText.isBlank())
        assertEquals(0, collector.status().fragmentCount)
        assertFalse("身份未确认时不得触发补采集", collector.shouldTriggerOcr(outcome.result, 1_000L))
        assertFalse(collector.shouldSupplementOcrForIncompletePool())
    }

    @Test
    fun own_overlay_window_text_never_becomes_question_or_triggers_ocr() {
        // 场景 5：事件来自 Hanako 自身窗口（身份门控后 windowKey = null）→ 自身 UI 绝不被当作题干。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        assertNull("本应用自身窗口不得被确认为目标应用", gatedWindowKey(ownPackage))
        val own = collector.onAccessibilityObservation(
            text = "Hanako 浮窗\n继续提问\n清空\n关闭",
            windowKey = gatedWindowKey(ownPackage),
            atMillis = 1_000L
        )

        assertFalse("自身窗口内容不得作用于题目池", own.applied)
        assertEquals(PoolUpdateResult.OCR_FAILED, own.result)
        assertTrue("自身 UI 文本不得进入题目池", collector.status().poolText.isBlank())
        assertEquals(0, collector.status().fragmentCount)
        assertFalse("自身窗口事件不得触发题目 OCR", collector.shouldTriggerOcr(own.result, 1_000L))
    }

    @Test
    fun supplement_ocr_failure_never_marks_failed_result_as_valid_question() {
        // 场景 6：OCR 采集失败 / 遮罩失败 → 不得把失败结果标记为有效题目内容。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 1_000L)
        val before = collector.status().poolText
        val seq = collector.beginOcrAttempt(1_000L)!!

        collector.failOcrAttempt(seq, 1_100L, reason = "窗口遮罩失败（本次截图不得进入 OCR）")

        val status = collector.status()
        assertEquals("遮罩失败不得改动既有文本池", before, status.poolText)
        assertEquals("失败结果不得被标记为有效题目内容", 1, status.fragmentCount)
        assertTrue(status.lastOcrStatusNote?.contains("遮罩失败") == true)
        assertTrue(
            "去重键应被释放，使后续仍可补采",
            collector.shouldSupplementOcrForIncompletePool()
        )
    }

    @Test
    fun supplement_ocr_full_question_enters_question_pool() {
        // 场景 7：薄外壳种子 + OCR 读到完整题干 → 文本必须真正进入上下文池。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 1_000L)
        assertTrue(collector.shouldTriggerOcr(PoolUpdateResult.NEW_QUESTION, 1_000L))
        val seq = collector.beginOcrAttempt(1_000L)!!

        val completed = collector.completeOcrAttempt(
            seq,
            organizationOf(*fenbiOcrQuestion.split("\n").toTypedArray()),
            null,
            selectedTargetApp,
            1_200L
        )

        val poolText = collector.status().poolText
        assertTrue("OCR 完整题干必须进入上下文池", poolText.contains("利润总额"))
        assertTrue("OCR 选项也必须进入上下文池", poolText.contains("约5600亿元"))
        assertTrue("OCR 结果必须真正改变池内容", completed.poolContentChanged)
    }

    @Test
    fun incomplete_accessibility_shell_never_loops_ocr_once_pool_established_by_ocr() {
        // 池已由 OCR 建立（含完整题干 + 选项）后，无障碍仍反复上报 28 字外壳时，
        // 不得仅因 UNSTABLE 就每 2 秒重复补采（同一页面重复读取不得产生无意义高频 OCR）。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 1_000L)
        val seq = collector.beginOcrAttempt(1_000L)!!
        collector.completeOcrAttempt(
            seq,
            organizationOf(*fenbiOcrQuestion.split("\n").toTypedArray()),
            null,
            selectedTargetApp,
            1_200L
        )
        assertTrue("OCR 完整内容必须已在池中", collector.status().poolText.contains("利润总额"))

        // 无障碍再次上报同一外壳：池判为「关联未知」（外壳与完整 OCR 内容只有固定的 12 字页面标题重叠，
        // 不足同题证据阈值），但池中已有由 OCR 建立的更完整内容。
        val shellAgain = collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 5_000L)
        assertEquals(PoolUpdateResult.UNPROVEN, shellAgain.result)
        assertFalse(
            "池已由 OCR 建立完整内容时，外壳的「关联未知」不得触发重复补采",
            collector.shouldTriggerOcr(shellAgain.result, 5_000L)
        )
        assertTrue("池中更完整的 OCR 内容必须保留，不得被外壳覆盖", collector.status().poolText.contains("利润总额"))
    }

    // ============ 17. R8.3：池不完整时同题滚动必须继续补采 / 迟到 OCR 隔离 ============

    @Test
    fun incomplete_ocr_pool_then_scroll_supplement_completes_question() {
        // R8.3 回归：OCR 只补进一屏**不完整**内容（无选项结构）时，池**不得因此被当作完整**；
        // 同题滚动后必须仍能继续补采，直到补齐题干 + 选项（R8.2 曾把「池由 OCR 建立」误当「池已完整」而永久跳过）。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        // 1) 建立由 OCR 提供、但明显不完整（无选项行）的池。
        collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 1_000L)
        val seq = collector.beginOcrAttempt(1_000L)!!
        collector.completeOcrAttempt(
            seq,
            organizationOf("专项智能练习（资料分析）", "材料一：2023年上半年全国规模以上工业企业实现利润总额33884亿元"),
            null,
            selectedTargetApp,
            1_200L
        )
        assertEquals(
            "OCR 只补进一屏不完整内容 ≠ 池已完整",
            QuestionCompleteness.INCOMPLETE,
            collector.currentPool().currentQuestion()?.completeness
        )

        // 2) 同题滚动后无障碍仍上报同一外壳（池判为关联未知）→ 必须允许继续补采。
        val shellAgain = collector.onAccessibilityObservation(
            fenbiShell, selectedTargetApp, 5_000L, scrollItems(0, 5),
            RealtimeEventKind.VIEW_SCROLLED, true
        )
        assertEquals(PoolUpdateResult.UNPROVEN, shellAgain.result)
        assertTrue(
            "池仅含不完整 OCR 内容时，外壳的「关联未知」必须仍允许补采（R8.3 修复）",
            collector.shouldTriggerOcr(shellAgain.result, 6_000L)
        )

        // 3) 一次更完整的补采必须真正重建为完整题目（不得再被 UNSTABLE 丢弃）。
        val seq2 = collector.beginOcrAttempt(6_000L)!!
        val completed = collector.completeOcrAttempt(
            seq2,
            organizationOf(*fenbiOcrQuestion.split("\n").toTypedArray()),
            null,
            selectedTargetApp,
            6_200L
        )
        assertTrue("更完整的补采必须真正改变池内容", completed.poolContentChanged)
        assertTrue("补齐后的题干必须进入池", collector.status().poolText.contains("利润总额"))
        assertTrue("补齐后的选项必须进入池", collector.status().poolText.contains("约5600亿元"))

        // 4) 补齐后池已含选项结构 → 同一外壳（此后无新滚动）的「关联未知」不再触发无意义补采（回到防抖语义）。
        val shellAfterComplete = collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 9_000L)
        assertEquals(PoolUpdateResult.UNPROVEN, shellAfterComplete.result)
        assertFalse(
            "池已含选项结构且此后无新滚动时，外壳的「关联未知」不得再触发高频补采",
            collector.shouldTriggerOcr(shellAfterComplete.result, 10_000L)
        )
    }

    @Test
    fun target_scroll_releases_incomplete_supplement_dedup() {
        // R8.3：去重键（同一不完整内容只补一次）必须在**目标应用发生滚动**时释放，
        // 否则用户在同题内继续滚动（可能出现新的题干 / 材料 / 选项）也会被永久跳过补采。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 1_000L)
        val seq = collector.beginOcrAttempt(1_000L)!!
        // 一次「未带来新内容」的 OCR：池内容不变 → 去重键保持，不得对同一内容高频补采。
        collector.completeOcrAttempt(seq, organizationOf(fenbiShell), null, selectedTargetApp, 1_200L)
        assertFalse("同一不完整内容已补采过 → 先按去重跳过", collector.shouldSupplementOcrForIncompletePool())

        // 目标应用发生滚动：可能出现新内容 → 必须释放去重键，允许再次补采（频率仍受 minOcrInterval 限制）。
        collector.onAccessibilityObservation(
            fenbiShell, selectedTargetApp, 5_000L, scrollItems(0, 5),
            RealtimeEventKind.VIEW_SCROLLED, true
        )
        assertTrue(
            "同题滚动后必须释放「只补一次」的去重键，允许继续补采（R8.3 修复）",
            collector.shouldSupplementOcrForIncompletePool()
        )
    }

    @Test
    fun pool_with_option_structure_does_not_suppress_supplement_for_changed_incomplete_frame() {
        // 场景 3（R8.4 核心）：池虽已由 OCR 建立**含选项结构**的完整内容，但本次无障碍读到的是
        // **另一屏**且明显不完整（本帧内容指纹变化、一行选项都读不到）→ 不得拿「池里曾读到过选项」
        // 当作「当前屏已完整识别」的证据，必须继续补采（旧规则正是这样长期跳过补采）。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        // 1) 先由 OCR 建立一份含选项结构的完整池。
        collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 1_000L)
        val seq = collector.beginOcrAttempt(1_000L)!!
        collector.completeOcrAttempt(
            seq,
            organizationOf(*fenbiOcrQuestion.split("\n").toTypedArray()),
            null,
            selectedTargetApp,
            1_200L
        )
        assertTrue("前置条件：池中必须已含选项结构", collector.status().poolText.contains("约5600亿元"))

        // 2) 无障碍读到**新的**一屏外壳（题号已变、无选项 → 本帧内容指纹与上次补采不同）：
        //    即便池里仍含选项结构，也必须按本帧不足继续补采。
        val newShell = listOf("专项智能练习（资料分析）", "11 / 15", "材料").joinToString("\n")
        val outcome = collector.onAccessibilityObservation(newShell, selectedTargetApp, 5_000L)
        assertTrue(
            "池含选项结构不得作为当前屏完整的证据：本帧明显不完整仍须补采（R8.4 修复）",
            collector.shouldTriggerOcr(outcome.result, 5_000L)
        )
        assertTrue(
            "补采判定必须只依据本帧是否缺内容，而非池里是否曾出现选项结构",
            collector.shouldSupplementOcrForIncompletePool()
        )
    }

    @Test
    fun late_ocr_result_after_session_boundary_is_discarded() {
        // R8.3：会话边界（切换目标应用）之后，边界之前在途的旧应用 OCR 结果必须被判为乱序迟到而丢弃，
        // 绝不允许旧应用的 OCR 内容覆盖 / 污染新题状态。
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)

        collector.onAccessibilityObservation(fenbiShell, selectedTargetApp, 1_000L)
        val inFlight = collector.beginOcrAttempt(1_000L)!!

        // 会话边界：绑定目标切换 → 清池并推进序号。
        collector.onSessionBoundary()

        // 迟到的旧 OCR 结果返回：必须被丢弃。
        val late = collector.completeOcrAttempt(
            inFlight,
            organizationOf(*fenbiOcrQuestion.split("\n").toTypedArray()),
            null,
            selectedTargetApp,
            2_000L
        )
        assertFalse("边界前在途的旧结果必须被判为过期丢弃", late.applied)
        assertEquals(PoolUpdateResult.UNSTABLE, late.result)
        assertTrue("迟到的旧 OCR 内容不得进入新题状态", collector.status().poolText.isBlank())
        assertEquals(0, collector.status().fragmentCount)
    }
}
