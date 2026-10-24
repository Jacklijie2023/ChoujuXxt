package `fun`.kirari.hanako.screencontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSelectionTest {

    private val APP_TYPE = 1
    private val IME_TYPE = 2
    private val SYSTEM_TYPE = 3
    private val OWN = "fun.kirari.hanako"

    @Test
    fun nullPackageNameIsNeverThirdParty() {
        val candidates = listOf(
            WindowCandidate(packageName = null, type = APP_TYPE, layer = 0, isActive = true, isFocused = true),
            WindowCandidate(packageName = "com.example.quiz", type = APP_TYPE, layer = 0, isActive = false, isFocused = false)
        )
        // null 包名不能被当作第三方窗口，应落到真正的第三方窗口。
        assertEquals(1, selectTargetWindowIndex(candidates, OWN, APP_TYPE))
    }

    @Test
    fun ownWindowIsExcluded() {
        val candidates = listOf(
            WindowCandidate(packageName = OWN, type = APP_TYPE, layer = 0, isActive = true, isFocused = true),
            WindowCandidate(packageName = "com.example.quiz", type = APP_TYPE, layer = 0, isActive = false, isFocused = false)
        )
        assertEquals(1, selectTargetWindowIndex(candidates, OWN, APP_TYPE))
    }

    @Test
    fun noThirdPartyWindowReturnsNullInsteadOfOwnWindow() {
        val candidates = listOf(
            WindowCandidate(packageName = OWN, type = APP_TYPE, layer = 0, isActive = true, isFocused = true)
        )
        // 找不到第三方窗口时绝不回退返回本应用窗口。
        assertNull(selectTargetWindowIndex(candidates, OWN, APP_TYPE))
    }

    @Test
    fun imeAndSystemWindowsAreExcluded() {
        val candidates = listOf(
            WindowCandidate(packageName = "com.google.android.inputmethod.latin", type = IME_TYPE, layer = 3, isActive = true, isFocused = true),
            WindowCandidate(packageName = "com.android.systemui", type = SYSTEM_TYPE, layer = 2, isActive = false, isFocused = false),
            WindowCandidate(packageName = "com.example.quiz", type = APP_TYPE, layer = 1, isActive = false, isFocused = false)
        )
        assertEquals(2, selectTargetWindowIndex(candidates, OWN, APP_TYPE))
    }

    @Test
    fun prefersActiveOrFocusedOverFirstWindow() {
        val candidates = listOf(
            WindowCandidate(packageName = "com.a.background", type = APP_TYPE, layer = 0, isActive = false, isFocused = false),
            WindowCandidate(packageName = "com.b.dialog", type = APP_TYPE, layer = 1, isActive = true, isFocused = false)
        )
        // 不固定挑选第一个，优先活动窗口。
        assertEquals(1, selectTargetWindowIndex(candidates, OWN, APP_TYPE))
    }

    @Test
    fun fallsBackToLowestLayer() {
        val candidates = listOf(
            WindowCandidate(packageName = "com.a.base", type = APP_TYPE, layer = 0, isActive = false, isFocused = false),
            WindowCandidate(packageName = "com.b.higher", type = APP_TYPE, layer = 2, isActive = false, isFocused = false)
        )
        // 无活动/焦点时，取层级最低（最靠底层）的基窗。
        assertEquals(0, selectTargetWindowIndex(candidates, OWN, APP_TYPE))
    }

    // ===== isActiveWindowAcceptable：活动窗口快速返回路径的准入规则 =====

    @Test
    fun activeFastPathRejectsImeType() {
        // 包名有效，但已匹配的窗口类型是输入法：不得走快速返回路径。
        assertFalse(isActiveWindowAcceptable("com.example.ime", IME_TYPE, OWN, APP_TYPE))
    }

    @Test
    fun activeFastPathRejectsSystemType() {
        assertFalse(isActiveWindowAcceptable("com.android.systemui", SYSTEM_TYPE, OWN, APP_TYPE))
    }

    @Test
    fun activeFastPathRejectsNullPackage() {
        assertFalse(isActiveWindowAcceptable(null, APP_TYPE, OWN, APP_TYPE))
    }

    @Test
    fun activeFastPathRejectsOwnPackage() {
        assertFalse(isActiveWindowAcceptable(OWN, APP_TYPE, OWN, APP_TYPE))
    }

    @Test
    fun activeFastPathRejectsUnknownType() {
        // 无法确认窗口类型（matchedType 为 null）时不得凭包名直接返回，避免伪造读取成功。
        assertFalse(isActiveWindowAcceptable("com.example.quiz", null, OWN, APP_TYPE))
    }

    @Test
    fun activeFastPathAcceptsValidApplicationWindow() {
        assertTrue(isActiveWindowAcceptable("com.example.quiz", APP_TYPE, OWN, APP_TYPE))
    }

    // ===== 三态分离：目标身份确认与「文字是否读到」解耦 =====

    @Test
    fun windowListUnavailable_fallsBackToCorroboratingEventPackage() {
        // 窗口列表不可用（未获 flagRetrieveInteractiveWindows）：不能因为无法用类型字段佐证
        // 就直接否定真实的第三方应用窗口 —— 但必须要求「事件来源包名 == 活动窗口包名」作为替代证据。
        assertTrue(
            isActiveWindowAcceptable(
                packageName = "com.example.quiz",
                matchedType = null,
                ownPackage = OWN,
                applicationType = APP_TYPE,
                windowListAvailable = false,
                eventPackage = "com.example.quiz"
            )
        )
    }

    @Test
    fun windowListUnavailable_withoutCorroboratingEventKeepsSafeGate() {
        // 没有可用的事件来源证据时，仍保留安全门控（不放行任意第三方窗口）。
        assertFalse(
            isActiveWindowAcceptable(
                packageName = "com.example.quiz",
                matchedType = null,
                ownPackage = OWN,
                applicationType = APP_TYPE,
                windowListAvailable = false,
                eventPackage = null
            )
        )
        assertFalse(
            isActiveWindowAcceptable(
                packageName = "com.example.quiz",
                matchedType = null,
                ownPackage = OWN,
                applicationType = APP_TYPE,
                windowListAvailable = false,
                eventPackage = "com.example.other"
            )
        )
    }

    @Test
    fun windowListAvailable_keepsStrictTypeFiltering() {
        // 窗口列表可用时仍以类型严格过滤：输入法 / 系统栏不得被当作应用内容窗口。
        assertFalse(
            isActiveWindowAcceptable("com.example.ime", IME_TYPE, OWN, APP_TYPE, true, "com.example.ime")
        )
        assertFalse(
            isActiveWindowAcceptable("com.android.systemui", SYSTEM_TYPE, OWN, APP_TYPE, true, "com.android.systemui")
        )
        // 类型未知（matchedType 为 null）时也不得凭包名直接放行（避免伪造读取成功）。
        assertFalse(
            isActiveWindowAcceptable("com.example.quiz", null, OWN, APP_TYPE, true, "com.example.quiz")
        )
    }

    @Test
    fun eventPackageResolvesTargetIdentityIndependentlyOfTextReadability() {
        // 事件来源包名可直接确认目标应用身份，与「是否读到节点树文字」完全无关。
        assertEquals("com.example.quiz", resolveEventTargetPackage("com.example.quiz", OWN))
        // 本应用自身窗口 / 空包名 / 空白包名一律不确认为目标。
        assertNull(resolveEventTargetPackage(OWN, OWN))
        assertNull(resolveEventTargetPackage(null, OWN))
        assertNull(resolveEventTargetPackage("   ", OWN))
    }

    // ===== 受控「画面变化探测」门控 =====

    @Test
    fun probeAllowedOnlyWhenEveryGatePasses() {
        assertNull(
            probeSkipReason(
                realtimeEnabled = true,
                managerActive = true,
                captureSessionAvailable = true,
                a11yConnected = true,
                targetForegroundConfirmed = true,
                probeAllowedByCollector = true
            )
        )
    }

    @Test
    fun probeSkippedWhenTargetForegroundCannotBeConfirmed() {
        // 目标应用前台身份确认失败时，必须跳过探测，且原因可读（不得误启用截图探测）。
        val reason = probeSkipReason(
            realtimeEnabled = true,
            managerActive = true,
            captureSessionAvailable = true,
            a11yConnected = true,
            targetForegroundConfirmed = false,
            probeAllowedByCollector = true
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("无法确认目标应用位于前台"))
    }

    @Test
    fun probeSkippedAndReasonNamesCaptureSessionWhenUnavailable() {
        val reason = probeSkipReason(
            realtimeEnabled = true,
            managerActive = true,
            captureSessionAvailable = false,
            a11yConnected = true,
            targetForegroundConfirmed = true,
            probeAllowedByCollector = true
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("截图会话不可用"))
    }

    @Test
    fun probeSkippedForOtherGatesWithReadableReasons() {
        assertNotNull(
            probeSkipReason(
                realtimeEnabled = false,
                managerActive = true,
                captureSessionAvailable = true,
                a11yConnected = true,
                targetForegroundConfirmed = true,
                probeAllowedByCollector = true
            )
        )
        assertNotNull(
            probeSkipReason(
                realtimeEnabled = true,
                managerActive = false,
                captureSessionAvailable = true,
                a11yConnected = true,
                targetForegroundConfirmed = true,
                probeAllowedByCollector = true
            )
        )
        assertNotNull(
            probeSkipReason(
                realtimeEnabled = true,
                managerActive = true,
                captureSessionAvailable = true,
                a11yConnected = false,
                targetForegroundConfirmed = true,
                probeAllowedByCollector = true
            )
        )
        assertNotNull(
            probeSkipReason(
                realtimeEnabled = true,
                managerActive = true,
                captureSessionAvailable = true,
                a11yConnected = true,
                targetForegroundConfirmed = true,
                probeAllowedByCollector = false
            )
        )
    }

    // ===== R7 目标应用绑定语义：写入判定（S1）与绑定建立判定（S2） =====

    private val TARGET = "com.fenbi.android.servant"
    private val OTHER = "com.example.other"
    private val HOME = setOf("com.miui.home", "com.android.launcher3")

    @Test
    fun poolWriteIsDeniedInBothModesWhenNoBindingEstablished() {
        // S5①：boundPackage == null 时，AUTO 与 RESTRICTED 都不得写池（仅选择应用 ≠ 已建立绑定）。
        assertFalse("未绑定时一律不得写池", isPoolWriteAllowed(TARGET, null, OWN, HOME))
        assertFalse("空绑定同样不得写池", isPoolWriteAllowed(TARGET, "", OWN, HOME))
        // 「能建立绑定」与「已建立绑定」是两件事：即便允许建立绑定，此刻仍未绑定 → 不得写池。
        assertTrue(canEstablishBinding(TARGET, TargetAppMode.AUTO, null, OWN, HOME))
        assertTrue(canEstablishBinding(TARGET, TargetAppMode.RESTRICTED, TARGET, OWN, HOME))
        assertFalse(isPoolWriteAllowed(TARGET, null, OWN, HOME))
    }

    @Test
    fun restrictedModeMerelySelectingAppDoesNotAllowPoolWrite() {
        // S5②：RESTRICTED 下手动选择 ≠ 已激活 —— 只选应用、前台一致，但未显式绑定 → 不得写池。
        assertTrue("前台与手动选择一致时允许建立绑定", canEstablishBinding(TARGET, TargetAppMode.RESTRICTED, TARGET, OWN, HOME))
        assertFalse("仅选择应用不等于已建立绑定 → 不得写池", isPoolWriteAllowed(TARGET, null, OWN, HOME))
        // 前台与手动选择不一致时，连绑定都不允许建立。
        assertFalse(canEstablishBinding(OTHER, TargetAppMode.RESTRICTED, TARGET, OWN, HOME))
        // AUTO 模式不依赖手动选择值。
        assertTrue(canEstablishBinding(OTHER, TargetAppMode.AUTO, TARGET, OWN, HOME))
    }

    @Test
    fun poolWriteRequiresExplicitBindingAndMatchingTarget() {
        // S5③：显式绑定 + 实时前台身份一致后才可写。
        assertTrue(isPoolWriteAllowed(TARGET, TARGET, OWN, HOME))
        assertFalse("其它应用不得写池", isPoolWriteAllowed(OTHER, TARGET, OWN, HOME))
        assertFalse("前缀相似包名不得写池", isPoolWriteAllowed("$TARGET.pro", TARGET, OWN, HOME))
        assertFalse("桌面不得写池", isPoolWriteAllowed("com.miui.home", "com.miui.home", OWN, HOME))
        assertFalse("自身窗口不得写池", isPoolWriteAllowed(OWN, OWN, OWN, HOME))
        assertFalse("空候选不得写池", isPoolWriteAllowed(null, TARGET, OWN, HOME))
    }

    @Test
    fun ordinaryAppSwitchKeepsBindingAndBlocksForeignWrites() {
        // S5④：普通应用切换（无显式绑定动作）不改变绑定。
        val binding = TargetBindingState(clock = { 1_000L })
        assertTrue("首次绑定目标", binding.bind(TARGET, 1_000L))
        assertEquals("绑定包名保持", TARGET, binding.boundPackage())

        // 用户切到其它应用：只改变实时前台，不得建立新绑定。
        assertFalse(isPoolWriteAllowed(OTHER, binding.boundPackage(), OWN, HOME))
        assertTrue("切回绑定目标才可写池", isPoolWriteAllowed(TARGET, binding.boundPackage(), OWN, HOME))
    }

    @Test
    fun explicitRebindEstablishesNewSessionBoundaryAndDropsOldQuestion() {
        // S5⑤：显式重绑定建立新会话边界，旧题内容不得串入新应用（可复现链路证据）。
        val binding = TargetBindingState(clock = { 1_000L })
        binding.bind("com.app.a", 1_000L)
        val epochA = binding.sessionEpoch()
        val previous = binding.boundPackage()

        val pool = ScreenContextPool()
        pool.startNewQuestion(windowKey = "com.app.a", atMillis = 1_000L)
        assertTrue("旧题已建立上下文", pool.hasQuestion())
        assertNotNull(pool.currentQuestion())

        // 显式重绑定到另一应用（唯一入口）。
        val changed = binding.bind("com.app.b", 2_000L)
        assertTrue("换到不同目标必须报告变化", changed)
        assertTrue("重绑定必须推进会话边界", binding.sessionEpoch() > epochA)
        assertTrue("必须建立新会话边界", requiresSessionBoundary(previous, binding.boundPackage()))

        // 会话边界的落地动作：清空累计题目上下文。
        pool.clear()
        assertFalse("旧题内容不得跨绑定残留", pool.hasQuestion())
        assertTrue("清空后不得再有题目快照", pool.currentQuestion() == null)

        // 旧目标不得继续写池，新目标可以。
        assertFalse(isPoolWriteAllowed("com.app.a", binding.boundPackage(), OWN, HOME))
        assertTrue(isPoolWriteAllowed("com.app.b", binding.boundPackage(), OWN, HOME))
    }

    @Test
    fun sameTargetRebindDoesNotDropSessionBoundary() {
        // 绑定同一目标不算「换应用」：不应无谓建立新会话边界（同题追问仍需上下文）。
        assertFalse(requiresSessionBoundary(TARGET, TARGET))
        assertTrue(requiresSessionBoundary(TARGET, null))
        assertTrue(requiresSessionBoundary(null, TARGET))
    }

    @Test
    fun onlyEligibleTargetsCanBeBound() {
        assertFalse("桌面不得作为绑定目标", canBindAsTarget("com.miui.home", OWN, HOME))
        assertFalse("自身不得作为绑定目标", canBindAsTarget(OWN, OWN, HOME))
        assertFalse("空包名不得作为绑定目标", canBindAsTarget(null, OWN, HOME))
        assertFalse("空白包名不得作为绑定目标", canBindAsTarget("   ", OWN, HOME))
        assertTrue(canBindAsTarget(TARGET, OWN, HOME))
    }
}