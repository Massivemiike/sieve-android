package com.sieve.app.net

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkGateTest {

    private fun gate(granted: Boolean, proxy: String? = null, onProxyRead: () -> Unit = {}) =
        LocalNetworkGate(granted = { granted }, proxy = { onProxyRead(); proxy })

    /** A gate whose resolver answers from [dns] (a name missing from it does not resolve) and records every name it was asked. */
    private fun gateWithDns(granted: Boolean, dns: Map<String, List<String>>, asked: MutableList<String> = mutableListOf(), proxy: String? = null) =
        LocalNetworkGate(granted = { granted }, proxy = { proxy }, resolve = { host -> asked += host; dns[host].orEmpty() })

    // ---- the Download screen: ask before reading or queueing ----------------------------------

    @Test fun `a LAN link needs the permission while it is not granted`() = runTest {
        for (url in listOf("http://nas:8096/Videos/1.mp4", "https://192.168.1.20/x.mp4", "http://printer.local/x", "http://[fe80::1]/x", "http://100.64.1.1/x")) {
            assertTrue(gate(granted = false).needsPermission(url), url)
        }
    }

    @Test fun `once it is granted nothing is asked, and the proxy setting is not even read`() = runTest {
        var proxyReads = 0
        val g = gate(granted = true, onProxyRead = { proxyReads++ })
        assertFalse(g.needsPermission("http://nas/x.mp4"))
        assertEquals(0, proxyReads)
    }

    @Test fun `an internet link and loopback never need it`() = runTest {
        val g = gate(granted = false)
        for (url in listOf("https://www.youtube.com/watch?v=abc", "https://vimeo.com/1", "http://localhost:8080/x.mp4", "http://127.0.0.1:8080/x.mp4", "ytsearch:cats", "")) {
            assertFalse(g.needsPermission(url), url)
        }
    }

    @Test fun `a proxy on the LAN needs it even for an internet link`() = runTest {
        assertTrue(gate(granted = false, proxy = "http://192.168.1.2:3128").needsPermission("https://www.youtube.com/watch?v=abc"))
    }

    @Test fun `a loopback or internet proxy does not, even in front of a LAN link`() = runTest {
        assertFalse(gate(granted = false, proxy = "socks5://127.0.0.1:1080").needsPermission("http://nas/x.mp4"))
        assertFalse(gate(granted = false, proxy = "http://proxy.example.com:3128").needsPermission("http://nas/x.mp4"))
    }

    @Test fun `a null link needs nothing`() = runTest {
        assertFalse(gate(granted = false).needsPermission(null))
    }

    // ---- the queue: is the block what failed this row? ----------------------------------------

    @Test fun `the guard blocks a LAN row only while the permission is missing`() = runTest {
        assertTrue(gate(granted = false).guard.blocks("http://nas/x.mp4", listOf("-f", "best")))
        assertFalse(gate(granted = true).guard.blocks("http://nas/x.mp4", listOf("-f", "best")))
        assertFalse(gate(granted = false).guard.blocks("https://www.youtube.com/watch?v=abc", listOf("-f", "best")))
    }

    @Test fun `the guard judges a queued row by the proxy it carries, not by today's setting`() = runTest {
        val today = gate(granted = false, proxy = "http://192.168.1.2:3128") // Settings now point at a LAN proxy...
        assertFalse(today.guard.blocks("https://www.youtube.com/watch?v=abc", listOf("-f", "best"))) // ...but this row never used it
        assertTrue(today.guard.blocks("https://www.youtube.com/watch?v=abc", listOf("--proxy", "http://192.168.1.2:3128")))
        assertFalse(today.guard.blocks("http://nas/x.mp4", listOf("--proxy", "socks5://127.0.0.1:1080")))
    }

    // ---- a public-looking name that points at the LAN (split-horizon DNS, Plex, Tailscale) --------

    @Test fun `a name that resolves to a LAN address needs the permission, one that resolves to the internet does not`() = runTest {
        val g = gateWithDns(
            granted = false,
            dns = mapOf(
                "nas.example.org" to listOf("192.168.1.5"),
                "192-168-1-5.abcdef.plex.direct" to listOf("192.168.1.5"),
                "box.tailnet.ts.net" to listOf("100.101.102.103"),
                "www.youtube.com" to listOf("142.250.80.46", "2607:f8b0:4004:c1b:0:0:0:5e"),
            ),
        )
        assertTrue(g.needsPermission("https://nas.example.org/video.mp4"))
        assertTrue(g.needsPermission("https://192-168-1-5.abcdef.plex.direct:32400/library/parts/1/file.mkv"))
        assertTrue(g.needsPermission("http://box.tailnet.ts.net:8096/x.mp4"))
        assertFalse(g.needsPermission("https://www.youtube.com/watch?v=abc"))
    }

    @Test fun `a name that does not resolve, or whose lookup throws, is not local and the download goes on`() = runTest {
        assertFalse(gateWithDns(granted = false, dns = emptyMap()).needsPermission("https://nothing.example.org/x.mp4"))
        val throwing = LocalNetworkGate(granted = { false }, proxy = { null }, resolve = { throw java.net.UnknownHostException(it) })
        assertFalse(throwing.needsPermission("https://nothing.example.org/x.mp4"))
        assertFalse(throwing.guard.blocks("https://nothing.example.org/x.mp4", emptyList()))
    }

    @Test fun `once the permission is granted nothing is resolved at all`() = runTest {
        val asked = mutableListOf<String>()
        val g = gateWithDns(granted = true, dns = mapOf("nas.example.org" to listOf("192.168.1.5")), asked = asked)
        assertFalse(g.needsPermission("https://nas.example.org/x.mp4"))
        assertFalse(g.guard.blocks("https://nas.example.org/x.mp4", emptyList()))
        assertEquals(emptyList(), asked)
    }

    @Test fun `only a name the shape cannot place is resolved`() = runTest {
        val asked = mutableListOf<String>()
        val g = gateWithDns(granted = false, dns = emptyMap(), asked = asked)
        for (url in listOf("http://nas/x", "http://printer.local/x", "http://192.168.1.5/x", "https://8.8.8.8/x", "http://localhost/x", "http://[fe80::1]/x", "ytsearch:cats", "")) {
            g.needsPermission(url)
        }
        assertEquals(emptyList(), asked, "addresses, LAN names, loopback and non-URLs need no lookup")
        g.needsPermission("https://example.com/x")
        assertEquals(listOf("example.com"), asked)
    }

    @Test fun `with a proxy it is the proxy's name that is resolved, not the link's`() = runTest {
        val asked = mutableListOf<String>()
        val dns = mapOf("proxy.example.org" to listOf("192.168.1.2"), "www.youtube.com" to listOf("142.250.80.46"))
        val g = gateWithDns(granted = false, dns = dns, asked = asked, proxy = "http://proxy.example.org:3128")
        assertTrue(g.needsPermission("https://www.youtube.com/watch?v=abc")) // the phone talks to the proxy, and the proxy is on the LAN
        assertEquals(listOf("proxy.example.org"), asked)
        // the guard judges a queued row by the --proxy it carries
        assertFalse(g.guard.blocks("https://www.youtube.com/watch?v=abc", listOf("-f", "best")))
        assertTrue(g.guard.blocks("https://www.youtube.com/watch?v=abc", listOf("--proxy", "http://proxy.example.org:3128")))
    }

    @Test fun `the guard names the block for a failed row whose public-looking host is on the LAN`() = runTest {
        val g = gateWithDns(granted = false, dns = mapOf("nas.example.org" to listOf("10.0.0.7")))
        assertTrue(g.guard.blocks("https://nas.example.org/x.mp4", listOf("-f", "best")))
        assertFalse(g.guard.blocks("https://other.example.org/x.mp4", listOf("-f", "best")))
    }

    // ---- a globally routable IPv6 address that the connected network holds on-link (DDNS, split-horizon, a literal) ----------

    /** The Wi-Fi holds [onLink] on-link ([OnLinkNetworks] decides per address, as in the app); every address the gate asks about is recorded. */
    private fun gateOnLink(
        granted: Boolean,
        onLink: List<OnLinkPrefix>,
        asked: MutableList<String> = mutableListOf(),
        dns: Map<String, List<String>> = emptyMap(),
        proxy: String? = null,
        resolved: MutableList<String> = mutableListOf(),
    ): LocalNetworkGate {
        val network = OnLinkNetworks { onLink }
        return LocalNetworkGate(
            granted = { granted },
            proxy = { proxy },
            resolve = { host -> resolved += host; dns[host].orEmpty() },
            onLink = { address -> asked += address; network.isOnLink(address) },
        )
    }

    private val wifi = listOf(OnLinkPrefix("2001:db8:1:0:0:0:0:0", 64)) // 2001:db8:1::/64 is on-link on the Wi-Fi

    @Test fun `a literal global IPv6 address needs the permission when the network holds it on-link, and only then`() = runTest {
        val asked = mutableListOf<String>()
        val g = gateOnLink(granted = false, onLink = wifi, asked = asked)
        assertTrue(g.needsPermission("http://[2001:db8:1::5]:8000/x.mp4"))
        assertFalse(g.needsPermission("http://[2606:4700:4700::1111]/x.mp4")) // a public address: not on-link
        assertEquals(listOf("[2001:db8:1::5]", "[2606:4700:4700::1111]"), asked)
    }

    @Test fun `a name that resolves to an on-link global IPv6 address needs the permission, an off-link one does not`() = runTest {
        val dns = mapOf(
            "nas.example.org" to listOf("93.184.216.34", "2001:db8:1:0:0:0:0:5"), // DDNS: the AAAA record is the NAS on the Wi-Fi's /64
            "www.youtube.com" to listOf("142.250.80.46", "2607:f8b0:4004:c1b:0:0:0:5e"),
        )
        val g = gateOnLink(granted = false, onLink = wifi, dns = dns)
        assertTrue(g.needsPermission("https://nas.example.org/video.mp4"))
        assertFalse(g.needsPermission("https://www.youtube.com/watch?v=abc"))
    }

    @Test fun `the guard names the block for a failed row whose host is an on-link global IPv6 address`() = runTest {
        val g = gateOnLink(granted = false, onLink = wifi, dns = mapOf("nas.example.org" to listOf("2001:db8:1:0:0:0:0:5")))
        assertTrue(g.guard.blocks("http://[2001:db8:1::5]:8000/x.mp4", listOf("-f", "best")))
        assertFalse(g.guard.blocks("http://[2001:db8:9::5]:8000/x.mp4", listOf("-f", "best")))
        assertTrue(g.guard.blocks("https://nas.example.org/x.mp4", listOf("-f", "best"))) // a name whose AAAA record is on-link
    }

    @Test fun `a proxy on an on-link global IPv6 address needs it, even in front of an internet link`() = runTest {
        val g = gateOnLink(granted = false, onLink = wifi, proxy = "http://[2001:db8:1::2]:3128")
        assertTrue(g.needsPermission("https://www.youtube.com/watch?v=abc"))
        assertTrue(g.guard.blocks("https://www.youtube.com/watch?v=abc", listOf("--proxy", "http://[2001:db8:1::2]:3128")))
        assertFalse(g.guard.blocks("https://www.youtube.com/watch?v=abc", listOf("-f", "best"))) // a queued row is judged by its own --proxy
    }

    @Test fun `once the permission is granted the network is not even read`() = runTest {
        val asked = mutableListOf<String>()
        val resolved = mutableListOf<String>()
        val g = gateOnLink(granted = true, onLink = wifi, asked = asked, resolved = resolved, dns = mapOf("nas.example.org" to listOf("2001:db8:1:0:0:0:0:5")))
        assertFalse(g.needsPermission("http://[2001:db8:1::5]:8000/x.mp4"))
        assertFalse(g.needsPermission("https://nas.example.org/x.mp4"))
        assertFalse(g.guard.blocks("http://[2001:db8:1::5]:8000/x.mp4", emptyList()))
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), resolved)
    }

    @Test fun `the network is asked only about global IPv6 addresses`() = runTest {
        val asked = mutableListOf<String>()
        val g = gateOnLink(
            granted = false, onLink = wifi, asked = asked,
            dns = mapOf("example.com" to listOf("93.184.216.34"), "v4only.example.org" to listOf("8.8.8.8")),
        )
        for (url in listOf(
            "http://nas/x", "http://192.168.1.5/x", "https://8.8.8.8/x", "http://localhost/x", "http://[::1]/x", "http://[fe80::1]/x", "http://[fd00::1]/x",
            "http://[ff02::fb]/x", "https://example.com/x", "https://v4only.example.org/x", "ytsearch:cats", "",
        )) g.needsPermission(url)
        assertEquals(emptyList(), asked, "IPv4, link-local, unique-local, loopback and names that resolve to IPv4 are settled without the network")
    }

    @Test fun `a network that cannot be read is not local and the download goes on`() = runTest {
        val g = LocalNetworkGate(granted = { false }, proxy = { null }, onLink = { throw SecurityException("no ACCESS_NETWORK_STATE") })
        assertFalse(g.needsPermission("http://[2001:db8:1::5]:8000/x.mp4"))
        assertFalse(g.guard.blocks("http://[2001:db8:1::5]:8000/x.mp4", emptyList()))
    }
}
