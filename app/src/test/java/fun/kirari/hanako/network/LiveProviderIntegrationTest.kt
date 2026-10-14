package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.data.modelsRequestUrl
import `fun`.kirari.hanako.data.resolvedRequestUrl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * P1 真实服务商联调验证入口（env-gated）。
 *
 * 设计要点：
 * - 凭据仅从本机环境变量读取（[System.getenv]），**绝不打印 Key 值**；
 *   未配置时用 [assumeTrue] 优雅 skip（报告为「未验证」），不会让全量单元测试变红。
 * - 复用项目真实网络栈：模型列表走 [ProviderModelsApi]，聊天 SSE 走 [UnifiedLLMClient]，
 *   不另写一套网络层。
 * - 证据写入 `%TEMP%/hanako_live_evidence.txt`，以便在 Gradle 默认屏蔽 stdout 时仍可取数。
 *
 * 环境变量（缺省值见各 target 定义）：
 * - HANAKO_LIVE_ANTHROPIC_URL / HANAKO_LIVE_ANTHROPIC_KEY / HANAKO_LIVE_ANTHROPIC_MODEL
 * - HANAKO_LIVE_DEEPSEEK_URL  / HANAKO_LIVE_DEEPSEEK_KEY  / HANAKO_LIVE_DEEPSEEK_MODEL
 * - HANAKO_LIVE_SEEKAI_URL    / HANAKO_LIVE_SEEKAI_KEY    / HANAKO_LIVE_SEEKAI_MODEL
 * - HANAKO_LIVE_AGENTROUTER_URL / HANAKO_LIVE_AGENTROUTER_KEY / HANAKO_LIVE_AGENTROUTER_MODEL
 * - HANAKO_LIVE_JUSTWOKER_URL / HANAKO_LIVE_JUSTWOKER_KEY / HANAKO_LIVE_JUSTWOKER_MODEL
 */
class LiveProviderIntegrationTest {

    private data class LiveTarget(
        val label: String,
        val kind: ProviderKind,
        val urlEnv: String,
        val keyEnv: String,
        val modelEnv: String,
        val defaultUrl: String,
        val defaultModel: String?,
        val forcedUrl: String? = null
    )

    private val anthropic = LiveTarget(
        label = "官方 Anthropic",
        kind = ProviderKind.ANTHROPIC,
        urlEnv = "HANAKO_LIVE_ANTHROPIC_URL",
        keyEnv = "HANAKO_LIVE_ANTHROPIC_KEY",
        modelEnv = "HANAKO_LIVE_ANTHROPIC_MODEL",
        defaultUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-opus-4-6"
    )

    private val deepseekV1 = LiveTarget(
        label = "DeepSeek（项目预设 /v1）",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        urlEnv = "HANAKO_LIVE_DEEPSEEK_URL",
        keyEnv = "HANAKO_LIVE_DEEPSEEK_KEY",
        modelEnv = "HANAKO_LIVE_DEEPSEEK_MODEL",
        defaultUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-flash"
    )

    /** 对照：官方根地址（不带 /v1），使用同一 Key。 */
    private val deepseekRoot = deepseekV1.copy(
        label = "DeepSeek（官方根地址，对照）",
        forcedUrl = "https://api.deepseek.com"
    )

    private val seekai = LiveTarget(
        label = "SeekAI",
        kind = ProviderKind.ANTHROPIC_MESSAGES,
        urlEnv = "HANAKO_LIVE_SEEKAI_URL",
        keyEnv = "HANAKO_LIVE_SEEKAI_KEY",
        modelEnv = "HANAKO_LIVE_SEEKAI_MODEL",
        defaultUrl = "",
        defaultModel = null
    )

    private val agentRouter = LiveTarget(
        label = "AgentRouter",
        kind = ProviderKind.ANTHROPIC_MESSAGES,
        urlEnv = "HANAKO_LIVE_AGENTROUTER_URL",
        keyEnv = "HANAKO_LIVE_AGENTROUTER_KEY",
        modelEnv = "HANAKO_LIVE_AGENTROUTER_MODEL",
        defaultUrl = "",
        defaultModel = null
    )

    private val justWoker = LiveTarget(
        label = "JustWoker",
        kind = ProviderKind.ANTHROPIC_MESSAGES,
        urlEnv = "HANAKO_LIVE_JUSTWOKER_URL",
        keyEnv = "HANAKO_LIVE_JUSTWOKER_KEY",
        modelEnv = "HANAKO_LIVE_JUSTWOKER_MODEL",
        defaultUrl = "",
        defaultModel = null
    )

    // ---------- 证据记录 ----------

    private fun log(line: String) {
        println("LIVE: $line")
        runCatching { evidenceFile.appendText(line + "\n") }
    }

    /** 抹除可能出现在异常信息中的凭据，确保日志/报告中不出现 Key 值。 */
    private fun redact(text: String, secret: String): String =
        if (secret.isNotBlank()) text.replace(secret, "***") else text

    private fun describe(t: Throwable, secret: String): String {
        val raw = t.message.orEmpty()
        val status = STATUS_REGEX.find(raw)?.groupValues?.get(1)
        val type = when (t) {
            is java.net.UnknownHostException -> "UnknownHost"
            is java.net.ConnectException -> "ConnectException"
            is java.net.SocketTimeoutException -> "SocketTimeout"
            is javax.net.ssl.SSLException -> "SSLException"
            is IllegalStateException -> "IllegalStateException"
            else -> t::class.java.simpleName
        }
        val snippet = redact(raw.replace(Regex("\\s+"), " ").take(240), secret)
        return if (status != null) "HTTP=$status type=$type msg=$snippet" else "type=$type msg=$snippet"
    }

