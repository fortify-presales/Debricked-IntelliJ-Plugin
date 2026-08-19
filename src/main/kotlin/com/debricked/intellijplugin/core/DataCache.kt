package com.debricked.intellijplugin.core

import com.debricked.intellijplugin.domain.DependencyPageResult
import com.debricked.intellijplugin.domain.VulnerabilityFinding
import java.net.ConnectException
import java.net.SocketTimeoutException

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
        var cachedSnapshot: CachedData? = null
        synchronized(lock) {
            val cached = cache[key]
            cachedSnapshot = cached
            if (!forceRefresh && cached != null && cached.isFresh()) {
                return cached.value as T
            }
        }
        try {
            val loaded = loader()
            synchronized(lock) {
                cache[key] = CachedData(loaded)
            }
            return loaded
        } catch (e: Exception) {
            val transient = e is SocketTimeoutException || e is ConnectException
            if (transient && cachedSnapshot != null) {
                return cachedSnapshot!!.value as T
            }
            throw e
        }
    }

    fun invalidate(repositoryId: String, branchId: String? = null) {
        synchronized(lock) {
            if (branchId.isNullOrBlank()) {
                val repositoryPrefix = "$repositoryId:"
                cache.keys.removeAll { it.startsWith(repositoryPrefix) }
                return
            }

            val basePrefix = "$repositoryId:$branchId"
            // Match exact branch namespace boundaries only:
            // - "$repo:$branch:$tab" for non-query keys
            // - "$repo:$branch|$query:$tab" for query keys
            cache.keys.removeAll { key ->
                key.startsWith("$basePrefix:") || key.startsWith("$basePrefix|")
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }
}

class DependencyCache(private val cache: DataCache = DataCache()) {
    fun <T : Any> getOrLoad(
        repositoryId: String,
        branchId: String?,
        queryKey: String,
        forceRefresh: Boolean = false,
        loader: () -> T
    ): T {
        val branchKey = "${branchId?.takeIf { it.isNotBlank() } ?: "all-branches"}|$queryKey"
        return cache.getOrLoad(cache.key(repositoryId, branchKey, "dependencies"), forceRefresh, loader)
    }

    fun invalidate(repositoryId: String, branchId: String? = null) {
        cache.invalidate(repositoryId, branchId)
    }

    fun clear() {
        cache.clear()
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
