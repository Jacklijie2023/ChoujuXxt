package `fun`.kirari.hanako.screencontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「累计同题快照注入判定」的纯逻辑单元测试。
 *
 * 覆盖：绑定门控、池空、跨应用、当前前台非目标、同题覆盖注入、翻新题回退，
 * 以及覆盖比例 / 超集行数阈值。真机仍需验证实际注入的最终请求体。
 */
class PoolInjectionTest {

    private val bound = "com.example.exam"

    @Test
    fun unboundTargetNeverInjects() {
        assertEquals(
            PoolInjectionDecision.NO_BOUND_TARGET,
            decidePoolInjection(boundPackage = null, currentWindowPackage = bound, poolWindowKey = bound, poolText = "题干", viewportText = "题干")
        )
        assertEquals(
            PoolInjectionDecision.NO_BOUND_TARGET,
            decidePoolInjection(boundPackage = "   ", currentWindowPackage = bound, poolWindowKey = bound, poolText = "题干", viewportText = "题干")
        )
    }

    @Test
    fun emptyPoolNeverInjects() {
        assertEquals(
            PoolInjectionDecision.NO_POOL_QUESTION,
            decidePoolInjection(boundPackage = bound, currentWindowPackage = bound, poolWindowKey = null, poolText = "", viewportText = "题干")
        )
        assertEquals(
            PoolInjectionDecision.NO_POOL_QUESTION,
            decidePoolInjection(boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound, poolText = "   ", viewportText = "题干")
        )
    }

    @Test
    fun poolFromOtherAppNeverInjects() {
        assertEquals(
            PoolInjectionDecision.POOL_WINDOW_MISMATCH,
            decidePoolInjection(boundPackage = bound, currentWindowPackage = bound, poolWindowKey = "com.other.app", poolText = "题干", viewportText = "题干")
        )
    }

    @Test
    fun currentWindowNotTargetNeverInjects() {
        assertEquals(
            PoolInjectionDecision.CURRENT_WINDOW_NOT_TARGET,
            decidePoolInjection(boundPackage = bound, currentWindowPackage = "com.other.app", poolWindowKey = bound, poolText = "题干", viewportText = "题干")
        )
        // 前台无法确认（读不到窗口包名）时同样不得注入。
        assertEquals(
            PoolInjectionDecision.CURRENT_WINDOW_NOT_TARGET,
            decidePoolInjection(boundPackage = bound, currentWindowPackage = null, poolWindowKey = bound, poolText = "题干", viewportText = "题干")
        )
    }

    @Test
    fun sameQuestionScrollAccumulationInjectsPoolSnapshot() {
        // 池跨屏累积了题干 + A/B 两个选项；当前屏只显示到 B 选项。
        val poolText = "题目：下列说法正确的是\nA. 选项一\nB. 选项二"
        val viewport = "B. 选项二"
        assertEquals(
            PoolInjectionDecision.INJECT_POOL,
            decidePoolInjection(boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound, poolText = poolText, viewportText = viewport)
        )
    }

    @Test
    fun newQuestionOnScreenFallsBackInsteadOfInjectingOldQuestion() {
        // 用户已翻到一道尚未采集的新题：当前屏内容不在池中，必须回退单次读取，绝不注入旧题。
        val poolText = "题目：下列说法正确的是\nA. 选项一\nB. 选项二"
        val viewport = "题目：下列关于化学反应速率的说法正确的是"
        assertEquals(
            PoolInjectionDecision.VIEWPORT_NOT_COVERED,
            decidePoolInjection(boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound, poolText = poolText, viewportText = viewport)
        )
    }

    @Test
    fun coverageRatioIsOneWhenPoolContainsEveryViewportLine() {
        val pool = "题目：题干内容\nA. 甲\nB. 乙"
        val viewport = "题目：题干内容\nB. 乙"
        assertEquals(1.0, viewportCoverageRatio(pool, viewport), 1e-9)
    }

    @Test
    fun poolIsNotSupersetWhenItHasFewerLinesThanViewport() {
        val pool = "题目：题干内容"
        val viewport = "题目：题干内容\nA. 甲\nB. 乙"
        assertFalse("池行数少于当前视口行数时不得视为超集", poolSupersedesViewport(pool, viewport))
    }

    @Test
    fun blankViewportCannotProveSameQuestion() {
        assertFalse(poolSupersedesViewport("题目：题干内容", "   "))
        assertEquals(0.0, viewportCoverageRatio("题目：题干内容", ""), 1e-9)
    }

    @Test
    fun whitespaceDifferencesDoNotAffectCoverage() {
        // OCR 换行/空格抖动不应破坏同题证明（按去空白行比较）。
        val pool = "题目： 下列说法 正确的是\nA.  选项一"
        val viewport = "题目：下列说法正确的是"
        assertTrue(poolSupersedesViewport(pool, viewport))
    }

    // ---- R8.3 新题隔离 ----

