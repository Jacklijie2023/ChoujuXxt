package `fun`.kirari.hanako.screencontext

import `fun`.kirari.hanako.chat.ChatConfig
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.chat.ChatStreamSource
import `fun`.kirari.hanako.chat.ScreenContextResult
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.network.ChatHistoryMessage
import `fun`.kirari.hanako.network.LlmEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8.6 §6 —— 长题跨滚动视口采集与同题累计的回归测试（12 项）。
 *
 * 关键要求（需求 §6）：断言必须落在**真正传给 `streamSource.stream(...)` 的 `userPrompt`**，
 * 而不只是某个枚举或纯函数返回值。因此每个用例都走「池/采集器状态 → [assembleAccumulatedWithLive]
 * （AppContainer 请求级组装的同一函数）→ [ChatSessionHolder] → 捕获实际请求」的完整链路。
 */
class LongQuestionCrossViewportAccumulationTest {

    private val bound = "com.fenbi.android.servant"

    private val provider = ModelProviderConfig(
        name = "Test",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        chatModel = "test-model"
    )

    private val config = ChatConfig(
        provider = provider,
        model = "test-model",
        systemPrompt = "sys",
        trustAllHttpsCertificates = false
    )

    private val userInstruction = "这道题选什么？"

    private class CapturingSource : ChatStreamSource {
        @Volatile var lastUserPrompt: String? = null
        @Volatile var streamCalls = 0
        @Volatile var lastTraceId: String? = null

        override suspend fun stream(
            provider: ModelProviderConfig,
            model: String,
            systemPrompt: String,
            userPrompt: String,
            imagesBase64: List<String>,
            webSearch: Boolean,
            thinking: Boolean,
            firstDeltaTimeoutMillis: Long,
            trustAllHttpsCertificates: Boolean,
            traceId: String?,
            recentHistory: List<ChatHistoryMessage>
        ): Flow<LlmEvent> {
            streamCalls++
            lastUserPrompt = userPrompt
            lastTraceId = traceId
            return flowOf(LlmEvent.Done)
        }
    }

    private fun collector(): RealtimeViewportCollector = RealtimeViewportCollector()

    /** 构造一个无障碍视口片段（节点树文本，无坐标）。 */
    private fun a11y(id: String, text: String, atMillis: Long, windowKey: String? = null) =
        accessibilityViewportFragmentOf(id = id, text = text, capturedAtMillis = atMillis, windowKey = windowKey)

    /** 构造一个带坐标的 OCR 整理结果（顺序自上而下，保证 comparable=true）。 */
    private fun organizationOf(vararg lines: String): OcrOrganization {
        val items = lines.mapIndexed { index, text ->
            OcrTextLine(text = text, left = 20, top = 100 + index * 40, right = 600, bottom = 130 + index * 40)
        }
        return organizeOcrText(OcrResult(rawText = lines.joinToString("\n"), lines = items))
    }

    private fun scrollItems(fromIndex: Int, toIndex: Int, itemCount: Int = 100) =
        RealtimeScrollSignal(fromIndex, toIndex, itemCount, scrollY = 0, maxScrollY = 0)

    private fun report(traceId: String, charCount: Int) = ScreenReadReport(
        traceId = traceId,
        status = ScreenReadStatus.SEND_INJECTED,
        source = ScreenContextSource.POOL_SNAPSHOT,
        targetPackage = bound,
        windowType = 1,
        windowValid = true,
        charCount = charCount,
        ocrCharCount = charCount,
        readAtMillis = 1L
    )

