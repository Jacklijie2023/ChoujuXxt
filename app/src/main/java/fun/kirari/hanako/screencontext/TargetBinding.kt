package `fun`.kirari.hanako.screencontext

/**
 * 目标应用「会话绑定」状态机（纯内存态，无 Android 依赖，便于本地单元测试）。
 *
 * 语义要点（对应 S1/S2）：
 * - **绑定**是「已确认要针对哪个应用建立题目上下文」的唯一凭据；**仅选择应用不等于已经建立绑定**。
 * - 绑定**只**由三处触发：`OverlayService.showMiniChat()`、用户主动发送提问前、
 *   设置页显式重绑定按钮。普通应用切换、桌面事件、Hanako 自身窗口事件、旧无障碍事件、
 *   后台探测一律**不得**调用 [bind]。
 * - 每次 [bind] / [clear] 都推进 [sessionEpoch]：作为「会话边界」的可复现证据，
 *   调用方据此清空累计题目上下文并让聊天链路丢弃上一题上下文，防止旧题串入新应用。
 *
 * 线程安全：字段均为 `@Volatile`；绑定触发点都在主线程，读取发生在无障碍 / 采集协程。
 */
internal class TargetBindingState(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var bound: String? = null

    @Volatile
    private var boundAt: Long = 0L

    @Volatile
    private var epoch: Long = 0L

    /** 当前绑定的目标应用包名；未建立绑定返回 null。 */
    fun boundPackage(): String? = bound

    /** 建立绑定的时间（毫秒）；未绑定为 0。 */
    fun boundAtMillis(): Long = boundAt

    /** 会话边界计数：每次 [bind] / [clear] 递增，用于断言「确实建立了新会话边界」。 */
    fun sessionEpoch(): Long = epoch

    /** 是否已建立绑定。 */
    fun isBound(): Boolean = bound != null

    /**
     * 建立或重建绑定（唯一入口）。
     *
     * @return true 表示目标确实发生了变化（调用方据此建立新会话边界）；false 表示重复绑定同一目标。
     */
    fun bind(packageName: String, atMillis: Long = clock()): Boolean {
        val changed = bound != packageName
        bound = packageName
        boundAt = atMillis
        epoch += 1
        return changed
    }

    /** 解除绑定（例如关闭实时互动）；若原本未绑定则不推进会话边界。 */
    fun clear() {
        if (bound != null) {
            bound = null
            boundAt = 0L
            epoch += 1
        }
    }
}

/**
 * 「是否需要建立新会话边界」的纯判定。
 *
 * 绑定目标发生变化（换到另一个应用、或解除绑定）时必须建立新会话边界：
 * 累计题目上下文必须清空，聊天链路必须丢弃上一题上下文，旧题内容不得串入新应用。
 * 绑定同一应用时不重复建立边界（避免无谓地丢弃同题追问所需的上下文）。
 */
internal fun requiresSessionBoundary(previousBound: String?, newBound: String?): Boolean =
    previousBound != newBound
