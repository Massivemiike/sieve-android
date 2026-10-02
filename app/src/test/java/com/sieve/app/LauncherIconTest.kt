package com.sieve.app

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.VectorDrawable
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The launcher icon (direction D "Mesh S"). Until it existed the manifest named no icon at all, so every launcher drew
 * Android's generic default. These pin that the app declares it and that it is a full adaptive icon, including the
 * monochrome layer Android 13+ needs for themed icons (without it a themed home screen shows the coloured icon).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherIconTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun theAppDeclaresTheSieveLauncherIcon() {
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        assertEquals(R.mipmap.ic_launcher, info.icon)
    }

    @Test fun bothLauncherIconsAreAdaptiveWithBackgroundForegroundAndThemedLayers() {
        for (id in intArrayOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val name = ctx.resources.getResourceEntryName(id)
            val icon = ContextCompat.getDrawable(ctx, id)
            assertTrue("$name is an adaptive icon", icon is AdaptiveIconDrawable)
            icon as AdaptiveIconDrawable
            assertTrue("$name background is the solid near-black", icon.background is ColorDrawable)
            assertEquals(0xFF101216.toInt(), (icon.background as ColorDrawable).color)
            assertTrue("$name foreground is the vector art", icon.foreground is VectorDrawable)
            assertNotNull("$name needs a monochrome layer for themed icons", icon.monochrome)
        }
    }

    // The source manifest, not only the merged one Robolectric reads: roundIcon has no public ApplicationInfo field.
    @Test fun theManifestNamesTheRoundIconToo() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }
}
