package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import kotlinx.coroutines.flow.Flow

internal data class StreamRequest(
    val provider: ModelProviderConfig,
    val model: String,
    val systemPrompt: String,
    val userPrompt: String,
    val imagesBase64: List<String> = emptyList(),
    val tools: List<ToolDef>? = null,
    val firstDeltaTimeoutMillis: Long
) {
    val hasImages: Boolean get() = imagesBase64.isNotEmpty()
    
    @Deprecated("Use imagesBase64 instead", replaceWith = ReplaceWith("imagesBase64"))
    val imageBase64: String? get() = imagesBase64.firstOrNull()
}

internal interface ProviderAdapter {
    suspend fun stream(request: StreamRequest): Flow<LlmEvent>
}

internal class PendingToolCall(
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder(),
    var id: String? = null
)
