package `fun`.kirari.hanako.overlay

import android.content.Context
import android.graphics.Bitmap
import `fun`.kirari.hanako.automation.AutomationResult
import `fun`.kirari.hanako.automation.validateAutomationAction
import `fun`.kirari.hanako.data.AssistantPreset
import `fun`.kirari.hanako.data.AutomationActionRecord
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.LOCAL_OCR_PROVIDER_ID
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ProcessingEvent
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.resolveModelName
import `fun`.kirari.hanako.data.resolveModelProvider
import `fun`.kirari.hanako.data.saveToHistoryFile
import `fun`.kirari.hanako.accessibility.HanakoAccessibilityService
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.network.LlmEvent
import `fun`.kirari.hanako.network.ToolDef
import `fun`.kirari.hanako.network.ToolRegistry
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.screencontext.organizeOcrText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal class ProcessingPipeline(
    private val appContext: Context,
    private val unifiedClient: UnifiedLLMClient,
    private val localOcrManager: LocalOcrManager
) {
    private val tag = "HanakoPipeline"

    data class ResolvedModels(
        val assistant: AssistantPreset,
        val ocrProvider: ModelProviderConfig?,
        val ocrModel: String,
        val textProvider: ModelProviderConfig?,
        val textModel: String,
        val visionProvider: ModelProviderConfig?,
        val visionModel: String,
        val firstDeltaTimeoutMillis: Long,
        val route: ProcessingRoute,
        val usingLocalOcr: Boolean,
        val trustAllHttpsCertificates: Boolean
    )

    fun resolveModels(state: OverlayUiState): ResolvedModels {
        val assistant = state.settings.assistants.firstOrNull { it.id == state.settings.selectedAssistantId }
            ?: error("请先配置助手")
        return ResolvedModels(
            assistant = assistant,
            ocrProvider = state.settings.resolveModelProvider(ModelPurpose.OCR),
            ocrModel = state.settings.resolveModelName(ModelPurpose.OCR),
            textProvider = state.settings.resolveModelProvider(ModelPurpose.TEXT),
            textModel = state.settings.resolveModelName(ModelPurpose.TEXT),
            visionProvider = state.settings.resolveModelProvider(ModelPurpose.VISION),
            visionModel = state.settings.resolveModelName(ModelPurpose.VISION),
            firstDeltaTimeoutMillis = state.settings.automation.autoModeTimeoutSeconds.coerceAtLeast(1) * 1000L,
            route = state.settings.processingRoute,
            usingLocalOcr = state.settings.ocrModelSelection.providerId == LOCAL_OCR_PROVIDER_ID,
            trustAllHttpsCertificates = state.settings.trustAllHttpsCertificates
        )
    }

    fun buildModelSummary(model: String, providerName: String?): String {
        val trimmedModel = model.trim()
        val trimmedProvider = providerName?.trim().orEmpty()
        if (trimmedModel.isBlank()) return ""
        return if (trimmedProvider.isBlank()) trimmedModel else "$trimmedModel（$trimmedProvider）"
    }

    fun createBaseResult(
        models: ResolvedModels,
        bitmaps: List<Bitmap>,
        detail: String
    ): Triple<ProcessingResult, String, List<String>> {
        val historyId = java.util.UUID.randomUUID().toString()
        val screenshotPaths = bitmaps.mapIndexed { index, bitmap ->
            bitmap.saveToHistoryFile(appContext, "${historyId}_$index")
        }
        val baseResult = ProcessingResult(
            id = historyId,
            assistantName = models.assistant.name,
            route = models.route,
            status = ProcessingStatus.RUNNING,
            modelSummary = when (models.route) {
                ProcessingRoute.OCR_THEN_LLM -> buildModelSummary(models.textModel, models.textProvider?.name)
                ProcessingRoute.MULTIMODAL_DIRECT -> buildModelSummary(models.visionModel, models.visionProvider?.name)
                ProcessingRoute.ACCESSIBILITY_TEXT -> buildModelSummary(models.textModel, models.textProvider?.name)
            },
            detail = detail,
            screenshotPath = screenshotPaths.firstOrNull(),
            screenshotPaths = screenshotPaths,
            events = listOf(ProcessingEvent(title = "请求开始", detail = "已创建处理记录"))
        )
        return Triple(baseResult, historyId, screenshotPaths)
    }

    fun validateOcrThenLlmModels(models: ResolvedModels) {
        if ((!models.usingLocalOcr && (models.ocrProvider == null || models.ocrModel.isBlank())) ||
            models.textProvider == null || models.textModel.isBlank()
        ) {
            error("请先在模型设置中配置 OCR 和文本模型")
        }
    }

    fun validateVisionModels(models: ResolvedModels) {
        if (models.visionProvider == null || models.visionModel.isBlank()) {
            error("请先在模型设置中配置多模态模型")
        }
    }

    fun validateTextModels(models: ResolvedModels) {
        if (models.textProvider == null || models.textModel.isBlank()) {
            error("请先在模型设置中配置文本模型")
        }
    }

    suspend fun runLocalOcr(bitmap: Bitmap): String {
        return withContext(Dispatchers.Default) {
            // 与实时互动链路一致：先取结构化结果，再按坐标恢复阅读顺序并关联选项，
            // 而不是直接使用引擎返回顺序拼接的文本。
            organizeOcrText(localOcrManager.recognize(bitmap)).text
        }
    }

    private suspend fun collectTextStream(
        provider: ModelProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imagesBase64: List<String> = emptyList(),
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean,
        onDelta: (String) -> Unit
    ): String {
        val text = StringBuilder()
        unifiedClient.stream(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imagesBase64 = imagesBase64,
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = trustAllHttpsCertificates
        ).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> {
                    text.append(event.text)
                    onDelta(event.text)
                }
                is LlmEvent.Done -> {}
                else -> {}
            }
        }
        return text.toString()
    }

    private data class StreamResult(
        val thought: String,
        val toolCall: LlmEvent.ToolCall?
    )

    private suspend fun collectToolStream(
        provider: ModelProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imagesBase64: List<String> = emptyList(),
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean,
        onThoughtDelta: (String) -> Unit
    ): StreamResult {
        val thought = StringBuilder()
        var toolCall: LlmEvent.ToolCall? = null
        unifiedClient.stream(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imagesBase64 = imagesBase64,
            tools = ToolRegistry.AUTOMATION_TOOLS,
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = trustAllHttpsCertificates
        ).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> {
                    thought.append(event.text)
                    onThoughtDelta(event.text)
                }
                is LlmEvent.ToolCall -> {
                    toolCall = event
                }
                is LlmEvent.Done -> {}
            }
        }
        return StreamResult(thought.toString().trim(), toolCall)
    }

    private fun buildAutomationResult(streamResult: StreamResult): AutomationResult {
        val tc = streamResult.toolCall ?: error("自动模式未调用工具")
        return AutomationResult(
            thought = streamResult.thought,
            action = validateAutomationAction(
                tc.name,
                tc.arguments["text"]?.toString()?.trim('"').orEmpty(),
                tc.arguments
            )
        )
    }

    suspend fun streamOcrThenChat(
        models: ResolvedModels,
        bitmaps: List<Bitmap>,
        onOcrDelta: (String) -> Unit,
        onAnswerDelta: (String) -> Unit
    ): Pair<String, String> {
        AppDebugLogStore.i(tag, "streamOcrThenChat ocrModel=${models.ocrModel} textModel=${models.textModel} imageCount=${bitmaps.size}")
        val ocrTexts = mutableListOf<String>()
        bitmaps.forEach { bitmap ->
            val ocrText = if (models.usingLocalOcr) {
                runLocalOcr(bitmap)
            } else {
                collectTextStream(
                    provider = requireNotNull(models.ocrProvider),
                    model = models.ocrModel,
                    systemPrompt = models.assistant.ocrPrompt,
                    userPrompt = "请执行 OCR。",
                    imagesBase64 = listOf(bitmap.toBase64Jpeg()),
                    firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
                    trustAllHttpsCertificates = models.trustAllHttpsCertificates,
                    onDelta = {}
                )
            }
            ocrTexts.add(ocrText)
        }
        val combinedOcrText = ocrTexts.joinToString("\n\n---\n\n")
        onOcrDelta(combinedOcrText)
        
        val answer = collectTextStream(
            provider = requireNotNull(models.textProvider),
            model = models.textModel,
            systemPrompt = assistantPromptWithCopyMarker(models.assistant.textPrompt),
            userPrompt = "以下是 OCR 结果，请完成任务：\n$combinedOcrText",
            firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = models.trustAllHttpsCertificates,
            onDelta = onAnswerDelta
        )
        AppDebugLogStore.i(tag, "streamOcrThenChat success ocrLength=${combinedOcrText.length} answerLength=${answer.length}")
        return combinedOcrText to answer
    }

    suspend fun streamVisionDirect(
        models: ResolvedModels,
        bitmaps: List<Bitmap>,
        onAnswerDelta: (String) -> Unit
    ): String {
        AppDebugLogStore.i(tag, "streamVisionDirect visionModel=${models.visionModel} imageCount=${bitmaps.size}")
        val imagesBase64 = bitmaps.map { it.toBase64Jpeg() }
        val answer = collectTextStream(
            provider = requireNotNull(models.visionProvider),
            model = models.visionModel,
            systemPrompt = assistantPromptWithCopyMarker(models.assistant.visionPrompt),
            userPrompt = "请直接基于图片内容完成任务。",
            imagesBase64 = imagesBase64,
            firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = models.trustAllHttpsCertificates,
            onDelta = onAnswerDelta
        )
        AppDebugLogStore.i(tag, "streamVisionDirect success answerLength=${answer.length}")
        return answer
    }

    /**
     * 无障碍提取文本 → LLM 分析。无需截屏，直接从屏幕 UI 节点提取文本。
     */
    suspend fun streamAccessibilityThenChat(
        models: ResolvedModels,
        onTextDelta: (String) -> Unit,
        onAnswerDelta: (String) -> Unit
    ): Pair<String, String> {
        AppDebugLogStore.i(tag, "streamAccessibilityThenChat textModel=${models.textModel}")
        val extractedText = HanakoAccessibilityService.extractTextFromScreen()
        if (extractedText.isBlank()) {
            error("无障碍服务未获取到任何文本，请检查无障碍权限是否已开启")
        }
        onTextDelta(extractedText)

        val answer = collectTextStream(
            provider = requireNotNull(models.textProvider),
            model = models.textModel,
            systemPrompt = assistantPromptWithCopyMarker(models.assistant.textPrompt),
            userPrompt = "以下是从屏幕 UI 节点提取的文本，请完成任务：\n$extractedText",
            firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = models.trustAllHttpsCertificates,
            onDelta = onAnswerDelta
        )
        AppDebugLogStore.i(tag, "streamAccessibilityThenChat success textLength=${extractedText.length} answerLength=${answer.length}")
        return extractedText to answer
    }

    /**
     * 无障碍提取文本 → LLM 自动化。无需截屏。
     */
    suspend fun streamAccessibilityThenAutomation(
        models: ResolvedModels,
        onTextDelta: (String) -> Unit,
        onThoughtDelta: (String) -> Unit
    ): Pair<String, AutomationResult> {
        AppDebugLogStore.i(tag, "streamAccessibilityThenAutomation textModel=${models.textModel}")
        val extractedText = HanakoAccessibilityService.extractTextFromScreen()
        if (extractedText.isBlank()) {
            error("无障碍服务未获取到任何文本，请检查无障碍权限是否已开启")
        }
        onTextDelta(extractedText)

        val streamResult = collectToolStream(
            provider = requireNotNull(models.textProvider),
            model = models.textModel,
            systemPrompt = automationSystemPrompt(models.assistant.textPrompt),
            userPrompt = "以下是从屏幕 UI 节点提取的文本，请先输出思考过程，再通过一次工具调用给出自动模式动作：\n$extractedText",
            firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = models.trustAllHttpsCertificates,
            onThoughtDelta = onThoughtDelta
        )
        val result = buildAutomationResult(streamResult)
        AppDebugLogStore.i(tag, "streamAccessibilityThenAutomation success textLength=${extractedText.length} thoughtLength=${result.thought.length}")
        return extractedText to result
    }

    suspend fun streamOcrThenAutomation(
        models: ResolvedModels,
        bitmaps: List<Bitmap>,
        onOcrDelta: (String) -> Unit,
        onThoughtDelta: (String) -> Unit
    ): Pair<String, AutomationResult> {
        AppDebugLogStore.i(tag, "streamOcrThenAutomation ocrModel=${models.ocrModel} textModel=${models.textModel} imageCount=${bitmaps.size}")
        val ocrTexts = mutableListOf<String>()
        bitmaps.forEach { bitmap ->
            val ocrText = if (models.usingLocalOcr) {
                runLocalOcr(bitmap)
            } else {
                collectTextStream(
                    provider = requireNotNull(models.ocrProvider),
                    model = models.ocrModel,
                    systemPrompt = models.assistant.ocrPrompt,
                    userPrompt = "请执行 OCR。",
                    imagesBase64 = listOf(bitmap.toBase64Jpeg()),
                    firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
                    trustAllHttpsCertificates = models.trustAllHttpsCertificates,
                    onDelta = {}
                )
            }
            ocrTexts.add(ocrText)
        }
        val combinedOcrText = ocrTexts.joinToString("\n\n---\n\n")
        onOcrDelta(combinedOcrText)
        val streamResult = collectToolStream(
            provider = requireNotNull(models.textProvider),
            model = models.textModel,
            systemPrompt = automationSystemPrompt(models.assistant.textPrompt),
            userPrompt = "以下是 OCR 结果，请先输出思考过程，再通过一次工具调用给出自动模式动作：\n$combinedOcrText",
            firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = models.trustAllHttpsCertificates,
            onThoughtDelta = onThoughtDelta
        )
        val result = buildAutomationResult(streamResult)
        AppDebugLogStore.i(tag, "streamOcrThenAutomation success ocrLength=${combinedOcrText.length} thoughtLength=${result.thought.length} action=${result.action.type}")
        return combinedOcrText to result
    }

    suspend fun streamAutomationDirect(
        models: ResolvedModels,
        bitmaps: List<Bitmap>,
        onThoughtDelta: (String) -> Unit
    ): AutomationResult {
        AppDebugLogStore.i(tag, "streamAutomationDirect visionModel=${models.visionModel} imageCount=${bitmaps.size}")
        val imagesBase64 = bitmaps.map { it.toBase64Jpeg() }
        val streamResult = collectToolStream(
            provider = requireNotNull(models.visionProvider),
            model = models.visionModel,
            systemPrompt = automationSystemPrompt(models.assistant.visionPrompt),
            userPrompt = "请根据整张屏幕截图先输出思考过程，再通过一次工具调用给出自动模式动作。",
            imagesBase64 = imagesBase64,
            firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = models.trustAllHttpsCertificates,
            onThoughtDelta = onThoughtDelta
        )
        val result = buildAutomationResult(streamResult)
        AppDebugLogStore.i(tag, "streamAutomationDirect success thoughtLength=${result.thought.length} action=${result.action.type} actionText=${result.action.text}")
        return result
    }

    fun buildChatResult(
        base: ProcessingResult,
        models: ResolvedModels,
        ocrText: String,
        answer: String,
        historyId: String,
        screenshotPaths: List<String>
    ): ProcessingResult {
        val events = base.events.toMutableList()
        when (models.route) {
            ProcessingRoute.OCR_THEN_LLM -> events.add(ProcessingEvent(title = "OCR 完成", detail = "已提取 ${ocrText.length} 个字符"))
            ProcessingRoute.ACCESSIBILITY_TEXT -> events.add(ProcessingEvent(title = "无障碍提取完成", detail = "已提取 ${ocrText.length} 个字符"))
            else -> {}
        }
        events.add(ProcessingEvent(title = "答案完成", detail = "已生成 ${answer.length} 个字符"))
        return ProcessingResult(
            id = historyId,
            assistantName = models.assistant.name,
            route = models.route,
            status = ProcessingStatus.SUCCESS,
            modelSummary = when (models.route) {
                ProcessingRoute.OCR_THEN_LLM -> buildModelSummary(models.textModel, models.textProvider?.name)
                ProcessingRoute.MULTIMODAL_DIRECT -> buildModelSummary(models.visionModel, models.visionProvider?.name)
                ProcessingRoute.ACCESSIBILITY_TEXT -> buildModelSummary(models.textModel, models.textProvider?.name)
            },
            detail = "处理完成",
            extractedText = ocrText,
            answer = answer,
            screenshotPath = screenshotPaths.firstOrNull(),
            screenshotPaths = screenshotPaths,
            events = events,
            createdAtMillis = base.createdAtMillis
        )
    }

    fun buildAutomationResult(
        base: ProcessingResult,
        models: ResolvedModels,
        ocrText: String,
        automationResult: AutomationResult,
        historyId: String,
        screenshotPaths: List<String>
    ): Pair<AutomationActionRecord, ProcessingResult> {
        val events = base.events.toMutableList()
        when (models.route) {
            ProcessingRoute.OCR_THEN_LLM -> events.add(ProcessingEvent(title = "OCR 完成", detail = "已提取 ${ocrText.length} 个字符"))
            ProcessingRoute.ACCESSIBILITY_TEXT -> events.add(ProcessingEvent(title = "无障碍提取完成", detail = "已提取 ${ocrText.length} 个字符"))
            else -> {}
        }
        events.add(
            ProcessingEvent(
                title = "工具动作完成",
                detail = "${automationResult.action.type}: ${automationResult.action.text}"
            )
        )
        val result = ProcessingResult(
            id = historyId,
            assistantName = models.assistant.name,
            route = models.route,
            status = ProcessingStatus.SUCCESS,
            modelSummary = when (models.route) {
                ProcessingRoute.OCR_THEN_LLM -> buildModelSummary(models.textModel, models.textProvider?.name)
                ProcessingRoute.MULTIMODAL_DIRECT -> buildModelSummary(models.visionModel, models.visionProvider?.name)
                ProcessingRoute.ACCESSIBILITY_TEXT -> buildModelSummary(models.textModel, models.textProvider?.name)
            },
            detail = "自动处理完成",
            extractedText = ocrText,
            answer = "",
            automationThought = automationResult.thought,
            automationAction = automationResult.action,
            screenshotPath = screenshotPaths.firstOrNull(),
            screenshotPaths = screenshotPaths,
            events = events,
            createdAtMillis = base.createdAtMillis
        )
        return automationResult.action to result
    }
}

