package com.sieve.app.net

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalNetworkAccessTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `the permission is the one Android 17 defines and the first enforcing level is 37`() {
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", LocalNetworkAccess.PERMISSION)
        assertEquals(37, LocalNetworkAccess.FIRST_API)
    }

    @Test fun `it is required from Android 17 and not before`() {
        assertFalse(LocalNetworkAccess.isRequired(26))
        assertFalse(LocalNetworkAccess.isRequired(35))
        assertFalse(LocalNetworkAccess.isRequired(36))
        assertTrue(LocalNetworkAccess.isRequired(37))
        assertTrue(LocalNetworkAccess.isRequired(38))
    }

    @Test fun `below Android 17 the local network is always allowed, whatever the (nonexistent) permission says`() {
        shadowOf(app).denyPermissions(LocalNetworkAccess.PERMISSION)
        assertTrue(LocalNetworkAccess.isGranted(app, sdkInt = 36))
        assertTrue(LocalNetworkAccess.isGranted(app, sdkInt = 26))
    }

    @Test fun `on Android 17 it follows the permission`() {
        shadowOf(app).denyPermissions(LocalNetworkAccess.PERMISSION)
        assertFalse(LocalNetworkAccess.isGranted(app, sdkInt = 37))
        shadowOf(app).grantPermissions(LocalNetworkAccess.PERMISSION)
        assertTrue(LocalNetworkAccess.isGranted(app, sdkInt = 37))
        shadowOf(app).denyPermissions(LocalNetworkAccess.PERMISSION) // revoked again in the system settings
        assertFalse(LocalNetworkAccess.isGranted(app, sdkInt = 37))
    }

    @Test fun `on the emulated device (Android 15) the default is allowed`() {
        assertTrue(LocalNetworkAccess.isGranted(app))
    }

    @Test fun `the settings intent opens this app's details page in a new task`() {
        val i = LocalNetworkAccess.appSettingsIntent(app)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, i.action)
        assertEquals("package:${app.packageName}", i.dataString)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
