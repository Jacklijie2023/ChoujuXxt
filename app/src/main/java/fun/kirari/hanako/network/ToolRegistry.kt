package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ProviderKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ToolParam(
    val name: String,
    val type: String,
    val description: String,
    val pattern: String? = null
)

internal data class ToolDef(
    val name: String,
    val description: String,
    val params: List<ToolParam>
)

internal object ToolRegistry {

    val AUTOMATION_TOOLS = listOf(
        ToolDef(
            name = "set_clipboard",
            description = "将最终答案写入剪贴板",
            params = listOf(ToolParam("text", "string", "最终可直接粘贴的答案"))
        ),
        ToolDef(
            name = "show_bubble_letters",
            description = "在悬浮球上展示选项字母",
            params = listOf(ToolParam("text", "string", "1到4个大写英文字母", pattern = "^[A-Z]{1,4}$"))
        ),
        ToolDef(
            name = "auto_fill_input",
            description = "自动填写屏幕上的输入框",
            params = listOf(
                ToolParam("text", "string", "要填入输入框的内容"),
                ToolParam("hint", "string", "输入框的提示文字或标签，用于定位目标输入框")
            )
        ),
        ToolDef(
            name = "auto_click_button",
            description = "自动点击屏幕上的按钮",
            params = listOf(ToolParam("text", "string", "按钮上显示的文字，用于定位目标按钮"))
        ),
        ToolDef(
            name = "voice_read",
            description = "用语音朗读答案内容",
            params = listOf(ToolParam("text", "string", "要朗读的文本内容"))
        )
    )

    fun formatForProvider(tools: List<ToolDef>, kind: ProviderKind): JsonElement = when (kind) {
        ProviderKind.OPENAI_COMPATIBLE -> formatChatCompletions(tools)
        ProviderKind.OPENAI_RESPONSES -> formatResponses(tools)
        ProviderKind.ANTHROPIC -> formatAnthropic(tools)
        ProviderKind.ANTHROPIC_MESSAGES -> formatAnthropic(tools)
        ProviderKind.GOOGLE -> formatGoogle(tools)
    }

    private fun formatChatCompletions(tools: List<ToolDef>): JsonArray = buildJsonArray {
        for (tool in tools) {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            for (p in tool.params) {
                                put(p.name, buildJsonObject {
                                    put("type", p.type)
                                    put("description", p.description)
                                    p.pattern?.let { put("pattern", it) }
                                })
                            }
                        })
                        put("required", buildJsonArray {
                            for (p in tool.params) add(JsonPrimitive(p.name))
                        })
                        put("additionalProperties", false)
                    })
                })
            })
        }
    }

    private fun formatResponses(tools: List<ToolDef>): JsonArray = buildJsonArray {
        for (tool in tools) {
            add(buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        for (p in tool.params) {
                            put(p.name, buildJsonObject {
                                put("type", p.type)
                                put("description", p.description)
                                p.pattern?.let { put("pattern", it) }
                            })
                        }
                    })
                    put("required", buildJsonArray {
                        for (p in tool.params) add(JsonPrimitive(p.name))
                    })
                    put("additionalProperties", false)
                })
            })
        }
    }

    private fun formatAnthropic(tools: List<ToolDef>): JsonArray = buildJsonArray {
        for (tool in tools) {
            add(buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        for (p in tool.params) {
                            put(p.name, buildJsonObject {
                                put("type", p.type)
                                put("description", p.description)
                                p.pattern?.let { put("pattern", it) }
                            })
                        }
                    })
                    put("required", buildJsonArray {
                        for (p in tool.params) add(JsonPrimitive(p.name))
                    })
                })
            })
        }
    }

    private fun formatGoogle(tools: List<ToolDef>): JsonObject = buildJsonObject {
        put("functionDeclarations", buildJsonArray {
            for (tool in tools) {
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            for (p in tool.params) {
                                put(p.name, buildJsonObject {
                                    put("type", "STRING")
                                    put("description", p.description)
                                    p.pattern?.let { put("pattern", it) }
                                })
                            }
                        })
                        put("required", buildJsonArray {
                            for (p in tool.params) add(JsonPrimitive(p.name))
                        })
                    })
                })
            }
        })
    }
}
