package com.sieve.app.update

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateManifestFetcherTest {

    private val valid = """{"versionCode":3,"versionName":"1.1","apkUrl":"u","sha256":"s"}"""

    @Test fun primaryUsedFirstMirrorSkipped() = runBlocking {
        val hit = mutableListOf<String>()
        val http = object : HttpGet {
            override suspend fun get(url: String): String? { hit += url; return if (url == "primary") valid else null }
        }
        val m = UpdateManifestFetcher("primary", "mirror", http).fetch()!!
        assertEquals(3, m.versionCode)
        assertEquals(listOf("primary"), hit) // mirror never queried
        Unit
    }

    @Test fun failsOverToMirror() = runBlocking {
        val http = object : HttpGet {
            override suspend fun get(url: String): String? = if (url == "mirror") valid else "garbage"
        }
        assertEquals(3, UpdateManifestFetcher("primary", "mirror", http).fetch()!!.versionCode)
        Unit
    }

    @Test fun bothFailReturnsNull() = runBlocking {
        val http = object : HttpGet { override suspend fun get(url: String): String? = null }
        assertNull(UpdateManifestFetcher("primary", "mirror", http).fetch())
        Unit
    }
}
