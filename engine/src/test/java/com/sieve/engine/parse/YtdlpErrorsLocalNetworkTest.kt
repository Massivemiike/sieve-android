package com.sieve.engine.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The failure the queue writes when Android 17 blocks a LAN download, and how the rule table reads it back. */
class YtdlpErrorsLocalNetworkTest {

    @Test fun `the blocked text is an ERROR line that names the host`() {
        val raw = YtdlpErrors.localNetworkBlocked("192.168.1.20")
        assertTrue(raw.startsWith("ERROR: "), raw)
        assertTrue("192.168.1.20" in raw, raw)
        assertEquals(raw, YtdlpErrors.errorLines(raw), "an ERROR line is what the rule table matches")
        assertFalse("(" in YtdlpErrors.localNetworkBlocked(null), "no host, no parentheses")
        assertEquals(YtdlpErrors.localNetworkBlocked(null), YtdlpErrors.localNetworkBlocked("  "))
    }

    @Test fun `it humanizes to LOCAL_NETWORK with a hint that names the permission and is not transient`() {
        val h = YtdlpErrors.humanize(YtdlpErrors.localNetworkBlocked("nas"))
        assertEquals(ErrorKind.LOCAL_NETWORK, h.kind)
        assertFalse(h.transient, "retrying cannot help until the user grants the permission")
        assertTrue("local network" in h.message, h.message)
        assertTrue("Nearby devices" in h.hint.orEmpty(), h.hint)
        assertEquals(h.message + " — " + h.hint, YtdlpErrors.format(h))
    }

    @Test fun `it wins over the timeout that is still in the log`() {
        val raw = YtdlpErrors.localNetworkBlocked("nas") + "\nERROR: [generic] Unable to download webpage: <urlopen error timed out>"
        assertEquals(ErrorKind.LOCAL_NETWORK, YtdlpErrors.humanize(raw).kind)
    }

    @Test fun `a plain timeout is still a transient network problem`() {
        val h = YtdlpErrors.humanize("ERROR: [generic] Unable to download webpage: <urlopen error timed out>")
        assertEquals(ErrorKind.NETWORK, h.kind)
        assertTrue(h.transient)
    }
}
