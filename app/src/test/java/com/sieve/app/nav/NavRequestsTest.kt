package com.sieve.app.nav

import android.content.Intent
import com.sieve.app.ui.nav.NavRequests
import com.sieve.queue.service.QueueNotification
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class NavRequestsTest {

    @Test fun failureNotificationIntentOpensTheQueue() {
        val tap = Intent().putExtra(QueueNotification.EXTRA_OPEN_QUEUE, true)
        assertEquals("queue", NavRequests.routeFor(tap))
    }

    @Test fun anOrdinaryLaunchAsksForNothing() {
        assertNull(NavRequests.routeFor(Intent(Intent.ACTION_MAIN)))
        assertNull(NavRequests.routeFor(Intent().putExtra(QueueNotification.EXTRA_OPEN_QUEUE, false)))
        assertNull(NavRequests.routeFor(null as Intent?))
    }

    @Test fun requestIsHeldUntilConsumed() {
        NavRequests.consume()
        assertNull(NavRequests.route.value)
        NavRequests.open("queue")
        assertEquals("queue", NavRequests.route.value)
        NavRequests.consume()
        assertNull(NavRequests.route.value)
    }
}
