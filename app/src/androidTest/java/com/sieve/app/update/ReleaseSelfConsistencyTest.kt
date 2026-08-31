package com.sieve.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sieve.app.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertTrue

/** Proves the update pipeline is internally consistent against this very build. */
@RunWith(AndroidJUnit4::class)
class ReleaseSelfConsistencyTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun manifestMatchingInstalledVersionIsUpToDate() = runBlocking {
        val json = """{"versionCode":${BuildConfig.VERSION_CODE},"versionName":"x","apkUrl":"u","sha256":"s"}"""
        val http = object : HttpGet { override suspend fun get(url: String): String? = if (url == "p") json else null }
        val checker = AppUpdateChecker(UpdateManifestFetcher("p", "m", http), BuildConfig.VERSION_CODE)
        assertTrue(checker.check() is UpdateStatus.UpToDate)
        Unit
    }

    @Test
    fun ownApkVerifiesAgainstItsOwnDigestAndSigner() {
        val ownApk = File(ctx.applicationInfo.sourceDir)
        val digest = ApkVerifier.sha256(ownApk)
        assertTrue(ApkVerifier.matchesSha256(ownApk, digest))
        assertTrue(ApkVerifier.apkSignerMatchesInstalled(ctx, ownApk))
    }
}
