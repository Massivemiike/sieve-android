package com.sieve.queue.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OutputIntentsTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val uri = "content://media/external/downloads/7"

    @Test fun `only a content Uri is openable`() {
        assertEquals(uri, OutputIntents.openableUri(uri).toString())
        assertNull(OutputIntents.openableUri(null))
        assertNull(OutputIntents.openableUri(""))
        assertNull(OutputIntents.openableUri("Download/Sieve/clip.mp4"))            // a display path, not a location
        assertNull(OutputIntents.openableUri("file:///data/user/0/x/files/out.mp4")) // would throw FileUriExposedException
    }

    @Test fun `view intent targets the file and grants read access`() {
        val i = OutputIntents.viewIntent(ctx, OutputIntents.openableUri(uri)!!)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(uri, i.data.toString())
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `open starts the viewer`() {
        assertTrue(OutputIntents.open(ctx, uri))
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(started)
        assertEquals(uri, started.data.toString())
    }

    @Test fun `open reports false instead of crashing when no app can open it`() {
        val noViewer = object : ContextWrapper(ctx) {
            override fun startActivity(intent: Intent?) = throw ActivityNotFoundException("no viewer")
        }
        assertFalse(OutputIntents.open(noViewer, uri))
    }

    @Test fun `open reports false when the grant is refused`() {
        val refused = object : ContextWrapper(ctx) {
            override fun startActivity(intent: Intent?) = throw SecurityException("denied")
        }
        assertFalse(OutputIntents.open(refused, uri))
    }

    @Test fun `open reports false for a path that is not a Uri`() {
        assertFalse(OutputIntents.open(ctx, "Download/Sieve/clip.mp4"))
        assertFalse(OutputIntents.open(ctx, null))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }
}
