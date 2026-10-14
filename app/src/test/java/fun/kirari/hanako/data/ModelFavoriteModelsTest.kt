package `fun`.kirari.hanako.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自定义模型保存所依赖的“提供方收藏模型”去重与规范化规则。
 *
 * 这些规则同时服务于三个入口（设置页模型用途选择、查看可用模型、悬浮球面板）：
 * 自定义模型最终都会经过 updateProviderFavoriteModels 归一化，因此在这里锁定其行为。
 */
class ModelFavoriteModelsTest {

    private fun settingsWith(vararg providers: ModelProviderConfig): AppSettings =
        AppSettings(providers = providers.toList())

    @Test
    fun addIfMissing_isIdempotentIgnoringCase() {
        val favorites = listOf("gpt-4o-mini")
        assertEquals(favorites, favorites.addIfMissing("gpt-4o-mini"))
        assertEquals(favorites, favorites.addIfMissing("GPT-4O-MINI"))
    }

    @Test
    fun removeByName_isCaseInsensitive() {
        val favorites = listOf("gpt-4o-mini", "deepseek-chat")
        assertEquals(listOf("deepseek-chat"), favorites.removeByName("GPT-4O-MINI"))
    }

    @Test
    fun updateProviderFavoriteModels_addsCustomModelToTargetProviderOnly() {
        val target = ModelProviderConfig(id = "a", favoriteModels = listOf("gpt-4o"))
        val other = ModelProviderConfig(id = "b", favoriteModels = listOf("claude-3-5-sonnet"))
        val settings = settingsWith(target, other)

        val updated = settings.updateProviderFavoriteModels("a") { it.addIfMissing("deepseek-chat") }

        assertEquals(listOf("gpt-4o", "deepseek-chat"), updated.providers.first { it.id == "a" }.favoriteModels)
        assertEquals(listOf("claude-3-5-sonnet"), updated.providers.first { it.id == "b" }.favoriteModels)
    }

    @Test
    fun updateProviderFavoriteModels_trimsBlanksAndDeduplicatesByLowercase() {
        val provider = ModelProviderConfig(id = "a", favoriteModels = listOf("gpt-4o"))
        val settings = settingsWith(provider)

        val updated = settings.updateProviderFavoriteModels("a") {
            it + "  DeepSeek-Chat  " + "" + "deepseek-chat" + "GPT-4O"
        }

        assertEquals(listOf("gpt-4o", "DeepSeek-Chat"), updated.providers.single().favoriteModels)
    }

    @Test
    fun updateProviderFavoriteModels_ignoresUnknownProvider() {
        val provider = ModelProviderConfig(id = "a", favoriteModels = listOf("gpt-4o"))
        val settings = settingsWith(provider)

        val updated = settings.updateProviderFavoriteModels("missing") { it.addIfMissing("deepseek-chat") }

        assertEquals(settings.providers, updated.providers)
    }
}