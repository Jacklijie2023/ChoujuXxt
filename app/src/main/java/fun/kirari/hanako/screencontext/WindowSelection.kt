package `fun`.kirari.hanako.screencontext

import kotlinx.serialization.Serializable

/**
 * 无障碍窗口候选（去 Android 依赖后的纯数据描述，便于本地单元测试）。
 */
data class WindowCandidate(
    val packageName: String?,
    val type: Int,
    val layer: Int,
    val isActive: Boolean,
    val isFocused: Boolean
)

/**
 * 判断「活动窗口快速返回路径」是否成立。
 *
 * 包名必须非空且不等于本应用。之后的判定分两种情况（**这就是「三态分离」的核心**）：
 * - **窗口列表可用**（已获 `flagRetrieveInteractiveWindows` / `getWindows()` 非空）时：
 *   必须能从窗口列表确认其类型恰为 [applicationType]（应用内容窗口），否则即使包名有效
 *   也回退到候选列表过滤，绝不伪造读取成功；
 * - **窗口列表不可用**（未获授权 / `getWindows()` 返回空）时：无法用窗口类型字段佐证，此时
 *   要求「事件来源包名与活动窗口包名一致」——即系统已在事件里明确告知该事件属于该第三方
 *   应用——作为替代证据。**不能**因为「窗口列表为空」或「节点树读不到文字」就直接否定
 *   真实的第三方应用窗口。
 */
internal fun isActiveWindowAcceptable(
    packageName: String?,
    matchedType: Int?,
    ownPackage: String,
    applicationType: Int,
    windowListAvailable: Boolean = true,
    eventPackage: String? = null
): Boolean {
    if (packageName == null || packageName == ownPackage) return false
    if (windowListAvailable) return matchedType == applicationType
    return eventPackage != null && eventPackage == packageName
}

/**
 * 目标应用绑定模式（纯数据，无 Android 依赖，便于本地单元测试）。
 *
 * - [AUTO]：默认模式。首次由用户**主动唤醒**（悬浮球 / 迷你窗）时，按**实时确认的前台应用**自动建立绑定。
 *   普通应用切换不会改变已建立的绑定。
 * - [RESTRICTED]：可选手动限制模式。用户在设置页**显式选择**一个目标应用后，
 *   只有该应用位于实时前台且已建立绑定时才允许写入题目上下文。
 *
 * 注：本枚举需随 [fun.kirari.hanako.data.AppSettings] 持久化，故标注 `@Serializable` 且为公开可见
 * （供 `data` 包中的设置模型直接持有）；判定逻辑本身仍保持无 Android 依赖、可本地单测。
 */
@Serializable
enum class TargetAppMode {
    AUTO,
    RESTRICTED
}

/**
 * 目标应用身份判定（纯函数）：某个包名是否**有资格**成为题目上下文的绑定目标。
 *
 * 规则（三条都必须满足）：
 * - 包名非空 —— 空包名窗口（未提供身份的窗口）一律不作数；
 * - 不是 Hanako 自身 —— 不得把自己的悬浮球 / 迷你窗 / 聊天界面当作题目来源；
 * - 不是系统桌面（HOME 类应用）—— 桌面不是刷题应用，其窗口事件不得建立或覆盖题目上下文。
 *
 * 注意：本函数**只**回答「是否有资格绑定」，不回答「当前是否已建立绑定」也不回答「是否读到了文字」。
 * 「已建立绑定」由 [isPoolWriteAllowed] / [canEstablishBinding] 依据显式绑定状态回答。
 */
internal fun canBindAsTarget(
    packageName: String?,
    ownPackage: String,
    homePackages: Set<String>
): Boolean {
    if (packageName.isNullOrBlank()) return false
    if (packageName == ownPackage) return false
    return packageName !in homePackages
}

/**
 * 目标应用身份判定（窗口候选重载）：除包名规则外，还要求窗口类型恰为应用内容窗口。
 */
internal fun canBindAsTarget(
    candidate: WindowCandidate,
    ownPackage: String,
    homePackages: Set<String>,
    applicationType: Int
): Boolean = candidate.type == applicationType &&
    canBindAsTarget(candidate.packageName, ownPackage, homePackages)

/**
 * **累计题目上下文写入判定（唯一入口，模式无关）** —— 对应 S1。
 *
 * 五个条件**全部**满足才允许写入累计内容池：
 * 1. [candidatePackage] 非空；
 * 2. **[boundPackage] 非空** —— 尚未建立会话绑定（`boundPackage == null`）时，
 *    AUTO 与 RESTRICTED 模式**都不得**写入。**仅选择应用不等于已经建立会话绑定**；
 * 3. `candidatePackage == boundPackage` —— 只有身份与绑定目标完全一致的窗口才可写；
 * 4. 候选自身有资格绑定（[canBindAsTarget]）—— 非自身 / 非桌面；
 * 5. 由调用方另行保证的「实时前台确认」（本函数只做身份判定）。
 *
 * 任一条件不满足即返回 false，池内容保持原样。
 */
