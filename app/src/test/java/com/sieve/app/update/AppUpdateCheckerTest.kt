package com.sieve.app.update

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class AppUpdateCheckerTest {

    private fun checker(json: String?, installed: Int): AppUpdateChecker {
        val http = object : HttpGet { override suspend fun get(url: String): String? = if (url == "p") json else null }
        return AppUpdateChecker(UpdateManifestFetcher("p", "m", http), installed)
    }

    private fun manifest(code: Int) = """{"versionCode":$code,"versionName":"x","apkUrl":"u","sha256":"s"}"""

    @Test fun availableWhenManifestIsNewer() = runBlocking {
        val s = checker(manifest(5), installed = 3).check()
        assertTrue(s is UpdateStatus.Available && s.manifest.versionCode == 5)
        Unit
    }

    @Test fun upToDateWhenSameOrOlder() = runBlocking {
        assertTrue(checker(manifest(3), installed = 3).check() is UpdateStatus.UpToDate)
        assertTrue(checker(manifest(2), installed = 3).check() is UpdateStatus.UpToDate)
        Unit
    }

    @Test fun unknownWhenFetchFails() = runBlocking {
        assertTrue(checker(null, installed = 3).check() is UpdateStatus.Unknown)
        Unit
    }
}
