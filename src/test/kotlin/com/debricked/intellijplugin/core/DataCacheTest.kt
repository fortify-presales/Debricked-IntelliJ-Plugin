package com.debricked.intellijplugin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class DataCacheTest {

    @Test
    fun returnsCachedValueWhenLoaderTimesOut() {
        val cache = DataCache()
        val key = "repo:main:vulnerabilities"

        val first = cache.getOrLoad<String>(key) { "cached-value" }
        val second = cache.getOrLoad<String>(key, forceRefresh = true) { throw SocketTimeoutException("Read timed out") }

        assertEquals("cached-value", first)
        assertEquals("cached-value", second)
    }

    @Test(expected = SocketTimeoutException::class)
    fun throwsTimeoutWhenNoCachedValueExists() {
        val cache = DataCache()
        cache.getOrLoad<String>("repo:main:vulnerabilities") { throw SocketTimeoutException("Read timed out") }
    }

    @Test
    fun returnsCachedValueWhenLoaderConnectionFails() {
        val cache = DataCache()
        val key = "repo:main:vulnerabilities"

        cache.getOrLoad<String>(key) { "cached-value" }
        val fallback = cache.getOrLoad<String>(key, forceRefresh = true) { throw ConnectException("Connection refused") }

        assertEquals("cached-value", fallback)
    }

    @Test(expected = IOException::class)
    fun doesNotFallbackForNonTransientErrors() {
        val cache = DataCache()
        val key = "repo:main:vulnerabilities"

        cache.getOrLoad<String>(key) { "cached-value" }
        cache.getOrLoad<String>(key, forceRefresh = true) { throw IOException("Unexpected IO error") }
    }

    @Test
    fun updatesCacheAfterSuccessfulReload() {
        val cache = DataCache()
        val key = "repo:main:vulnerabilities"

        cache.getOrLoad<String>(key) { "old" }
        val updated = cache.getOrLoad<String>(key, forceRefresh = true) { "new" }

        assertEquals("new", updated)
        val readBack = cache.getOrLoad<String>(key) { "unexpected" }
        assertTrue(readBack == "new")
    }

    @Test
    fun invalidateBranch_clearsOnlyTargetBranchNamespace() {
        val cache = DataCache()
        val mainKey = cache.key("repo", "main", "vulnerabilities")
        val mainQueryKey = cache.key("repo", "main|page=1", "vulnerabilities")
        val main2Key = cache.key("repo", "main2", "vulnerabilities")

        cache.getOrLoad<String>(mainKey) { "main-cached" }
        cache.getOrLoad<String>(mainQueryKey) { "main-query-cached" }
        cache.getOrLoad<String>(main2Key) { "main2-cached" }

        cache.invalidate("repo", "main")

        var mainReloaded = false
        val mainAfter = cache.getOrLoad<String>(mainKey) {
            mainReloaded = true
            "main-reloaded"
        }
        var mainQueryReloaded = false
        val mainQueryAfter = cache.getOrLoad<String>(mainQueryKey) {
            mainQueryReloaded = true
            "main-query-reloaded"
        }
        var main2Reloaded = false
        val main2After = cache.getOrLoad<String>(main2Key) {
            main2Reloaded = true
            "main2-reloaded"
        }

        assertTrue(mainReloaded)
        assertTrue(mainQueryReloaded)
        assertTrue(!main2Reloaded)
        assertEquals("main-reloaded", mainAfter)
        assertEquals("main-query-reloaded", mainQueryAfter)
        assertEquals("main2-cached", main2After)
    }
}


