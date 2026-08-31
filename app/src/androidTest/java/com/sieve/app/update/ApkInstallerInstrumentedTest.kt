package com.sieve.app.update

import android.content.Context
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ApkInstallerInstrumentedTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun canInstallReturnsWithoutThrowing() {
        ApkInstaller(ctx).canInstall() // API 26+ returns a Boolean; must not throw
    }

    @Test
    fun permissionIntentTargetsUnknownSources() {
        val i = ApkInstaller(ctx).requestInstallPermissionIntent()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, i.action)
        assertEquals("package:${ctx.packageName}", i.data.toString())
    }

    @Test
    fun packageInstallerSessionLifecycleWorks() {
        // Exercise the session create/abandon path; do NOT commit (would try to replace the app under test).
        val pi = ctx.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.abandonSession(id)
    }
}
