package com.debricked.intellijplugin.core

import com.debricked.intellijplugin.domain.VulnerabilityFinding

private const val CACHE_TTL_MS = 30 * 60 * 1000L

data class CachedData(
    val value: Any,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    fun isFresh(ttlMs: Long = CACHE_TTL_MS): Boolean {
        return System.currentTimeMillis() - fetchedAt <= ttlMs
    }
}

class DataCache {
    private val lock = Any()
    private val cache = mutableMapOf<String, CachedData>()

    fun key(repositoryId: String, branchId: String?, tabType: String): String {
        val safeBranch = branchId?.takeIf { it.isNotBlank() } ?: "all-branches"
        return "$repositoryId:$safeBranch:$tabType"
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrLoad(key: String, forceRefresh: Boolean = false, loader: () -> T): T {
        synchronized(lock) {
            val cached = cache[key]
            if (!forceRefresh && cached != null && cached.isFresh()) {
                return cached.value as T
            }
            val loaded = loader()
            cache[key] = CachedData(loaded)
            return loaded
        }
    }

    fun invalidate(repositoryId: String, branchId: String? = null) {
        synchronized(lock) {
            val prefix = if (branchId.isNullOrBlank()) {
                "$repositoryId:"
            } else {
                "$repositoryId:$branchId"
            }
            cache.keys.removeAll { it.startsWith(prefix) }
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }
}

class VulnerabilityCache(private val cache: DataCache = DataCache()) {
    fun getOrLoad(
        repositoryId: String,
        branchId: String?,
        forceRefresh: Boolean = false,
        loader: () -> List<VulnerabilityFinding>
    ): List<VulnerabilityFinding> {
        return cache.getOrLoad(cache.key(repositoryId, branchId, "vulnerabilities"), forceRefresh, loader)
    }

    fun <T : Any> getOrLoadForQuery(
        repositoryId: String,
        branchId: String?,
        queryKey: String,
        forceRefresh: Boolean = false,
        loader: () -> T
    ): T {
        val branchKey = "${branchId?.takeIf { it.isNotBlank() } ?: "all-branches"}|$queryKey"
        return cache.getOrLoad(cache.key(repositoryId, branchKey, "vulnerabilities"), forceRefresh, loader)
    }

    fun invalidate(repositoryId: String, branchId: String? = null) {
        cache.invalidate(repositoryId, branchId)
    }

    fun clear() {
        cache.clear()
    }
}