private fun assistantPromptWithCopyMarker(systemPrompt: String): String {
    val trimmed = systemPrompt.trim()
    if (trimmed.isBlank()) return trimmed
    return """
        你可以在回答中插入如下格式的可复制文本块：
        [copy:内容]
        其中"内容"会显示为一个小标签，点击复制图标后会写入同样的文本到剪贴板。
        对于问题的答案或用户需要填写到某个表单中的内容，你必须给出一键复制的标签。

        $trimmed
    """.trimIndent()
}

private fun automationSystemPrompt(userPrompt: String): String {
    val trimmed = userPrompt.trim()
    return """
        你当前处于自动答题模式。
        你必须先输出简短、清晰的思考过程，说明你识别到了什么题型以及为什么这样判断。
        思考过程结束后，你必须且只能调用一个工具，不能在工具调用后继续输出额外文本。

        可用工具说明：
        1. set_clipboard — 将答案写入剪贴板，适合填空题、简答题等需要粘贴的场景。
        2. show_bubble_letters — 在悬浮球上显示选项字母（A-D），适合选择题。text 只能是 1-4 个大写字母。
        3. auto_fill_input — 自动填写屏幕上的输入框。text 是要填的内容，hint 是输入框的提示文字（如"请输入答案"）。
        4. auto_click_button — 自动点击屏幕上的按钮。text 是按钮上显示的文字（如"提交"、"确定"）。
        5. voice_read — 用语音朗读答案。适合用户不方便看屏幕时使用。

        不允许调用多个工具，不允许省略工具调用。

        ${trimmed.ifBlank { "请根据截图内容判断题目类型，并选择最合适的自动动作。" }}
    """.trimIndent()
}

private fun Bitmap.toBase64Jpeg(quality: Int = 92): String {
    val output = java.io.ByteArrayOutputStream()
    compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)
    return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
}
