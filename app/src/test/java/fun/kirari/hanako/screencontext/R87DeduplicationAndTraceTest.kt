package `fun`.kirari.hanako.screencontext

import `fun`.kirari.hanako.chat.ChatConfig
import `fun`.kirari.hanako.chat.ChatSessionHolder
import `fun`.kirari.hanako.chat.ChatStreamSource
import `fun`.kirari.hanako.chat.ScreenContextResult
import `fun`.kirari.hanako.chat.buildInjectedPrompt
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * R8.7 —— 跨屏累计去重（P1）、累计 / 最新视口 / 未确认三态区分（P2）、请求注入自证（P3）。
 *
 * 断言落点：池内可注入文本 → [assembleAccumulatedWithLive] → [ChatSessionHolder] →
 * **真正传给 `streamSource.stream(...)` 的 `userPrompt` 与 trace 日志**，
 * 不使用任何「预期文本」替身。
 */
class R87DeduplicationAndTraceTest {

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
        @Volatile var lastSystemPrompt: String? = null
        @Volatile var lastTraceId: String? = null
        @Volatile var streamCalls = 0

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
            lastSystemPrompt = systemPrompt
            lastTraceId = traceId
            return flowOf(LlmEvent.Done)
        }
    }

    private class RequestRun(val source: CapturingSource, val logs: List<String>)

    private fun a11y(id: String, text: String, atMillis: Long, windowKey: String? = null) =
        accessibilityViewportFragmentOf(id = id, text = text, capturedAtMillis = atMillis, windowKey = windowKey)

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

    /** 走完整请求链路：注入器由用例提供，日志来自 [ChatSessionHolder] 的真实 trace 接缝。 */
    private suspend fun runRequest(
        injector: (suspend (String) -> ScreenContextResult)?
    ): RequestRun {
        val source = CapturingSource()
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val holder = ChatSessionHolder(
            streamSource = source,
            configProvider = { config },
            scope = CoroutineScope(Dispatchers.Default),
            screenContextInjector = injector,
            traceLogger = { line -> logs.add(line) }
        )
        holder.send(userInstruction)
        withTimeout(5_000) {
            while (source.lastUserPrompt == null) delay(5)
            while (!holder.isIdleForTest()) delay(5)
        }
        assertEquals("每次发送必须恰好一次模型请求", 1, source.streamCalls)
        return RequestRun(source, logs.toList())
    }

    private fun String.countOf(needle: String): Int = if (needle.isEmpty()) 0 else split(needle).size - 1

    private fun fieldValue(line: String, key: String): String? =
        Regex("$key=(\\S+)").find(line)?.groupValues?.get(1)

    // 长题材料（供跨视口共享 ≥ 20 连续字符）。
    private val m1 = "资料：某市 2023 年规模以上工业企业实现营业收入 1250 亿元"
    private val m2 = "同比增长 18%，利润总额 320 亿元，其中制造业占 76%"
    private val m3 = "分行业看，采矿业增长 9%，电力热力增长 21%"

    /** 一屏完整长题：倒计时 + 3 行材料 + A/B。 */
    private val fullScreen = "剩余时间 01:20\n$m1\n$m2\n$m3\nA. 甲选项内容\nB. 乙选项内容"

    // ==================================================================
    // P1-1 完全相同的视口重复采集：不重复增长
    // ==================================================================

    @Test
    fun identical_viewport_recollected_repeatedly_does_not_grow_the_pool() = runBlocking {
        val pool = ScreenContextPool()
        assertEquals(PoolUpdateResult.NEW_QUESTION, pool.observe(a11y("v1", fullScreen, 1_000)))
        val before = pool.currentQuestion()!!.text
        val beforeFragments = pool.fragmentCount()

        // 同一屏被连续上报 5 次（同一次滚动后的无变化采帧）。
        for (i in 1..5) {
            val result = pool.observe(a11y("v${i + 1}", fullScreen, 2_000L + i * 1_000L))
            assertTrue(
                "同一屏重复采集不得判为新片段（实得 $result）",
                result == PoolUpdateResult.NO_CHANGE || result == PoolUpdateResult.DUPLICATE
            )
        }
        val snap = pool.currentQuestion()!!
        assertEquals("同一屏重复采集不得增加任何文字", before, snap.text)
        assertEquals("同一屏重复采集不得增加片段数", beforeFragments, pool.fragmentCount())
        assertEquals("正式注入文本中材料只出现一次", 1, snap.text.countOf(m1))
    }

    // ==================================================================
    // P1-2 高度重叠、仅界面抖动（新增行全是页面壳文字）：不重复保存整屏
    // ==================================================================

    @Test
    fun echo_viewport_differing_only_in_countdown_does_not_accumulate_the_whole_screen() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", fullScreen, 1_000))
        val before = pool.currentQuestion()!!.text
        val beforeFragments = pool.fragmentCount()

        // 倒计时 `01:20` → `01:19`：整屏 6 行中 5 行被已保存内容覆盖，唯一「新增行」是页面壳文字。
        val echo = fullScreen.replace("01:20", "01:19")
        val result = pool.observe(a11y("v2", echo, 2_000))
        assertNotEquals("回声视口不得被当成新的同题片段", PoolUpdateResult.NEW_SEGMENT, result)
        assertEquals(PoolUpdateResult.DUPLICATE, result)

        val snap = pool.currentQuestion()!!
        assertEquals("回声视口不得改变已确认的题目材料", before, snap.text)
        assertEquals("回声视口不得新增片段", beforeFragments, pool.fragmentCount())
        assertFalse("倒计时等页面壳文字不得进入题目材料", snap.text.contains("01:19"))
        assertFalse("回声视口不得制造分段标记", snap.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK))

        val run = runRequest { tid ->
            val assembly = assembleAccumulatedWithLive(snap.text, null)
            ScreenContextResult.Available(
                text = assembly.text,
                traceId = tid,
                source = ScreenContextSource.POOL_SNAPSHOT,
                readReport = report(tid, assembly.text.length),
                includedSegmentIds = snap.includedSegmentIds
            )
        }
        val sent = run.source.lastUserPrompt!!
        assertEquals("材料在最终请求里只出现一次", 1, sent.countOf(m1))
        assertEquals("选项在最终请求里只出现一次", 1, sent.countOf("A. 甲选项内容"))
    }

    // ==================================================================
    // P1-3 OCR 抖动（正文字符级抖动）同样不得重复累计
    // ==================================================================

    @Test
    fun body_line_ocr_jitter_is_treated_as_the_same_viewport() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", fullScreen, 1_000))
        val before = pool.currentQuestion()!!.text

        // 正文一行被 OCR 抖成一个字（`分行业看` → `分行业瞧`）：整屏 6/6 行仍被已保存内容覆盖。
        val jittered = fullScreen.replace("分行业看", "分行业瞧")
        val result = pool.observe(a11y("v2", jittered, 2_000))
        assertEquals("只差一个字的同一屏必须判为重复视口", PoolUpdateResult.DUPLICATE, result)
        assertEquals("OCR 抖动不得改变已确认的题目材料", before, pool.currentQuestion()!!.text)
        assertEquals("OCR 抖动不得新增片段", 1, pool.fragmentCount())
    }

    // ==================================================================
    // P1-4 高度重叠且确有新内容：只追加新增行，不重复保存整屏
    // ==================================================================

    @Test
    fun highly_overlapping_viewport_appends_only_the_new_line_not_the_whole_screen() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", fullScreen, 1_000))
        val before = pool.currentQuestion()!!.text
        val footer = "本题解析见下一页说明"

        // 与已保存内容有 5 行可证明的边界连续重叠，只多出一行真正的新内容。
        val scrolled = "$m1\n$m2\n$m3\nA. 甲选项内容\nB. 乙选项内容\n$footer"
        val result = pool.observe(a11y("v2", scrolled, 2_000))
        assertEquals("可证明的边界重叠必须走增量合并", PoolUpdateResult.OVERLAP_MERGED, result)

        val snap = pool.currentQuestion()!!
        assertEquals("增量合并只能新增那一行，不得重复整屏", "$before\n$footer", snap.text)
        assertEquals("重复的材料行不得被再次保存", 1, snap.text.countOf(m1))
        assertEquals("重复的选项行不得被再次保存", 1, snap.text.countOf("A. 甲选项内容"))
        assertEquals("新增行必须保存且只保存一次", 1, snap.text.countOf(footer))
        assertEquals("增长量必须等于「换行 + 新增行」", footer.length + 1, snap.text.length - before.length)
        assertFalse("连续增量合并不需要分段标记", snap.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK))

        assertEquals("连续正文由 2 个已合并片段共同贡献", 2, snap.includedSegmentIds.size)
        assertEquals("segmentCount 必须等于实际注入片段数", snap.includedSegmentIds.size, snap.segmentCount)
    }

    // ==================================================================
    // P1-4b 无法证明连续但确有新内容：只把新增行落成独立片段
    // ==================================================================

    @Test
    fun separate_segment_stores_only_new_lines_not_the_whole_viewport() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", "剩余时间 01:20\n$m1\nA. 甲选项内容\nB. 乙选项内容", 1_000))
        val before = pool.currentQuestion()!!.text

        // 无边界连续重叠，但共享材料 → 同题新片段；其中 m1 / B 已保存，只有 m3 与 C 是新增。
        val result = pool.observe(a11y("v2", "$m1\n$m3\nC. 丙选项内容", 2_000))
        assertEquals("有同题证据但无法证明连续时必须保留为独立片段", PoolUpdateResult.NEW_SEGMENT, result)

        val snap = pool.currentQuestion()!!
        assertEquals("独立片段只能包含新增行", "$m3\nC. 丙选项内容", snap.fragments.last().text)
        assertTrue("已确认正文必须保留在前", snap.text.startsWith(before))
        assertEquals("已保存的材料不得重复", 1, snap.text.countOf(m1))
        assertEquals("B 选项不得重复", 1, snap.text.countOf("B. 乙选项内容"))
        assertTrue("新增内容必须带分段标记", snap.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK))
    }

    // ==================================================================
    // P1-5 明确重叠且内容延伸：保留新增部分且不重复
    // ==================================================================

    @Test
    fun overlapping_continuation_keeps_new_part_once_in_the_request() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", "$m1\n$m2\nA. 甲选项内容\nB. 乙选项内容", 1_000))
        val result = pool.observe(a11y("v2", "B. 乙选项内容\nC. 丙选项内容\nD. 丁选项内容", 2_000))
        assertEquals("相邻边界连续重叠必须判为内容扩展", PoolUpdateResult.OVERLAP_MERGED, result)

        val snap = pool.currentQuestion()!!
        val sent = runRequest { tid ->
            val assembly = assembleAccumulatedWithLive(snap.text, "D. 丁选项内容")
            ScreenContextResult.Available(
                text = assembly.text,
                traceId = tid,
                source = ScreenContextSource.POOL_SNAPSHOT,
                readReport = report(tid, assembly.text.length),
                includedSegmentIds = snap.includedSegmentIds,
                hasSegmentBreak = assembly.hasLiveMarker,
                liveIncluded = assembly.liveIncluded,
                liveVerified = assembly.liveVerified
            )
        }.source.lastUserPrompt!!

        val order = listOf(m1, "A. 甲选项内容", "B. 乙选项内容", "C. 丙选项内容", "D. 丁选项内容")
            .map { sent.indexOf(it) }
        assertTrue("合并文本必须按屏幕顺序排列", order.all { it >= 0 } && order.zipWithNext().all { (a, b) -> b > a })
        assertEquals("重叠行不得重复注入", 1, sent.countOf("B. 乙选项内容"))
        assertEquals("新增行不得重复注入", 1, sent.countOf("D. 丁选项内容"))
    }

    // ==================================================================
    // P2-1 缺乏关联证据：不合并、不注入、明确标记为未确认
    // ==================================================================

    @Test
    fun unrelated_viewport_without_shared_evidence_is_kept_unconfirmed_and_never_injected() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", "$m1\n$m2\nA. 甲选项内容", 1_000))
        val before = pool.currentQuestion()!!.text

        val unrelated = "文化遗产保护工作持续推进，各地积极修缮古建筑并开展公众教育活动"
        val result = pool.observe(a11y("v2", unrelated, 2_000))
        assertEquals("没有可证明的同题证据时必须判为关联未知", PoolUpdateResult.UNPROVEN, result)

        val snap = pool.currentQuestion()!!
        assertEquals("关联未知不得改动已确认内容", before, snap.text)
        assertTrue("未确认片段必须登记在排除列表", snap.excludedSegmentIds.contains("v2"))
        assertFalse("未确认片段绝不能进入可注入片段列表", snap.includedSegmentIds.contains("v2"))

        val sent = runRequest { tid ->
            val assembly = assembleAccumulatedWithLive(snap.text, null)
            ScreenContextResult.Available(
                text = assembly.text,
                traceId = tid,
                source = ScreenContextSource.POOL_SNAPSHOT,
                readReport = report(tid, assembly.text.length),
                includedSegmentIds = snap.includedSegmentIds
            )
        }.source.lastUserPrompt!!
        assertFalse("关联未知内容不得进入最终请求", sent.contains(unrelated))
        assertTrue("已确认内容必须保留", sent.contains(m1))
    }

    // ==================================================================
    // P2-2 明确切换新题：旧题上下文被隔离
    // ==================================================================

    @Test
    fun explicit_question_switch_isolates_previous_accumulated_content() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("v1", "$m1\nA. 甲选项内容", 1_000, windowKey = "fenbi/activity-one"))
        pool.observe(a11y("v2", "$m2\nB. 乙选项内容", 2_000, windowKey = "fenbi/activity-one"))

        val next = "某基层单位开展志愿服务，累计服务时长 860 小时，参与人数 240 人"
        val result = pool.observe(a11y("v3", next, 3_000, windowKey = "fenbi/activity-two"))
        assertEquals("目标窗口变化必须判为新题", PoolUpdateResult.NEW_QUESTION, result)

        val snap = pool.currentQuestion()!!
        assertEquals("新题上下文只包含新题文字", next, snap.text)
        assertFalse("旧题材料不得进入新题上下文", snap.text.contains(m1))
        assertEquals("新题片段数必须从 1 重新开始", 1, pool.fragmentCount())
    }

    // ==================================================================
    // P2-3 最新视口不可用：明确报告原因，不得冒充当前屏幕
    // ==================================================================

    @Test
    fun unavailable_live_viewport_is_reported_instead_of_claiming_current_screen() = runBlocking {
        val accumulated = "$m1\n$m2\nA. 甲选项内容"
        val assembly = assembleAccumulatedWithLive(
            accumulatedText = accumulated,
            liveText = null,
            liveViewportId = "live-ocr-42",
            liveUnavailableReason = "content_insufficient"
        )
        assertFalse("未采集到视口时不得声称已并入最新视口", assembly.liveIncluded)
        assertFalse("未采集到视口时 liveVerified 必须为 false", assembly.liveVerified)
        assertFalse("未采集到视口时不得出现「当前屏幕」分段", assembly.hasLiveMarker)
        assertTrue("必须显式声明未采集到当前屏幕", assembly.text.contains(LIVE_VIEWPORT_UNAVAILABLE_MARK))
        assertTrue("必须写明不可用原因", assembly.text.contains("content_insufficient"))
        assertTrue("已确认的累计内容必须保留", assembly.text.contains(m1))
        assertEquals("累计内容不得改动", accumulated, assembly.text.substringBefore("\n" + LIVE_VIEWPORT_UNAVAILABLE_MARK))

        val run = runRequest { tid ->
            ScreenContextResult.Available(
                text = assembly.text,
                traceId = tid,
                source = ScreenContextSource.POOL_SNAPSHOT,
                readReport = report(tid, assembly.text.length),
                liveIncluded = assembly.liveIncluded,
                liveVerified = assembly.liveVerified,
                liveViewportId = "live-ocr-42"
            )
        }
        val sent = run.source.lastUserPrompt!!
        assertTrue("最终请求必须带上「未采集到当前屏幕」的声明", sent.contains(LIVE_VIEWPORT_UNAVAILABLE_MARK))
        assertFalse("最终请求不得伪装成拿到了当前完整题目", sent.contains(LIVE_VIEWPORT_MARK))

        val streamSend = run.logs.first { it.contains(" stream-send ") }
        assertEquals("日志必须来自真实发送变量：liveIncluded", "false", fieldValue(streamSend, "liveIncluded"))
        assertEquals("日志必须来自真实发送变量：liveVerified", "false", fieldValue(streamSend, "liveVerified"))
        assertEquals("日志必须记录真实 prompt 长度", "${sent.length}", fieldValue(streamSend, "promptChars"))
    }

    // ==================================================================
    // P3-1 最终 prompt 的片段与日志片段 ID 一一对应
    // ==================================================================

    @Test
    fun injected_segment_ids_in_the_log_match_the_segments_actually_in_the_prompt() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("merged-1", "$m1\n$m2\nA. 甲选项内容\nB. 乙选项内容", 1_000))
        // 独立同题片段（与既有正文不连续但共享材料）。
        assertEquals(
            PoolUpdateResult.NEW_SEGMENT,
            pool.observe(a11y("seg-1", "$m1\n$m3\nC. 丙选项内容", 2_000))
        )
        // 关联未知（不注入）。
        assertEquals(
            PoolUpdateResult.UNPROVEN,
            pool.observe(a11y("bad-1", "社区图书馆延长开放时间，提供自习与讲座服务", 3_000))
        )

        val snap = pool.currentQuestion()!!
        assertEquals("实际注入片段必须是「连续正文 + 独立片段」", listOf("merged-1", "seg-1"), snap.includedSegmentIds)
        assertEquals("关联未知片段必须在排除列表", listOf("bad-1"), snap.excludedSegmentIds)

        val run = runRequest { tid ->
            val assembly = assembleAccumulatedWithLive(snap.text, null)
            ScreenContextResult.Available(
                text = assembly.text,
                traceId = tid,
                source = ScreenContextSource.POOL_SNAPSHOT,
                readReport = report(tid, assembly.text.length),
                includedSegmentIds = snap.includedSegmentIds,
                hasSegmentBreak = assembly.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK)
            )
        }
        val sent = run.source.lastUserPrompt!!
        val streamSend = run.logs.first { it.contains(" stream-send ") }
        assertEquals(
            "日志中的注入片段 ID 必须与快照一致",
            snap.includedSegmentIds.joinToString(","),
            fieldValue(streamSend, "injectedSegmentIds")
        )

        // 每个被记录为「已注入」的片段，其文字必须真的出现在最终 prompt 中。
        snap.fragments.filter { it.id in snap.includedSegmentIds }.forEach { fragment ->
            fragment.readingOrderLines.forEach { line ->
                assertTrue("已注入片段 ${fragment.id} 的行必须出现在最终 prompt 中", sent.contains(line))
            }
        }
        // 被排除的片段一个字都不能进入 prompt。
        snap.fragments.filter { it.id in snap.excludedSegmentIds }.forEach { fragment ->
            fragment.readingOrderLines.forEach { line ->
                assertFalse("未确认片段 ${fragment.id} 不得进入最终 prompt", sent.contains(line))
            }
        }
        assertTrue("独立片段之间必须出现分段标记", snap.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK))
        assertEquals(
            "contextFp 必须等于实际注入文本的稳定指纹",
            ocrFingerprintOf(snap.text),
            fieldValue(streamSend, "contextFp")
        )
        assertEquals("contextChars 必须等于实际注入文本长度", "${snap.text.length}", fieldValue(streamSend, "contextChars"))
    }

    // ==================================================================
    // P3-2 同一 traceId 串起采集 → 组装 → 最终发送；viewportId 与 traceId 不同
    // ==================================================================

    @Test
    fun one_trace_id_links_assembly_and_the_prompt_actually_sent() = runBlocking {
        val pool = ScreenContextPool()
        pool.observe(a11y("merged-1", fullScreen, 1_000))
        val snap = pool.currentQuestion()!!
        val viewportId = "live-ocr-1700000000000"
        var injectorTraceId: String? = null

        val run = runRequest { tid ->
            injectorTraceId = tid
            val assembly = assembleAccumulatedWithLive(snap.text, "C. 丙选项内容", liveViewportId = viewportId)
            ScreenContextResult.Available(
                text = assembly.text,
                traceId = tid,
                source = ScreenContextSource.POOL_SNAPSHOT,
                readReport = report(tid, assembly.text.length),
                includedSegmentIds = snap.includedSegmentIds,
                hasSegmentBreak = assembly.text.contains(ScreenContextPool.SEGMENT_BREAK_MARK) ||
                    assembly.hasLiveMarker,
                liveIncluded = assembly.liveIncluded,
                liveVerified = assembly.liveVerified,
                liveViewportId = viewportId
            )
        }

        val traceId = run.source.lastTraceId
        assertNotNull("发送时必须带上 traceId", traceId)
        assertEquals("组装与最终发送必须是同一个 traceId", injectorTraceId, traceId)
        assertEquals("traceId 必须等于本次请求 id", traceId, run.source.lastTraceId)

        val contextInjected = run.logs.first { it.contains(" context-injected ") }
        val streamSend = run.logs.first { it.contains(" stream-send ") }
        assertTrue("context-injected 必须用同一 traceId", contextInjected.startsWith("trace=$traceId "))
        assertTrue("stream-send 必须用同一 traceId", streamSend.startsWith("trace=$traceId "))
        assertEquals(
            "viewportId 必须与 traceId 区分开（两个不同对象）",
            viewportId,
            fieldValue(streamSend, "liveViewportId")
        )
        assertNotEquals("视口标识不得复用请求 traceId", traceId, viewportId)

        // 日志里的 prompt 必须就是真正发送的 prompt。
        val sent = run.source.lastUserPrompt!!
        assertEquals("promptChars 必须来自真实发送变量", "${sent.length}", fieldValue(streamSend, "promptChars"))
        assertEquals("promptFp 必须来自真实发送变量", ocrFingerprintOf(sent), fieldValue(streamSend, "promptFp"))
        assertEquals("最终 prompt 必须包含组装后的上下文", buildInjectedPrompt(userInstruction, snap.text + "\n" +
            LIVE_VIEWPORT_MARK + "\nC. 丙选项内容"), sent)
        assertEquals("最新视口必须带有独立标识", "true", fieldValue(streamSend, "liveIncluded"))
        assertEquals("分段标识必须与真实文本一致", "true", fieldValue(streamSend, "hasSegmentBreak"))
        assertFalse("日志不得包含用户聊天正文", run.logs.any { it.contains(userInstruction) })
    }

    // ==================================================================
    // P2-4 / §5.9 正常聊天与注入拒绝路径无回归
    // ==================================================================

    @Test
    fun normal_chat_without_screen_context_keeps_original_prompt_and_no_injection_logs() = runBlocking {
        val run = runRequest(injector = null)
        val sent = run.source.lastUserPrompt!!
        assertEquals("普通聊天必须原样发送用户指令", userInstruction, sent)
        assertEquals("普通聊天必须使用常规系统提示", "sys", run.source.lastSystemPrompt)
        assertFalse("普通聊天不得产生注入日志", run.logs.any { it.contains("context-injected") })
        val streamSend = run.logs.first { it.contains(" stream-send ") }
        assertEquals("普通聊天不得记录任何注入片段", "-", fieldValue(streamSend, "injectedSegmentIds"))
        assertEquals("普通聊天不得记录上下文来源", "none", fieldValue(streamSend, "source"))
    }

    @Test
    fun not_requested_screen_context_does_not_inject_or_block_the_request() = runBlocking {
        val run = runRequest { ScreenContextResult.NotRequested }
        assertEquals("未请求屏幕上下文时必须原样发送用户指令", userInstruction, run.source.lastUserPrompt)
        assertFalse("不得产生注入日志", run.logs.any { it.contains("context-injected") })
    }

    // ==================================================================
    // P1 单元层：新增信息量必须可核验，不能只看 poolChars 增长
    // ==================================================================

    @Test
    fun novelty_measurement_distinguishes_repeat_partial_overlap_and_new_content() {
        val existing = ocrLines("$m1\n$m2\nA. 甲选项内容")

        val identical = measureViewportNovelty(existing, ocrLines("$m1\n$m2\nA. 甲选项内容"))
        assertTrue("完全相同的视口必须判为重复", identical.isDuplicate)
        assertEquals("完全相同的视口不得有新增字符", 0, identical.newChars)
        assertEquals("完全相同的视口重复率必须为 100%", 1.0, identical.duplicateRatio, 0.0001)

        // 整屏回声（行数达到 MIN_ECHO_LINES）：必须判为「同一屏重复识别」。
        val screenLines = ocrLines(fullScreen)
        val echo = measureViewportNovelty(screenLines, screenLines)
        assertTrue("整屏重复采集必须判为回声", echo.isEcho)
        assertTrue("整屏重复采集不得有任何新增行", echo.newLines.isEmpty())

        val jittered = measureViewportNovelty(existing, ocrLines("$m1\n$m2\nA. 甲选项内客"))
        assertEquals("一字抖动必须计入覆盖", 0, jittered.newLines.size)
        assertEquals("一字抖动重复率应为 100%", 1.0, jittered.duplicateRatio, 0.0001)

        val extended = measureViewportNovelty(existing, ocrLines("$m2\nA. 甲选项内容\nC. 丙选项内容"))
        assertEquals("延伸视口必须保留新增行", listOf("C. 丙选项内容"), extended.newLines)
        assertTrue("延伸视口必须被识别为有新增内容", extended.isSubstantive)
        assertFalse("延伸视口不得判为回声", extended.isEcho)

        val chromeOnly = measureViewportNovelty(existing, ocrLines("$m1\n$m2\nA. 甲选项内容\n剩余时间 01:19"))
        assertTrue("新增行全为页面壳文字时必须被识别", chromeOnly.newLinesAreChromeOnly)
        assertFalse("页面壳文字不得构成新的题目材料", chromeOnly.isSubstantive)
    }
}
