package com.sieve.app.net

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals

class HostLookupTest {

    @Test fun `it returns what the lookup found`() = runBlocking {
        val asked = mutableListOf<String>()
        val l = HostLookup(lookup = { asked += it; listOf("192.168.1.5", "fe80:0:0:0:a:b:c:d%wlan0") })
        assertEquals(listOf("192.168.1.5", "fe80:0:0:0:a:b:c:d%wlan0"), l.addresses("nas.example.org"))
        assertEquals(listOf("nas.example.org"), asked)
    }

    @Test fun `a lookup that fails is an empty answer, not an exception`() = runBlocking {
        val l = HostLookup(lookup = { throw java.net.UnknownHostException(it) })
        assertEquals(emptyList(), l.addresses("no-such-host.example"))
    }

    @Test fun `a lookup that is too slow is given up on at the timeout and does not hold the caller`() = runBlocking {
        val release = CountDownLatch(1)
        try {
            val l = HostLookup(timeoutMs = 50, lookup = { release.await(); listOf("192.168.1.5") })
            // null here would mean the caller was still held after 5 s although the lookup's own timeout is 50 ms
            val answer = withTimeoutOrNull(5_000) { l.addresses("slow.example.org") }
            assertEquals(emptyList(), answer, "a lookup that is still blocked after its timeout is an empty answer")
        } finally {
            release.countDown()
        }
    }

    @Test fun `the system lookup reads an address literal without asking DNS`() {
        assertEquals(listOf("192.168.1.5"), HostLookup.systemLookup("192.168.1.5"))
        assertEquals(listOf("127.0.0.1"), HostLookup.systemLookup("127.0.0.1"))
    }
}
