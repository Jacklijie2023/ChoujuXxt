package `fun`.kirari.hanako.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SettingsRepository(private val store: SettingsStore) {

    val settings = store.settings

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.update(transform)
    }

    fun updateModelSelection(
        scope: CoroutineScope,
        purpose: ModelPurpose,
        selection: ModelSelection
    ) {
        scope.launch {
            store.update { current ->
                when (purpose) {
                    ModelPurpose.TEXT -> current.copy(textModelSelection = selection)
                    ModelPurpose.VISION -> current.copy(visionModelSelection = selection)
                    ModelPurpose.OCR -> current.copy(ocrModelSelection = selection)
                }
            }
        }
    }

    fun updateModelSelectionWithFavorite(
        scope: CoroutineScope,
        purpose: ModelPurpose,
        selection: ModelSelection,
        favoriteModel: Boolean = false
    ) {
        scope.launch {
            store.update { current ->
                val next = when (purpose) {
                    ModelPurpose.TEXT -> current.copy(textModelSelection = selection)
                    ModelPurpose.VISION -> current.copy(visionModelSelection = selection)
                    ModelPurpose.OCR -> current.copy(ocrModelSelection = selection)
                }
                if (!favoriteModel || selection.providerId == null || selection.model.isBlank()) {
                    next
                } else {
                    next.updateProviderFavoriteModels(selection.providerId) { favorites ->
                        favorites.addIfMissing(selection.model)
                    }
                }
            }
        }
    }

    fun toggleFavoriteModel(
        scope: CoroutineScope,
        providerId: String,
        modelId: String
    ) {
        val trimmedModelId = modelId.trim()
        if (trimmedModelId.isBlank()) return
        scope.launch {
            store.update { current ->
                current.updateProviderFavoriteModels(providerId) { favorites ->
                    if (favorites.any { it.equals(trimmedModelId, ignoreCase = true) }) {
                        favorites.removeByName(trimmedModelId)
                    } else {
                        favorites + trimmedModelId
                    }
                }
            }
        }
    }

    fun selectAssistant(scope: CoroutineScope, assistantId: String) {
        scope.launch {
            store.update { current ->
                if (current.assistants.any { it.id == assistantId }) {
                    current.copy(selectedAssistantId = assistantId)
                } else {
                    current
                }
            }
        }
    }

    fun addFavoriteModel(scope: CoroutineScope, providerId: String, modelId: String) {
        val trimmedModelId = modelId.trim()
        if (trimmedModelId.isBlank()) return
        scope.launch {
            store.update { current ->
                current.updateProviderFavoriteModels(providerId) { favorites ->
                    favorites.addIfMissing(trimmedModelId)
                }
            }
        }
    }

    fun removeFavoriteModel(scope: CoroutineScope, providerId: String, modelId: String) {
        val trimmedModelId = modelId.trim()
        if (trimmedModelId.isBlank()) return
        scope.launch {
            store.update { current ->
                current.updateProviderFavoriteModels(providerId) { favorites ->
                    favorites.removeByName(trimmedModelId)
                }
            }
        }
    }
}

internal fun AppSettings.updateProviderFavoriteModels(
    providerId: String,
    transform: (List<String>) -> List<String>
): AppSettings {
    return copy(
        providers = providers.map { provider ->
            if (provider.id != providerId) {
                provider
            } else {
                provider.copy(
                    favoriteModels = transform(provider.favoriteModels)
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinctBy { it.lowercase() }
                )
            }
        }
    )
}

internal fun List<String>.addIfMissing(modelId: String): List<String> {
    return if (any { it.equals(modelId, ignoreCase = true) }) this else this + modelId
}

internal fun List<String>.removeByName(modelId: String): List<String> {
    return filterNot { it.equals(modelId, ignoreCase = true) }
}
