package `fun`.kirari.hanako.chat

import `fun`.kirari.hanako.evidence.ContextImageRef
import `fun`.kirari.hanako.screencontext.ScreenContextSource
import `fun`.kirari.hanako.screencontext.ScreenReadReport

/**
 * 一次发送请求的「屏幕上下文解析结果」（由聊天链路向实时屏幕感知层请求得到）。
 *
 * 三态严格分离，避免把「没请求」「请求了但失败」「请求到可用文本」混为一谈：
 * - [NotRequested]：**完全绕过屏幕读取**的普通聊天（实时模式未开启 / 实时互动服务未运行 / 未接入注入器），
 *   用户消息原样发送，绝不因缺少屏幕上下文而失败；
 * - [Missing]：已请求但上下文不可用（无障碍未开启 / 截图会话缺失 / 未取到新鲜画面 / OCR 失败等），
 *   携带面向用户的明确原因；调用方必须据此**终止本此请求**并以失败提示回应，不得静默降级为普通聊天；
 * - [Available]：取到了可注入当前请求的屏幕文本，连同**同一次读取**的诊断报告一起返回。
 */
internal sealed interface ScreenContextResult {

    /** 完全绕过屏幕读取的普通聊天。 */
    data object NotRequested : ScreenContextResult

    /**
     * 已请求但上下文不可用：[reason] 是面向用户的失败原因
     * （会作为失败提示消息展示，不参与模型请求）。
     */
    data class Missing(val reason: String) : ScreenContextResult

    /**
     * 可用：本次请求注入 [text]。
     *
     * [traceId] / [source] / [readReport] 必须来自**同一次**屏幕读取结果，
     * 保证「读取诊断」与「真正注入模型的文本」严格对应，不出现两次读取拼接。
     *
     * R8.7 P3：以下证据字段由**同一份组装结果**派生，随结果一起交给发送侧，
     * 使「注入日志」与「真正传给 `streamSource.stream()` 的 userPrompt」能用同一个 traceId 对齐：
     * - [includedSegmentIds]：实际贡献了 [text] 的池内片段 id（不是池内片段总数）；
     * - [hasSegmentBreak]：[text] 中是否含分段标识（用于判断累计段与当前屏是否被分开）；
     * - [liveIncluded] / [liveVerified]：本次新鲜视口是否被并入、是否被采信为当前屏幕；
     * - [liveViewportId]：本次视口标识（**与 traceId 不同**，后者标识这一次用户请求）。
     */
    data class Available(
        val text: String,
        val traceId: String,
        val source: ScreenContextSource,
        val readReport: ScreenReadReport,
        val includedSegmentIds: List<String> = emptyList(),
        val hasSegmentBreak: Boolean = false,
        val liveIncluded: Boolean = false,
        val liveVerified: Boolean = true,
        val liveViewportId: String? = null,
        /** 权威题目上下文的问题标识（来自证据库组装结果）。 */
        val questionId: String? = null,
        /** 上下文版本号（同一题目每重建一次 +1）。 */
        val contextVersion: Int = 0,
        /** 上下文状态名：[`fun`.kirari.hanako.evidence.QuestionContextStatus] 的 name（READY/PARTIAL/...）。 */
        val contextStatus: String? = null,
        /** 与本题关联的图像证据引用；真正是否发送由发送侧按模型能力决定。 */
        val contextImages: List<ContextImageRef> = emptyList(),
        /** 明确缺失 / 无法确认的内容说明。 */
        val contextMissing: List<String> = emptyList()
    ) : ScreenContextResult
}
