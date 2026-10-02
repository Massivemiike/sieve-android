package com.sieve.app.ui.common

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class SnackbarMessagesTest {

    @Test fun `a message posted before anyone collects is delivered once, in order`() = runTest {
        val a = SnackbarMessage("a")
        val b = SnackbarMessage("b", actionLabel = "Settings")
        SnackbarMessages.post(a)
        SnackbarMessages.post(b)
        assertEquals(listOf(a, b), SnackbarMessages.flow.take(2).toList())
    }

    @Test fun `past the buffer the oldest messages are dropped, never the newest`() = runTest {
        val all = (1..SnackbarMessages.BUFFER + 3).map { SnackbarMessage("m$it") }
        all.forEach(SnackbarMessages::post)
        assertEquals(all.takeLast(SnackbarMessages.BUFFER), SnackbarMessages.flow.take(SnackbarMessages.BUFFER).toList())
    }
}
