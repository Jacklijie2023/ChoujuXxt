package `fun`.kirari.hanako.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic Messages / DeepSeek 的配置与 URL 规则的纯逻辑单元测试。
 *
 * 不依赖网络与 Android 运行时，用于验证：
 * Base URL 拼接规则、Provider 预设、枚举新增后的向后兼容性，以及收藏模型逻辑未回归。
 */
class ProviderPresetAndUrlTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---------- Base URL 与最终请求路径 ----------

    @Test
    fun anthropic_messages_url_normalizes_root_base_urls() {
        assertEquals("https://seekai.cc/v1/messages", anthropicMessagesRequestUrl("https://seekai.cc"))
        assertEquals("https://agentrouter.org/v1/messages", anthropicMessagesRequestUrl("https://agentrouter.org/"))
        assertEquals("https://api.justwoker.icu/v1/messages", anthropicMessagesRequestUrl("https://api.justwoker.icu"))
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", anthropicMessagesRequestUrl("https://api.deepseek.com/anthropic"))
    }

    @Test
    fun anthropic_messages_url_does_not_double_v1_or_messages() {
        assertEquals("https://example.com/v1/messages", anthropicMessagesRequestUrl("https://example.com/v1"))
        assertEquals("https://example.com/v1/messages", anthropicMessagesRequestUrl("https://example.com/v1/"))
        assertEquals("https://example.com/v1beta/messages", anthropicMessagesRequestUrl("https://example.com/v1beta"))
        assertEquals("https://example.com/v1/messages", anthropicMessagesRequestUrl("https://example.com/v1/messages"))
        assertFalse(anthropicMessagesRequestUrl("https://example.com/v1").contains("/v1/v1"))
        assertFalse(anthropicMessagesRequestUrl("https://example.com/v1/messages").contains("/messages/messages"))
    }

    @Test
    fun anthropic_messages_url_keeps_non_standard_path_prefix() {
        assertEquals("https://example.com/anthropic/v1/messages", anthropicMessagesRequestUrl("https://example.com/anthropic"))
    }

    @Test
    fun anthropic_messages_request_url_never_uses_chat_completions() {
        val provider = ModelProviderConfig(
            kind = ProviderKind.ANTHROPIC_MESSAGES,
            baseUrl = "https://seekai.cc"
        )
        val url = provider.resolvedRequestUrl()
        assertEquals("https://seekai.cc/v1/messages", url)
        assertFalse(url.contains("/chat/completions"))
    }

    @Test
    fun existing_anthropic_provider_url_is_unchanged() {
        val provider = ModelProviderConfig(
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/v1"
        )
        assertEquals("https://api.anthropic.com/v1/messages", provider.resolvedRequestUrl())
    }

    @Test
    fun openai_compatible_provider_uses_chat_completions() {
        val deepSeek = ModelProviderConfig(
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = DEEPSEEK_DEFAULT_BASE_URL
        )
        assertEquals("https://api.deepseek.com/v1/chat/completions", deepSeek.resolvedRequestUrl())
        assertEquals("https://api.deepseek.com/v1/models", deepSeek.modelsRequestUrl())
    }

    // ---------- Provider 预设与展示名称 ----------

    @Test
    fun deepseek_preset_uses_openai_compatible_protocol() {
        val provider = ProviderPreset.DEEPSEEK.createProvider(2)
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, provider.kind)
        assertEquals(DEEPSEEK_DEFAULT_BASE_URL, provider.baseUrl)
        assertEquals(DEEPSEEK_DEFAULT_MODEL, provider.chatModel)
    }

    @Test
    fun anthropic_messages_preset_uses_messages_protocol() {
        val provider = ProviderPreset.ANTHROPIC_MESSAGES.createProvider(1)
        assertEquals(ProviderKind.ANTHROPIC_MESSAGES, provider.kind)
        assertEquals("https://api.anthropic.com/v1", provider.baseUrl)
    }

    @Test
    fun custom_preset_keeps_previous_default_behaviour() {
        val provider = ProviderPreset.CUSTOM.createProvider(3)
        assertEquals("自定义提供方 3", provider.name)
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, provider.kind)
    }

    @Test
    fun anthropic_and_anthropic_messages_have_distinct_display_names() {
        assertEquals("Anthropic", ProviderKind.ANTHROPIC.displayName)
        assertEquals("Anthropic Messages", ProviderKind.ANTHROPIC_MESSAGES.displayName)
        assertTrue(ProviderKind.entries.contains(ProviderKind.ANTHROPIC))
        assertTrue(ProviderKind.entries.contains(ProviderKind.ANTHROPIC_MESSAGES))
    }

    // ---------- 序列化与向后兼容 ----------

    @Test
    fun legacy_anthropic_settings_still_load() {
        val legacy = """
            {"providers":[{"id":"p1","name":"Anthropic","kind":"ANTHROPIC",
            "baseUrl":"https://api.anthropic.com/v1","apiKey":"old-key","chatModel":"claude-x",
            "visionModel":"claude-x","ocrModel":"claude-x","favoriteModels":["m1","M2"],"enabled":true}],
            "selectedProviderId":"p1"}
        """.trimIndent()

        val settings = json.decodeFromString<AppSettings>(legacy)
        val provider = settings.providers.single()
        assertEquals(ProviderKind.ANTHROPIC, provider.kind)
        assertEquals("old-key", provider.apiKey)
        assertEquals("https://api.anthropic.com/v1", provider.baseUrl)
        assertEquals(listOf("m1", "M2"), provider.favoriteModels)
    }

    @Test
    fun new_provider_kind_round_trips_through_serialization() {
        val settings = AppSettings(
            providers = listOf(
                ModelProviderConfig(
                    id = "am",
                    name = "Anthropic Messages",
                    kind = ProviderKind.ANTHROPIC_MESSAGES,
                    baseUrl = "https://seekai.cc",
                    apiKey = "k"
                )
            )
        )
        val restored = json.decodeFromString<AppSettings>(json.encodeToString(settings))
        assertEquals(ProviderKind.ANTHROPIC_MESSAGES, restored.providers.single().kind)
        assertEquals("https://seekai.cc", restored.providers.single().baseUrl)
    }

    // ---------- 收藏模型逻辑未回归 ----------

    @Test
    fun favorite_update_only_touches_target_provider() {
        val settings = AppSettings(
            providers = listOf(
                ModelProviderConfig(
                    id = "a",
                    name = "Anthropic",
                    kind = ProviderKind.ANTHROPIC,
                    favoriteModels = listOf("keep")
                ),
                ModelProviderConfig(
                    id = "b",
                    name = "Anthropic Messages",
                    kind = ProviderKind.ANTHROPIC_MESSAGES
                )
            )
        )

        val updated = settings.updateProviderFavoriteModels("b") { favorites ->
            favorites.addIfMissing("X").addIfMissing("x")
        }

        assertEquals(listOf("keep"), updated.providers.first { it.id == "a" }.favoriteModels)
        assertEquals(listOf("X"), updated.providers.first { it.id == "b" }.favoriteModels)
    }

    // ---------- 模型列表 URL 与聊天 URL 归一化一致（P1-01） ----------

    @Test
    fun anthropic_messages_models_url_matches_chat_url_rules() {
        assertEquals("https://example.com/v1/models", anthropicMessagesModelsUrl("https://example.com"))
        assertEquals("https://example.com/v1/models", anthropicMessagesModelsUrl("https://example.com/"))
        assertEquals("https://example.com/v1/models", anthropicMessagesModelsUrl("https://example.com/v1"))
        assertEquals("https://example.com/v1/models", anthropicMessagesModelsUrl("https://example.com/v1/"))
        assertEquals("https://example.com/anthropic/v1/models", anthropicMessagesModelsUrl("https://example.com/anthropic"))
        assertEquals("https://example.com/v1/models", anthropicMessagesModelsUrl("https://example.com/v1/messages"))
        assertEquals("https://example.com/v1/models", anthropicMessagesModelsUrl("https://example.com/v1/messages/"))
    }

    @Test
    fun anthropic_messages_chat_and_models_urls_share_the_same_root() {
        listOf(
            "https://seekai.cc",
            "https://agentrouter.org/",
            "https://api.justwoker.icu",
            "https://example.com/v1",
            "https://example.com/anthropic"
        ).forEach { base ->
            val chat = anthropicMessagesRequestUrl(base)
            val models = anthropicMessagesModelsUrl(base)
            assertTrue("chat url for '$base': $chat", chat.endsWith("/v1/messages"))
            assertTrue("models url for '$base': $models", models.endsWith("/v1/models"))
            assertEquals(
                "chat/models must share the same prefix for '$base'",
                chat.removeSuffix("/messages"),
                models.removeSuffix("/models")
            )
        }
    }

    @Test
    fun anthropic_messages_provider_models_request_url_uses_chat_rule() {
        val provider = ModelProviderConfig(
            kind = ProviderKind.ANTHROPIC_MESSAGES,
            baseUrl = "https://seekai.cc"
        )
        assertEquals("https://seekai.cc/v1/messages", provider.resolvedRequestUrl())
        assertEquals("https://seekai.cc/v1/models", provider.modelsRequestUrl())
    }

    @Test
    fun other_providers_models_url_rules_are_unchanged() {
        assertEquals(
            "https://api.openai.com/v1/models",
            ModelProviderConfig(kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://api.openai.com/v1").modelsRequestUrl()
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            ModelProviderConfig(kind = ProviderKind.ANTHROPIC, baseUrl = "https://api.anthropic.com/v1").modelsRequestUrl()
        )
    }
}