    @Test
    fun differentQuestionNumberOnSharedMaterialNeverInjectsOldPool() {
        // 共享材料的「一拖 N」（资料分析）：池属于第 10 题，当前屏已是第 11 题 —— 材料完全相同，
        // 仅靠行重合（poolSupersedesViewport）无法区分，必须靠题号正向矛盾拒绝注入旧题池。
        val poolText = "第 10/15 题\n材料：某市 2020 年新业态营业收入\nA. 甲\nB. 乙"
        val viewport = "第 11/15 题\n材料：某市 2020 年新业态营业收入"
        assertEquals(
            PoolInjectionDecision.POOL_NOT_SAME_QUESTION,
            decidePoolInjection(
                boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound,
                poolText = poolText, viewportText = viewport,
                poolQuestionNumber = 10, viewportQuestionNumber = 11
            )
        )
    }

    @Test
    fun unsettledPoolNeverInjects() {
        // R8.6：读取失败（OCR_FAILED）→ 当前屏幕状态未知，仍必须回退单次读取。
        assertEquals(
            PoolInjectionDecision.POOL_UNSETTLED,
            decidePoolInjection(
                boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound,
                poolText = "题目：下列说法正确的是\nA. 选项一\nB. 选项二", viewportText = "B. 选项二",
                poolLastResult = PoolUpdateResult.OCR_FAILED
            )
        )
    }

    @Test
    fun unstablePoolStillInjectsWhenViewportProvesCoverage() {
        // R8.6 变更：UNSTABLE 不再一律拒绝注入。旧门控把「相邻视口只有部分重叠」这一**正常**情形
        // 判为不可注入，使跨屏累计彻底失效（真机长题故障的直接成因）。现在只要有**可证明的同题联系**
        // （池覆盖当前屏，或共享一段足够长的连续材料），仍允许注入已确认的累计内容。
        assertEquals(
            PoolInjectionDecision.INJECT_POOL,
            decidePoolInjection(
                boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound,
                poolText = "题目：下列说法正确的是\nA. 选项一\nB. 选项二", viewportText = "B. 选项二",
                poolLastResult = PoolUpdateResult.UNSTABLE
            )
        )
        // 本地读取「关联未知」（UNPROVEN）同样不改变池中已确认的可注入内容，只要有同题联系即可注入。
        assertEquals(
            PoolInjectionDecision.INJECT_POOL,
            decidePoolInjection(
                boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound,
                poolText = "题目：下列说法正确的是\nA. 选项一\nB. 选项二", viewportText = "B. 选项二",
                poolLastResult = PoolUpdateResult.UNPROVEN
            )
        )
    }

    @Test
    fun unsettledPoolNeverInjectsWithoutAnyProvableLink() {
        // R8.6：即便池判定 UNSTABLE，只要当前视口与池**没有任何可证明的同题联系**（不覆盖、且无足够长的
        // 连续共享材料），仍必须回退单次读取 —— 绝不把可能属于旧题的内容注入当前（可能是另一道题的）屏幕。
        assertEquals(
            PoolInjectionDecision.VIEWPORT_NOT_COVERED,
            decidePoolInjection(
                boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound,
                poolText = "言语理解与表达 第 11/15 题\n题干第一行\nA. 甲\nB. 乙",
                viewportText = "以下结论中不能由题干推出的是：\n甲的身高高于乙",
                poolLastResult = PoolUpdateResult.UNSTABLE
            )
        )
    }

    @Test
    fun sameQuestionNumberAcrossScrollStillInjects() {
        // 题号相同（同题）且池状态已定（OVERLAP_MERGED）→ 正常注入累计快照（不被 R8.3 隔离误伤）。
        val poolText = "第 10/15 题\nA. 选项一\nB. 选项二"
        assertEquals(
            PoolInjectionDecision.INJECT_POOL,
            decidePoolInjection(
                boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound,
                poolText = poolText, viewportText = "B. 选项二",
                poolLastResult = PoolUpdateResult.OVERLAP_MERGED,
                poolQuestionNumber = 10, viewportQuestionNumber = 10
            )
        )
    }

    @Test
    fun settledCompletePoolStillDoesNotInjectWhenViewportCannotProveCoverage() {
        // 场景 7：池判定已定（OVERLAP_MERGED，看似 COMPLETE），但**当前屏没有可证明覆盖的内容**
        // （本次识别失败 / 视口为空）→ 不得因为「历史池显示 COMPLETE」就无条件注入旧池冒充当前题目。
        assertEquals(
            PoolInjectionDecision.VIEWPORT_NOT_COVERED,
            decidePoolInjection(
                boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound,
                poolText = "第 10/15 题\n题干\nA. 甲\nB. 乙", viewportText = "",
                poolLastResult = PoolUpdateResult.OVERLAP_MERGED
            )
        )
    }

    @Test
    fun unknownQuestionNumberFallsBackToCoverageDecision() {
        // 任一侧题号解析不到（OCR 抖动）时不做题号判定，仍按覆盖率决定（此处池覆盖当前屏 → 注入）。
        val poolText = "题目：题干内容\nA. 甲\nB. 乙"
        assertEquals(
            PoolInjectionDecision.INJECT_POOL,
            decidePoolInjection(
                boundPackage = bound, currentWindowPackage = bound, poolWindowKey = bound,
                poolText = poolText, viewportText = "B. 乙",
                poolQuestionNumber = 7, viewportQuestionNumber = null
            )
        )
    }
}
