package `fun`.kirari.hanako.overlay

import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.resolveModelName

/**
 * 快速命令处理器
 * 支持 /clear, /model, /prompt, /history, /help 命令
 */
internal object QuickCommands {

    data class CommandResult(
        val handled: Boolean,
        val message: String = "",
        val action: CommandAction? = null
    )

    sealed class CommandAction {
        data class SwitchModel(val purpose: ModelPurpose, val modelQuery: String) : CommandAction()
        data class SetPrompt(val prompt: String) : CommandAction()
        object ClearHistory : CommandAction()
        object ShowHistory : CommandAction()
        object ShowHelp : CommandAction()
        data class OpenWebView(val url: String) : CommandAction()
        data class SearchWeb(val query: String) : CommandAction()
    }

    /**
     * 处理用户输入，判断是否为命令
     * @return 如果是命令返回 CommandResult，否则返回 null
     */
    fun process(input: String, settings: AppSettings): CommandResult? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null

        val parts = trimmed.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val args = parts.getOrNull(1)?.trim() ?: ""

        return when (command) {
            "/clear" -> CommandResult(
                handled = true,
                message = "历史记录已清空",
                action = CommandAction.ClearHistory
            )

            "/model" -> handleModelCommand(args, settings)

            "/prompt" -> {
                if (args.isBlank()) {
                    CommandResult(handled = true, message = "当前系统提示词:\n${getCurrentPrompt(settings)}")
                } else {
                    CommandResult(
                        handled = true,
                        message = "系统提示词已更新",
                        action = CommandAction.SetPrompt(args)
                    )
                }
            }

            "/history" -> CommandResult(
                handled = true,
                message = formatHistory(settings.history.take(5)),
                action = CommandAction.ShowHistory
            )

            "/help" -> CommandResult(
                handled = true,
                message = buildHelpMessage(),
                action = CommandAction.ShowHelp
            )

            "/web" -> {
                if (args.isBlank()) {
                    CommandResult(handled = true, message = "用法: /web <网址或搜索内容>")
                } else {
                    CommandResult(
                        handled = true,
                        message = "正在打开: $args",
                        action = if (args.startsWith("http")) {
                            CommandAction.OpenWebView(args)
                        } else {
                            CommandAction.SearchWeb(args)
                        }
                    )
                }
            }

            "/stream" -> CommandResult(
                handled = true,
                message = "流式输出已切换"
            )

            else -> CommandResult(
                handled = true,
                message = "未知命令: $command\n输入 /help 查看可用命令"
            )
        }
    }

    private fun handleModelCommand(args: String, settings: AppSettings): CommandResult {
        if (args.isBlank()) {
            val textModel = settings.resolveModelName(ModelPurpose.TEXT)
            val visionModel = settings.resolveModelName(ModelPurpose.VISION)
            val ocrModel = settings.resolveModelName(ModelPurpose.OCR)
            return CommandResult(
                handled = true,
                message = "当前模型:\n文本: $textModel\n视觉: $visionModel\nOCR: $ocrModel\n\n用法: /model <模型名>"
            )
        }
        return CommandResult(
            handled = true,
            message = "正在切换文本模型为: $args",
            action = CommandAction.SwitchModel(ModelPurpose.TEXT, args)
        )
    }

    private fun getCurrentPrompt(settings: AppSettings): String {
        val assistant = settings.assistants.firstOrNull { it.id == settings.selectedAssistantId }
        return assistant?.textPrompt ?: "未设置"
    }

    private fun formatHistory(history: List<ProcessingResult>): String {
        if (history.isEmpty()) return "暂无历史记录"
        return history.mapIndexed { index, result ->
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(result.createdAtMillis))
            "${index + 1}. [$time] ${result.assistantName}: ${result.answer.take(30)}..."
        }.joinToString("\n")
    }

    private fun buildHelpMessage(): String = """
        可用命令:
        /clear — 清空历史记录
        /model — 查看/切换模型
        /prompt — 查看/设置系统提示词
        /history — 查看最近处理记录
        /web <内容> — 打开网页或搜索
        /stream — 切换流式输出
        /help — 显示此帮助
    """.trimIndent()
}