    /**
     * 走完整请求链路：把 [accumulatedText]（累计片段）与 [liveText]（本次新鲜视口）按
     * AppContainer 的同一 [assembleAccumulatedWithLive] 组装后交给 [ChatSessionHolder]，
     * 返回捕获到的实际请求。
     */
    private suspend fun runRequest(accumulatedText: String, liveText: String?): CapturingSource {
        val source = CapturingSource()
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = CoroutineScope(Dispatchers.Default),
            screenContextInjector = { tid ->
                val assembly = assembleAccumulatedWithLive(accumulatedText, liveText, tid)
                ScreenContextResult.Available(
                    assembly.text,
                    tid,
                    ScreenContextSource.POOL_SNAPSHOT,
                    report(tid, assembly.text.length)
                )
            }
        )
        holder.send(userInstruction)
        withTimeout(5_000) {
            while (source.lastUserPrompt == null) delay(5)
            while (!holder.isIdleForTest()) delay(5)
        }
        assertEquals("每次发送必须恰好一次模型请求", 1, source.streamCalls)
        return source
    }

    private fun String.countOf(needle: String): Int = if (needle.isEmpty()) 0 else split(needle).size - 1

    // 长题材料（≥ 20 字，用于产生可证明的连续共享）。
    private val material = "资料：某市 2023 年规模以上工业企业实现营业收入 1250 亿元，同比增长 18%"

    // ============ §6.1 相邻视口部分重叠 → 按正确顺序合并 ============

    @Test
    fun adjacent_overlapping_viewports_merge_in_order_and_appear_once_in_the_request() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", "$material\nA. 甲选项内容\nB. 乙选项内容", 1_000))
        val result = pool.observe(a11y("v2", "B. 乙选项内容\nC. 丙选项内容\nD. 丁选项内容", 2_000))
        assertEquals("边界连续重叠必须判为同题内容扩展并合并", PoolUpdateResult.OVERLAP_MERGED, result)

        val accumulated = pool.currentQuestion()!!.text
        // 当前视口（B/C/D）已被累计文本覆盖 → 不得重复注入。
        val sent = runRequest(accumulated, "D. 丁选项内容").lastUserPrompt!!

        val order = listOf(material, "A. 甲选项内容", "B. 乙选项内容", "C. 丙选项内容", "D. 丁选项内容")
            .map { sent.indexOf(it) }
        assertTrue("合并文本必须按屏幕顺序排列", order.all { it >= 0 } && order.zipWithNext().all { (a, b) -> b > a })
        assertEquals("相邻重叠行不得重复注入", 1, sent.countOf("B. 乙选项内容"))
    }

    // ============ §6.2 滚动到新内容但无足够重叠 → 保存独立片段而非丢弃 ============

    @Test
    fun scrolled_viewport_without_provable_continuity_is_kept_as_a_separate_segment_in_the_request() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", "$material\nA. 选项甲内容\nB. 选项乙内容", 1_000))
        // 与既有正文共享长材料，但行序无法整体证明（A/B 在本次视口中找不到）→ 不得拼接，也不得丢弃。
        val result = pool.observe(a11y("v2", "$material\nC. 选项丙内容", 2_000))
        assertEquals(PoolUpdateResult.NEW_SEGMENT, result)

        val accumulated = pool.currentQuestion()!!.text
        assertTrue("独立片段必须以分段标记附加", accumulated.contains(ScreenContextPool.SEGMENT_BREAK_MARK))

        val sent = runRequest(accumulated, null).lastUserPrompt!!
        assertTrue("既有连续正文必须保留", sent.contains("A. 选项甲内容"))
        assertTrue("新片段不得被丢弃", sent.contains("C. 选项丙内容"))
        assertTrue("差异内容必须位于分段标记之后（未插入既有正文）",
            sent.indexOf("C. 选项丙内容") > sent.indexOf(ScreenContextPool.SEGMENT_BREAK_MARK))
    }

    // ============ §6.3 同一视口重复采集不得无限重复添加文字 ============

    @Test
    fun repeated_collection_of_the_same_viewport_does_not_duplicate_text_in_the_request() = runBlocking {
        val pool = ScreenContextPool()
        val text = "$material\nA. 甲选项内容\nB. 乙选项内容"
        pool.observe(a11y("v1", text, 1_000))
        assertEquals(PoolUpdateResult.NO_CHANGE, pool.observe(a11y("v2", text, 2_000)))
        assertEquals(PoolUpdateResult.NO_CHANGE, pool.observe(a11y("v3", text, 3_000)))
        assertEquals("重复上报不得增加片段数", 1, pool.fragmentCount())

        val sent = runRequest(pool.currentQuestion()!!.text, null).lastUserPrompt!!
        assertEquals("同一视口重复采集不得重复注入正文", 1, sent.countOf("A. 甲选项内容"))
    }

    // ============ §6.4 只改变无关 UI 内容不得生成新的题目正文片段 ============

    @Test
    fun unrelated_ui_change_does_not_create_new_question_body_in_the_request() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", "剩余时间 01:20\n$material\nA. 甲选项内容\nB. 乙选项内容", 1_000))
        val before = pool.currentQuestion()!!.text
        // 仅倒计时文字变化（题干与选项一字未改）：R8.7 起按「只差一个字的 OCR 抖动」判为重复视口，
        // 既不并入正文，也不生成新的独立片段 —— 无关 UI 行不得进入题目材料。
        val result = pool.observe(a11y("v2", "剩余时间 01:19\n$material\nA. 甲选项内容\nB. 乙选项内容", 2_000))
        assertFalse("无关 UI 变化绝不能被判为「内容扩展」而并入正文", result == PoolUpdateResult.OVERLAP_MERGED)
        assertFalse("无关 UI 变化绝不能被整体采纳为新正文", result == PoolUpdateResult.APPENDED)
        assertEquals("只差一个字的无关 UI 行必须判为重复视口", PoolUpdateResult.DUPLICATE, result)

        val snap = pool.currentQuestion()!!
        assertTrue("连续正文必须原样保留在最前", snap.text.startsWith(before))
        assertEquals("无关 UI 变化不得改变题目材料", before, snap.text)
        assertFalse("无关 UI 行不得进入题目材料", snap.text.contains("01:19"))
        assertFalse("无关 UI 变化不得新增片段", snap.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK))

        val sent = runRequest(snap.text, null).lastUserPrompt!!
        assertEquals("无关 UI 变化不得在连续正文里制造新的题目片段", 1, sent.countOf("A. 甲选项内容"))
        assertEquals(1, sent.countOf(material))
    }

    // ============ §6.5 题号变化后旧题片段不得进入新题请求 ============

    @Test
    fun question_number_change_keeps_old_question_out_of_the_new_request() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("q10", "第 10/15 题\n材料：某市 2020 年新业态营业收入 1250 亿元\nA 甲\nB 乙", 1_000))
        val result = pool.observe(a11y("q11", "第 11/15 题\n材料：某市 2020 年新业态营业收入 1250 亿元\nC 丙\nD 丁", 2_000))
        assertEquals("题号正向矛盾必须判为新题", PoolUpdateResult.NEW_QUESTION, result)

        val sent = runRequest(pool.currentQuestion()!!.text, null).lastUserPrompt!!
        assertTrue("新题请求必须含第 11 题", sent.contains("第 11/15 题"))
        assertFalse("不得把第 10 题题干混入新题请求", sent.contains("第 10/15 题"))
        assertFalse("不得把第 10 题选项混入新题请求", sent.contains("A 甲"))
    }

    // ============ §6.6 目标应用切换后旧片段不得注入 ============

    @Test
    fun target_app_switch_keeps_old_segments_out_of_the_request() = runBlocking {
        val collector = collector()
        collector.setEnabled(true)
        collector.onAccessibilityText("fenbi 题干内容 甲\nA 甲\nB 乙", bound, 1_000)
        val result = collector.onAccessibilityText("其它应用界面内容 乙\nC 丙\nD 丁", "com.other.app", 2_000).result
        assertEquals("目标窗口标识变化必须判为新题（旧片段不得延续）", PoolUpdateResult.NEW_QUESTION, result)

        val status = collector.status()
        assertFalse("旧应用内容不得残留在池中", status.poolText.contains("fenbi 题干内容"))
        assertTrue("新应用内容必须已建立", status.poolText.contains("其它应用界面内容"))

        // 目标应用切换后，旧题池的应用身份与已绑定目标不一致 → 注入判定必须拒绝。
        assertEquals(
            PoolInjectionDecision.POOL_WINDOW_MISMATCH,
            decidePoolInjection(
                boundPackage = "com.other.app", currentWindowPackage = "com.other.app",
                poolWindowKey = bound, poolText = "旧应用题干", viewportText = "旧应用题干"
            )
        )

        val sent = runRequest(status.poolText, null).lastUserPrompt!!
        assertFalse("旧应用片段绝不能进入最终请求", sent.contains("fenbi 题干内容"))
    }

    // ============ §6.7 延迟返回的旧 OCR 不得污染新题会话 ============

    @Test
    fun late_returning_old_ocr_does_not_pollute_the_new_question_session_request() = runBlocking {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityText("第 10/15 题\n材料：旧题材料内容\nA 甲\nB 乙", bound, 1_000)
        // 在途 OCR 期间用户已翻到第 11 题（真正改变池内容 → acceptedSequence 推进）。
        val staleSequence = collector.beginOcrAttempt(2_000L)!!
        collector.onAccessibilityText("第 11/15 题\n材料：新题材料内容\nC 丙\nD 丁", bound, 3_000)

        val late = collector.completeOcrAttempt(
            staleSequence,
            organizationOf("第 10/15 题", "迟到 OCR 的旧题内容", "A 旧"),
            "raw-late-ocr",
            bound,
            4_000L
        )
        assertFalse("迟到 OCR 必须被判为过期而丢弃", late.applied)
        assertFalse("迟到的旧 OCR 不得污染池", collector.status().poolText.contains("迟到 OCR 的旧题内容"))

        val sent = runRequest(collector.status().poolText, null).lastUserPrompt!!
        assertFalse("迟到旧 OCR 绝不能进入最终请求", sent.contains("迟到 OCR 的旧题内容"))
        assertTrue("最终请求必须仍是新的第 11 题", sent.contains("第 11/15 题"))
    }

    // ============ §6.8 Hanako 自身窗口滚动不得当作粉笔滚动 ============

    @Test
    fun hanako_own_window_scroll_is_not_treated_as_target_scroll() {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityText("静止的粉笔题干\nA 甲\nB 乙", bound, 1_000)

        // Hanako 自身浮窗滚动：事件来源不是目标窗口 → 不得当作目标应用滚动。
        collector.onAccessibilityText(
            "Hanako 浮窗文字", "fun.kirari.hanako", 2_000L,
            scrollItems(0, 5), RealtimeEventKind.VIEW_SCROLLED, eventFromTargetWindow = false
        )

        val status = collector.status()
        assertEquals("非目标窗口的滚动必须判为 NOT_SCROLL", RealtimeScrollDecision.NOT_SCROLL, status.lastScrollDecision)
        assertNull("非目标窗口的滚动不得记录为目标应用滚动时间", status.lastTargetScrollAtMillis)
        assertFalse("非目标窗口滚动不得被当作目标滚动证据", status.lastEventFromTargetWindow == true)
    }

    // ============ §6.9 OCR 失败保留已验证片段，且不得声称获得完整新视口 ============

    @Test
    fun ocr_failure_keeps_verified_segments_and_never_claims_a_complete_new_viewport() = runBlocking {
        val collector = collector()
        collector.setEnabled(true)
        collector.setOcrCapability(true)
        collector.onAccessibilityText("$material\nA 甲\nB 乙", bound, 1_000)
        val before = collector.status().poolText

        val failed = collector.completeOcrAttempt(collector.beginOcrAttempt(2_000L)!!, null, null, bound, 2_200L)
        assertEquals(PoolUpdateResult.OCR_FAILED, failed.result)
        assertEquals("OCR 失败必须保留已验证的既有片段", before, collector.status().poolText)
        assertFalse("不得声称本次读到了有效文字", collector.status().lastNote?.contains("成功") == true)

        // 读取失败的池不得被当作可注入的当前屏证据 → 回退单次读取。
        assertEquals(
            PoolInjectionDecision.POOL_UNSETTLED,
            decidePoolInjection(
                boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound,
                poolText = before, viewportText = "$material\nA 甲\nB 乙",
                poolLastResult = collector.status().lastResult
            )
        )

        // 回退到本次新鲜视口（此处模拟 fresh live），最终请求不得伪造任何未识别内容。
        val sent = runRequest("", "$material\nA 甲\nB 乙").lastUserPrompt!!
        assertTrue("回退请求必须含本次新鲜视口", sent.contains("A 甲"))
        assertFalse("不得凭空补写 OCR 未识别的文字", sent.contains("A 甲选项内容"))
    }

    // ============ §6.10 请求 = 已确认累计片段 + 本次有效视口（无重复、无旧题） ============

    @Test
    fun request_includes_verified_accumulated_segments_plus_live_viewport_without_duplicates() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", "$material\nA. 甲选项内容\nB. 乙选项内容", 1_000))
        val accumulated = pool.currentQuestion()!!.text
        val live = "C. 丙选项内容\nD. 丁选项内容"

        val sent = runRequest(accumulated, live).lastUserPrompt!!
        assertTrue("必须含已确认的累计片段", sent.contains("A. 甲选项内容"))
        assertTrue("必须含本次新鲜视口", sent.contains("C. 丙选项内容"))
        // R8.7 P2：累计段与「本次当前屏」必须使用**不同**的分段标识，
        // 否则模型无法区分「已确认的累计内容」与「最新视口」。
        assertTrue(
            "累计与新鲜视口之间必须有当前屏标识",
            sent.contains(LIVE_VIEWPORT_MARK)
        )
        assertTrue(
            "累计片段必须排在新鲜视口之前",
            sent.indexOf(material) < sent.indexOf(LIVE_VIEWPORT_MARK)
        )
        assertEquals("同一片段不得重复注入", 1, sent.countOf("C. 丙选项内容"))
        assertTrue("必须含用户指令", sent.contains(userInstruction))
    }

    // ============ §6.11 关联未知（UNPROVEN）不得自动升级为已确认同题片段 ============

    @Test
    fun association_unknown_is_never_upgraded_to_a_confirmed_segment() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", "$material\nA. 甲选项内容\nB. 乙选项内容", 1_000))
        val result = pool.observe(a11y("u1", "以下结论中不能由题干推出的是：甲的身高高于乙", 2_000))
        assertEquals(PoolUpdateResult.UNPROVEN, result)

        val snap = pool.currentQuestion()!!
        assertFalse("未确认片段不得进入可注入文本", snap.text.contains("甲的身高"))
        assertTrue("未确认片段必须被明确排除", snap.excludedSegmentIds.contains("u1"))
        assertFalse("未确认片段不得被列为已注入", snap.includedSegmentIds.contains("u1"))
        assertEquals(1, pool.unconfirmedFragmentCount())

        // 反复上报同一未确认帧：既不升级、也不重复堆积。
        assertEquals(PoolUpdateResult.UNPROVEN, pool.observe(a11y("u2", "以下结论中不能由题干推出的是：甲的身高高于乙", 3_000)))
        assertEquals(1, pool.unconfirmedFragmentCount())

        val sent = runRequest(pool.currentQuestion()!!.text, null).lastUserPrompt!!
        assertFalse("关联未知片段绝不能进入最终请求", sent.contains("甲的身高"))
        assertTrue("已确认正文必须仍在请求中", sent.contains("A. 甲选项内容"))
    }

    // ============ §6.12 片段从采集、合并到最终请求的链路可追踪 ============

    @Test
    fun segment_lifecycle_is_traceable_from_collection_to_final_request() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("seg-1", "$material\nA. 甲选项内容\nB. 乙选项内容", 1_000))
        pool.observe(a11y("seg-2", "$material\nC. 丙选项内容\nD. 丁选项内容", 2_000))
        pool.observe(a11y("unproven-1", "以下结论中不能由题干推出的是：甲的身高高于乙", 3_000))

        val snap = pool.currentQuestion()!!
        assertEquals("已确认片段必须按采集先后可追踪", listOf("seg-1", "seg-2"), snap.includedSegmentIds)
        assertEquals(listOf("unproven-1"), snap.excludedSegmentIds)
        assertEquals(2, snap.segmentCount)
        assertTrue("独立片段必须有分段标记", snap.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK))

        val sent = runRequest(snap.text, null).lastUserPrompt!!
        // 每个「已确认」片段的可定位内容都必须出现在最终请求里；「未确认」片段则绝不出现。
        assertTrue(sent.contains("A. 甲选项内容"))
        assertTrue(sent.contains("C. 丙选项内容"))
        assertFalse("被排除的片段不得出现在最终请求", sent.contains("甲的身高"))
    }
}
