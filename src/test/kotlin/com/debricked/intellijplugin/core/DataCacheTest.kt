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
}


