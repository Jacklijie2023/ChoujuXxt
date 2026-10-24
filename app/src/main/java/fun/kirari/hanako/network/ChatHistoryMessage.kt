package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ChatRole

/**
 * 送入模型请求体的「会话历史消息」（同题追问时的最近必要问答）。
 *
 * 仅承载 role 与内容：system 与当前用户消息之间的历史按顺序插入。
 * 是否携带由上层依据「当前题目上下文是否与上一次请求一致」决定，
 * 本层不做任何推断或裁剪（无历史时必须只发 system + 当前用户消息）。
 */
internal data class ChatHistoryMessage(val role: ChatRole, val content: String)
