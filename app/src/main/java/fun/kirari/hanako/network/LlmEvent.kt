package `fun`.kirari.hanako.network

import kotlinx.serialization.json.JsonObject

internal sealed class LlmEvent {
    data class TextDelta(val text: String) : LlmEvent()
    data class ToolCall(val name: String, val arguments: JsonObject, val id: String? = null) : LlmEvent()
    data object Done : LlmEvent()
}
