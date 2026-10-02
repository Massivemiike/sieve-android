package com.sieve.app.ui.common

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The answer of the Android 17 "Nearby devices" dialog. The dialog's result is delivered through the Activity result registry, which
 * outlives the Activity; the action that waited for it is a lambda held in the composition, which does not. A rotation (or a theme or
 * language change) while the dialog is up therefore delivers an answer with nothing waiting.
 */
class LocalNetworkPromptTest {

    private val denied = SnackbarMessage("denied", actionLabel = "Settings")

    private fun answer(granted: Boolean, pending: (() -> Unit)?): List<SnackbarMessage> {
        val posted = mutableListOf<SnackbarMessage>()
        onLocalNetworkAnswer(granted, pending, post = { posted += it }, denied = { denied })
        return posted
    }

    @Test fun `a grant runs the waiting action and says nothing`() {
        var ran = 0
        assertTrue(answer(granted = true, pending = { ran++ }).isEmpty())
        assertEquals(1, ran)
    }

    @Test fun `a refusal starts nothing and says why, with the way to Settings`() {
        var ran = 0
        assertEquals(listOf(denied), answer(granted = false, pending = { ran++ }))
        assertEquals(0, ran)
    }

    @Test fun `a refusal is reported even when the Activity was recreated and nothing is waiting`() {
        assertEquals(listOf(denied), answer(granted = false, pending = null))
    }

    @Test fun `a grant with nothing waiting tells the user to tap again instead of dropping the tap silently`() {
        val posted = answer(granted = true, pending = null)
        assertEquals(listOf(SnackbarMessage(LOCAL_NETWORK_ALLOWED_TEXT)), posted)
        assertTrue(posted.single().actionLabel == null, "plain message, no action")
    }
}
