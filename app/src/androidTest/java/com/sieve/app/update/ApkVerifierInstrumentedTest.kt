package com.sieve.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ApkVerifierInstrumentedTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun ownApkSignerMatchesInstalled() {
        // The app-under-test's own APK is signed by its own cert -> self-consistent match.
        val ownApk = File(ctx.applicationInfo.sourceDir)
        assertTrue(ApkVerifier.apkSignerMatchesInstalled(ctx, ownApk))
    }

    @Test
    fun nonApkFileDoesNotMatch() {
        val f = File(ctx.cacheDir, "not-an-apk-${System.nanoTime()}.bin").apply { writeText("nope") }
        assertFalse(ApkVerifier.apkSignerMatchesInstalled(ctx, f))
        f.delete()
    }

    @Test
    fun installedSignerIsPresent() {
        assertNotNull(ApkVerifier.installedSignerSha256(ctx, ctx.packageName))
    }
}
