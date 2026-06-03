package `fun`.kirari.hanako.automation

import `fun`.kirari.hanako.data.AutomationActionRecord
import `fun`.kirari.hanako.data.AutomationActionType

data class AutomationResult(
    val thought: String,
    val action: AutomationActionRecord
)

internal fun bubbleLettersAction(text: String): AutomationActionRecord =
    AutomationActionRecord(
        type = AutomationActionType.SHOW_BUBBLE_LETTERS,
        text = text
    )

internal fun clipboardAction(text: String): AutomationActionRecord =
    AutomationActionRecord(
        type = AutomationActionType.SET_CLIPBOARD,
        text = text
    )

internal fun autoFillInputAction(text: String, hint: String): AutomationActionRecord =
    AutomationActionRecord(
        type = AutomationActionType.AUTO_FILL_INPUT,
        text = text,
        extra = hint
    )

internal fun autoClickButtonAction(text: String): AutomationActionRecord =
    AutomationActionRecord(
        type = AutomationActionType.AUTO_CLICK_BUTTON,
        text = text
    )

internal fun voiceReadAction(text: String): AutomationActionRecord =
    AutomationActionRecord(
        type = AutomationActionType.VOICE_READ,
        text = text
    )
