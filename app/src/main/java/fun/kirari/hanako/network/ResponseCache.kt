package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.debug.AppDebugLogStore
import java.security.MessageDigest

/**
 * LLM 响应缓存
 * 缓存相同的请求结果，避免重复调用 API
 */
object ResponseCache {
    private const val TAG = "ResponseCache"
    private const val MAX_CACHE_SIZE = 100
    private const val CACHE_TTL_MS = 600_000L // 10 分钟

    private val cache = LinkedHashMap<String, CacheEntry>(MAX_CACHE_SIZE, 0.75f, true)

    data class CacheEntry(
        val response: String,
        val timestamp: Long,
        val hitCount: Int = 0
    )

    /**
     * 生成缓存键
     */
    fun generateKey(
        providerId: String,
        model: String,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val input = "$providerId|$model|$systemPrompt|$userPrompt"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 获取缓存的响应
     */
    fun get(key: String): String? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS) {
            cache.remove(key)
            AppDebugLogStore.d(TAG, "Cache expired for key=${key.take(8)}")
            return null
        }
        AppDebugLogStore.d(TAG, "Cache hit for key=${key.take(8)}, hits=${entry.hitCount + 1}")
        cache[key] = entry.copy(hitCount = entry.hitCount + 1)
        return entry.response
    }

    /**
     * 存入缓存
     */
    fun put(key: String, response: String) {
        if (cache.size >= MAX_CACHE_SIZE) {
            val oldest = cache.keys.first()
            cache.remove(oldest)
        }
        cache[key] = CacheEntry(response, System.currentTimeMillis())
        AppDebugLogStore.d(TAG, "Cached response for key=${key.take(8)}")
    }

    /**
     * 清空缓存
     */
    fun clear() {
        cache.clear()
        AppDebugLogStore.i(TAG, "Cache cleared")
    }

    /**
     * 获取缓存统计
     */
    fun stats(): String {
        val totalHits = cache.values.sumOf { it.hitCount }
        return "缓存条目: ${cache.size}, 总命中: $totalHits"
    }
}
