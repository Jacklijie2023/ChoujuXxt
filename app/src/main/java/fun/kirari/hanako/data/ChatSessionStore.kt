package `fun`.kirari.hanako.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.chatSessionsDataStore by preferencesDataStore(name = "chat_sessions")

/**
 * 聊天会话的独立持久化存储。
 *
 * 使用独立的 DataStore（`chat_sessions`），【不并入 AppSettings】，与既有配置/历史互不影响。
 * 反序列化失败时返回空容器（由 [ChatSessionHolder] 归一化为一个空会话），
 * 不会清空用户既有配置与截图历史。
 */
internal class ChatSessionStore(private val context: Context) : ChatSessionPersistence {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun load(): ChatSessionsContainer {
        val raw = context.chatSessionsDataStore.data.map { it[SESSION_KEY] }.first()
        if (raw.isNullOrBlank()) return ChatSessionsContainer()
        return runCatching {
            json.decodeFromString<ChatSessionsContainer>(raw)
        }.getOrElse { ChatSessionsContainer() }
    }

    override suspend fun save(container: ChatSessionsContainer) {
        context.chatSessionsDataStore.edit { preferences ->
            preferences[SESSION_KEY] = json.encodeToString(ChatSessionsContainer.serializer(), container)
        }
    }

    companion object {
        private val SESSION_KEY = stringPreferencesKey("chat_sessions")
    }
}