    // ---------- 步骤 ----------

    private data class ModelsOutcome(val ok: Boolean, val evidence: String, val ids: List<String>)

    private fun runModelsStep(provider: ModelProviderConfig, secret: String): ModelsOutcome {
        val url = provider.modelsRequestUrl()
        return try {
            val models = runBlocking { withTimeout(30_000) { ProviderModelsApi().listModels(provider) } }
            val ids = models.map { it.id }
            ModelsOutcome(
                ok = true,
                evidence = "MODELS ok HTTP=2xx count=${ids.size} sample=${ids.take(3)}",
                ids = ids
            )
        } catch (t: Throwable) {
            ModelsOutcome(ok = false, evidence = "MODELS fail ${describe(t, secret)}", ids = emptyList())
        }
    }

    private fun runChatStep(provider: ModelProviderConfig, model: String, secret: String): String {
        val url = provider.resolvedRequestUrl()
        val events = mutableListOf<LlmEvent>()
        var failure: Throwable? = null
        try {
            runBlocking {
                withTimeout(60_000) {
                    UnifiedLLMClient().stream(
                        provider = provider,
                        model = model,
                        systemPrompt = "You are a concise assistant.",
                        userPrompt = "Reply with exactly: pong",
                        firstDeltaTimeoutMillis = 20_000L
                    ).collect { events += it }
                }
            }
        } catch (t: Throwable) {
            failure = t
        }
        val textDeltas = events.filterIsInstance<LlmEvent.TextDelta>()
        val toolCalls = events.filterIsInstance<LlmEvent.ToolCall>()
        val done = events.lastOrNull() is LlmEvent.Done
        val builder = StringBuilder("CHAT_URL=$url ")
        builder.append(if (failure == null) "outcome=completed" else "outcome=failed ${describe(failure!!, secret)}")
        builder.append(" firstDelta=").append(if (textDeltas.isEmpty()) "none" else "yes(len=${textDeltas.first().text.length})")
        builder.append(" textDeltas=").append(textDeltas.size)
        builder.append(" toolCalls=").append(toolCalls.size)
        builder.append(" done=").append(done)
        return builder.toString()
    }

    private fun runTarget(target: LiveTarget) {
        val key = System.getenv(target.keyEnv)?.trim().orEmpty()
        assumeTrue("${target.label}：未配置 ${target.keyEnv}（本轮标记为「未验证」）", key.isNotBlank())
        val url = target.forcedUrl
            ?: System.getenv(target.urlEnv)?.trim().takeUnless { it.isNullOrBlank() }
            ?: target.defaultUrl
        assumeTrue("${target.label}：未配置 ${target.urlEnv} 且无缺省地址（本轮标记为「未验证」）", url.isNotBlank())

        val provider = ModelProviderConfig(
            name = target.label,
            kind = target.kind,
            baseUrl = url,
            apiKey = key,
            chatModel = "",
            visionModel = "",
            ocrModel = ""
        )

        log("=== ${target.label} ===")
        log("BASE_URL=$url kind=${target.kind} key=已配置(len>0)")
        log("MODELS_URL=${provider.modelsRequestUrl()}")

        val models = runModelsStep(provider, key)
        log(models.evidence)

        val model = System.getenv(target.modelEnv)?.trim().takeUnless { it.isNullOrBlank() }
            ?: target.defaultModel
            ?: models.ids.firstOrNull()
        if (model.isNullOrBlank()) {
            log("CHAT skipped：未配置 ${target.modelEnv}，且模型列表为空，无法确定聊天模型名")
            return
        }
        log("CHAT_MODEL=$model")
        log(runChatStep(provider, model, key))
    }

    // ---------- 测试 ----------

    /** 无论是否配置凭据都会执行，输出各服务凭据配置情况（不含 Key 值）。 */
    @Test
    fun live_env_presence_summary() {
        listOf(anthropic, deepseekV1, seekai, agentRouter, justWoker).forEach { t ->
            val configured = !System.getenv(t.keyEnv).isNullOrBlank()
            log("ENV ${t.label}: key(${t.keyEnv})=${if (configured) "已配置" else "未配置"}")
        }
    }

    @Test
    fun anthropic_official_live() = runTarget(anthropic)

    @Test
    fun deepseek_preset_v1_live() = runTarget(deepseekV1)

    @Test
    fun deepseek_official_root_live() = runTarget(deepseekRoot)

    @Test
    fun seekai_live() = runTarget(seekai)

    @Test
    fun agentrouter_live() = runTarget(agentRouter)

    @Test
    fun justwoker_live() = runTarget(justWoker)

    companion object {
        private val evidenceFile: File =
            File(System.getProperty("java.io.tmpdir"), "hanako_live_evidence.txt")

        private val STATUS_REGEX = Regex("(?:Failed to get models: |Stream failed: )(\\d{3})")

        @JvmStatic
        @BeforeClass
        fun resetEvidence() {
            runCatching { evidenceFile.writeText("") }
        }
    }
}