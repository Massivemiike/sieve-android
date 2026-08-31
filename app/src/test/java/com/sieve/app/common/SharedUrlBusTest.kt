package com.sieve.app.common

import android.content.Intent
import com.sieve.app.ui.common.SharedUrlBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedUrlBusTest {

    @Test fun sendPlainUrl() {
        assertEquals(
            "https://youtube.com/watch?v=x",
            SharedUrlBus.extract(Intent.ACTION_SEND, "text/plain", "https://youtube.com/watch?v=x", null),
        )
    }

    @Test fun sendWrappedUrlPicksFirstHttpToken() {
        assertEquals(
            "https://vimeo.com/123",
            SharedUrlBus.extract(Intent.ACTION_SEND, "text/plain", "Check this out https://vimeo.com/123 cool", null),
        )
    }

    @Test fun nonUrlTextReturnsNull() {
        assertNull(SharedUrlBus.extract(Intent.ACTION_SEND, "text/plain", "just some text", null))
    }

    @Test fun unrelatedActionReturnsNull() {
        assertNull(SharedUrlBus.extract(Intent.ACTION_MAIN, null, "https://x/y", null))
    }
}
