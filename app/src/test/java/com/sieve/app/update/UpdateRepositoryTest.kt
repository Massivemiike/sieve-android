package com.sieve.app.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manifest = UpdateManifest(5, "1.5", "https://x/apk", "EXPECTED_SHA")

    private fun repo(
        verifySha: Boolean,
        verifySigner: Boolean,
        installed: MutableList<File>,
        checkerJson: String? = null,
    ): UpdateRepository {
        val http = object : HttpGet { override suspend fun get(url: String): String? = checkerJson }
        val checker = AppUpdateChecker(UpdateManifestFetcher("p", "m", http), installedVersionCode = 1)
        val apk = tmp.newFile("dl-${System.nanoTime()}.apk").apply { writeText("bytes") }
        return UpdateRepository(
            checker = checker,
            download = { _, _, onProgress -> onProgress(1f); apk },
            verifySha = { _, _ -> verifySha },
            verifySigner = { _ -> verifySigner },
            install = { f -> installed += f; true },
        )
    }

    @Test fun checkNowReportsAvailable() = runTest {
        val r = repo(true, true, mutableListOf(), checkerJson = """{"versionCode":9,"versionName":"x","apkUrl":"u","sha256":"s"}""")
        r.checkNow()
        val s = r.state.value.status
        assertTrue(s is UpdateStatus.Available && s.manifest.versionCode == 9)
    }

    @Test fun happyPathInstalls() = runTest {
        val installed = mutableListOf<File>()
        repo(verifySha = true, verifySigner = true, installed = installed).downloadAndInstall(manifest)
        assertEquals(1, installed.size)
    }

    @Test fun checksumMismatchAbortsWithoutInstalling() = runTest {
        val installed = mutableListOf<File>()
        val r = repo(verifySha = false, verifySigner = true, installed = installed)
        r.downloadAndInstall(manifest)
        assertTrue(installed.isEmpty())
        assertTrue(r.state.value.error!!.contains("Checksum"))
    }

    @Test fun signerMismatchAbortsWithoutInstalling() = runTest {
        val installed = mutableListOf<File>()
        val r = repo(verifySha = true, verifySigner = false, installed = installed)
        r.downloadAndInstall(manifest)
        assertTrue(installed.isEmpty())
        assertTrue(r.state.value.error!!.contains("Signature"))
    }
}
