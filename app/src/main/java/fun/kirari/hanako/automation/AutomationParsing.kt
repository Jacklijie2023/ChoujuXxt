package `fun`.kirari.hanako.automation

import `fun`.kirari.hanako.data.AutomationActionRecord

internal fun validateAutomationAction(
    name: String,
    text: String,
    arguments: Map<String, Any?> = emptyMap()
): AutomationActionRecord {
    val normalized = text.trim()
    require(normalized.isNotBlank()) { "自动模式工具参数不能为空" }
    return when (name) {
        "set_clipboard" -> clipboardAction(normalized)
        "show_bubble_letters" -> {
            require(Regex("^[A-Z]{1,4}$").matches(normalized)) {
                "悬浮球字母必须是 1 到 4 个大写英文字母"
            }
            bubbleLettersAction(normalized)
        }
        "auto_fill_input" -> {
            val hint = arguments["hint"]?.toString()?.trim('"') ?: ""
            autoFillInputAction(normalized, hint)
        }
        "auto_click_button" -> autoClickButtonAction(normalized)
        "voice_read" -> voiceReadAction(normalized)

        else -> error("未知自动模式工具：$name")
    }
}