internal fun isPoolWriteAllowed(
    candidatePackage: String?,
    boundPackage: String?,
    ownPackage: String,
    homePackages: Set<String>
): Boolean {
    if (candidatePackage.isNullOrBlank()) return false
    // S1 核心：未建立绑定一律不得写池（模式无关）。
    if (boundPackage.isNullOrBlank()) return false
    if (candidatePackage != boundPackage) return false
    return canBindAsTarget(candidatePackage, ownPackage, homePackages)
}

/**
 * **绑定建立判定** —— 对应 S2。回答「此刻是否允许（重新）建立会话绑定」。
 *
 * - 首先要求 [liveForeground] 是有资格绑定的目标（[canBindAsTarget]）；
 * - [TargetAppMode.AUTO]：有资格即可建立（首次主动唤醒时按实时前台自动绑定）；
 * - [TargetAppMode.RESTRICTED]：**手动选择 ≠ 已激活** —— 仅当实时前台恰为
 *   [manualPackage] 时才允许建立绑定。
 *
 * 本函数不做副作用；调用方仅在三个触发点调用它：`OverlayService.showMiniChat()`、
 * 用户主动发送提问前、设置页显式重绑定按钮。
 */
internal fun canEstablishBinding(
    liveForeground: String?,
    mode: TargetAppMode,
    manualPackage: String?,
    ownPackage: String,
    homePackages: Set<String>
): Boolean {
    if (!canBindAsTarget(liveForeground, ownPackage, homePackages)) return false
    return when (mode) {
        TargetAppMode.AUTO -> true
        TargetAppMode.RESTRICTED -> liveForeground == manualPackage?.trim()
    }
}

/**
 * 由「无障碍事件来源包名」解析目标第三方应用的包名（三态分离的第 ① 态：目标应用是否存在）。
 *
 * 与「本次是否读到了文字」（第 ② 态）**完全解耦**：即使节点树返回空文本、窗口提取失败，
 * 只要系统分发的事件来源包名非空且不是本应用自身，就足以确认当前目标应用身份。
 * 包名为空 / 本应用悬浮窗 → 返回 null（保留安全门控，绝不把自身窗口或未知窗口当作题目来源）。
 */
internal fun resolveEventTargetPackage(eventPackage: String?, ownPackage: String): String? =
    eventPackage?.takeIf { it.isNotBlank() && it != ownPackage }

/**
 * 受控「画面变化探测回退」的运行期门控（纯函数）。
 *
 * 返回 null 表示**允许**探测；返回非空字符串表示**必须跳过**，且该字符串即为可读的阻断原因
 * （用于诊断，能让真机验收一眼看出「探测任务在跑但被哪一条门控挡住」）。
 *
 * 只有「实时模式有效、屏幕上下文服务活动、捕获会话可用、无障碍服务已连接、
 * **目标应用确认位于前台**、且采集器允许探测（限频 + 最近无目标滚动事件）」全部满足时才允许，
 * 任一不满足都保留安全门控 —— 但**必须**给出明确原因，不能统一含糊掉。
 */
internal fun probeSkipReason(
    realtimeEnabled: Boolean,
    managerActive: Boolean,
    captureSessionAvailable: Boolean,
    a11yConnected: Boolean,
    targetForegroundConfirmed: Boolean,
    probeAllowedByCollector: Boolean
): String? = when {
    !realtimeEnabled -> "实时模式未启用"
    !managerActive -> "屏幕上下文管理器未激活"
    !captureSessionAvailable -> "截图会话不可用（无法执行受控画面探测）"
    !a11yConnected -> "无障碍服务未连接"
    !targetForegroundConfirmed -> "无法确认目标应用位于前台（保留安全门控，不启用画面探测）"
    !probeAllowedByCollector -> "当前无需画面探测（探测限频未到，或最近收到过目标应用滚动事件）"
    else -> null
}

/**
 * 从无障碍窗口候选中选出「应读取内容的第三方应用窗口」的下标，找不到返回 null。
 *
 * 规则：
 * - packageName 必须非空且不等于 ownPackage：null 包名、本应用自身窗口一律排除，
 *   防止把自己的悬浮窗/聊天输入框当作题目。
 * - 仅 [applicationType] 类型的窗口参与竞争（排除输入法、系统栏等非内容窗口）。
 * - 优先活动/焦点窗口，否则取层级最低（最靠底层）的基窗，不固定挑选第一个。
 */
internal fun selectTargetWindowIndex(
    candidates: List<WindowCandidate>,
    ownPackage: String,
    applicationType: Int
): Int? {
    val eligible = candidates.indices.filter { index ->
        val c = candidates[index]
        c.packageName != null &&
            c.packageName != ownPackage &&
            c.type == applicationType
    }
    if (eligible.isEmpty()) return null
    eligible.firstOrNull { candidates[it].isActive || candidates[it].isFocused }?.let { return it }
    return eligible.minByOrNull { candidates[it].layer }
}