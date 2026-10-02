package com.sieve.engine.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which connections Android 17 treats as local-network traffic (developer.android.com/privacy-and-security/local-network-permission):
 * the documented IPv4 and IPv6 ranges at both edges, loopback and public hosts as the negatives, names, and the proxy rule.
 */
class LocalNetworkTest {

    private fun local(host: String) = assertTrue(LocalNetwork.isLocalHost(host), "$host should be local")
    private fun notLocal(host: String) = assertFalse(LocalNetwork.isLocalHost(host), "$host should not be local")

    // ---- IPv4: every documented range, both edges ----------------------------------------------

    @Test fun `10 slash 8`() {
        local("10.0.0.0"); local("10.255.255.255"); local("10.1.2.3")
        notLocal("9.255.255.255"); notLocal("11.0.0.0")
    }

    @Test fun `172 16 slash 12`() {
        local("172.16.0.0"); local("172.31.255.255"); local("172.20.1.1")
        notLocal("172.15.255.255"); notLocal("172.32.0.0")
    }

    @Test fun `192 168 slash 16`() {
        local("192.168.0.1"); local("192.168.255.255")
        notLocal("192.167.255.255"); notLocal("192.169.0.0")
    }

    @Test fun `169 254 slash 16 link-local`() {
        local("169.254.0.1"); local("169.254.255.255")
        notLocal("169.253.255.255"); notLocal("169.255.0.0")
    }

    @Test fun `100 64 slash 10 carrier-grade NAT`() {
        local("100.64.0.0"); local("100.127.255.255")
        notLocal("100.63.255.255"); notLocal("100.128.0.0")
    }

    @Test fun `multicast 224 slash 4 and the broadcast address`() {
        local("224.0.0.1"); local("239.255.255.255"); local("255.255.255.255")
        notLocal("223.255.255.255"); notLocal("240.0.0.1"); notLocal("255.255.255.254")
    }

    @Test fun `loopback and public IPv4 are not local`() {
        for (h in listOf("127.0.0.1", "127.255.255.254", "0.0.0.0", "8.8.8.8", "1.1.1.1", "93.184.216.34")) notLocal(h)
    }

    @Test fun `a malformed dotted quad is neither an address nor a name`() {
        notLocal("999.1.1.1")
        notLocal("1.2.3.4.5")
        notLocal("10.0.0")
    }

    // ---- IPv6 ---------------------------------------------------------------------------------

    @Test fun `IPv6 link-local fe80 slash 10`() {
        local("fe80::1"); local("febf:ffff::1"); local("FE80::ABCD"); local("fe80::1%wlan0"); local("[fe80::1]")
        notLocal("fec0::1"); notLocal("fe00::1")
    }

    @Test fun `IPv6 unique-local fc00 slash 7`() {
        local("fc00::1"); local("fd12:3456:789a::1"); local("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        notLocal("fb00::1"); notLocal("fe00::")
    }

    @Test fun `IPv6 multicast ff00 slash 8`() {
        local("ff00::1"); local("ff02::1"); local("ffff::")
        notLocal("fe7f::1")
    }

    @Test fun `IPv6 loopback unspecified and global are not local`() {
        notLocal("::1"); notLocal("::"); notLocal("2001:db8::1"); notLocal("2606:4700:4700::1111"); notLocal("[::1]")
    }

    @Test fun `an IPv4-mapped IPv6 address is judged by its IPv4 part`() {
        local("::ffff:192.168.1.1"); local("::ffff:10.0.0.5"); local("::ffff:c0a8:0101")
        notLocal("::ffff:8.8.8.8"); notLocal("::ffff:127.0.0.1")
    }

    @Test fun `a malformed IPv6 literal is not local and does not throw`() {
        for (h in listOf("fe80:::1", "fe80::1::2", "1:2:3:4:5:6:7:8:9", "gggg::1", "fe80::12345", "::ffff:1.2.3", "1:2:3")) notLocal(h)
    }

    // ---- names ---------------------------------------------------------------------------------

    @Test fun `single-label names are local`() {
        local("nas"); local("NAS"); local("jellyfin"); local("printer")
    }

    @Test fun `localhost is loopback, not local network`() {
        notLocal("localhost"); notLocal("LOCALHOST"); notLocal("app.localhost")
    }

    @Test fun `mDNS and the reserved private suffixes are local, an absolute trailing dot too`() {
        local("printer.local"); local("Printer.LOCAL."); local("box.localdomain"); local("router.lan"); local("hub.home.arpa"); local("db.internal")
    }

    @Test fun `public names are not local`() {
        for (h in listOf("example.com", "www.youtube.com", "youtu.be", "a.b.c.example.org", "locally.com", "notlocal.example", "lan.example.com")) notLocal(h)
    }

    @Test fun `blank and null are not local`() {
        notLocal(""); notLocal("   ")
        assertFalse(LocalNetwork.isLocalHost(null))
    }

    // ---- hostOf --------------------------------------------------------------------------------

    @Test fun `hostOf takes the host of a link or proxy`() {
        assertEquals("nas", LocalNetwork.hostOf("https://nas/video.mp4"))
        assertEquals("192.168.1.20", LocalNetwork.hostOf("http://192.168.1.20:8096/Videos/1/stream.mp4?api_key=x"))
        assertEquals("192.168.1.5", LocalNetwork.hostOf("socks5://user:pa:ss@192.168.1.5:1080"))
        assertEquals("127.0.0.1", LocalNetwork.hostOf("socks5://127.0.0.1:1080"))
        assertEquals("[fe80::1]", LocalNetwork.hostOf("http://[fe80::1]:8080/x"))
        assertEquals("example.com", LocalNetwork.hostOf("  https://example.com#frag  "))
        assertEquals("host.local", LocalNetwork.hostOf("HTTP://host.local"))
    }

    @Test fun `hostOf accepts a scheme-less host with a path`() {
        assertEquals("nas", LocalNetwork.hostOf("nas/video.mp4"))
        assertEquals("nas", LocalNetwork.hostOf("nas:8096/x"))
        assertEquals("youtube.com", LocalNetwork.hostOf("youtube.com/watch?v=abc"))
        assertEquals("[::1]", LocalNetwork.hostOf("[::1]:80/a"))
    }

    @Test fun `hostOf is null for what is not a URL`() {
        for (t in listOf(null, "", "   ", "ytsearch:cats", "magnet:?xt=urn:btih:abc", "just words", "cats", "http://", "https:///path", "a b/c")) {
            assertNull(LocalNetwork.hostOf(t), "hostOf($t)")
        }
    }

    @Test fun `a search or magnet pseudo-URL never reads as a single-label LAN host`() {
        assertFalse(LocalNetwork.connectsToLocalNetwork("ytsearch:cats"))
        assertFalse(LocalNetwork.connectsToLocalNetwork("magnet:?xt=urn:btih:abc"))
        assertFalse(LocalNetwork.connectsToLocalNetwork("cats"))
    }

    // ---- the connection a download makes ------------------------------------------------------

    @Test fun `without a proxy the link's host decides`() {
        assertTrue(LocalNetwork.connectsToLocalNetwork("http://nas:8096/Videos/1.mp4"))
        assertTrue(LocalNetwork.connectsToLocalNetwork("https://192.168.0.10/x.mp4", null))
        assertTrue(LocalNetwork.connectsToLocalNetwork("https://printer.local/x", ""))
        assertFalse(LocalNetwork.connectsToLocalNetwork("https://www.youtube.com/watch?v=abc"))
        assertFalse(LocalNetwork.connectsToLocalNetwork("http://localhost:8080/x.mp4"))
        assertFalse(LocalNetwork.connectsToLocalNetwork("http://127.0.0.1:8080/x.mp4"))
    }

    @Test fun `with a proxy the proxy decides, not the link`() {
        // a LAN link behind an internet proxy: the phone only talks to the proxy
        assertFalse(LocalNetwork.connectsToLocalNetwork("http://nas/x.mp4", "http://proxy.example.com:3128"))
        // a public link through a proxy on the LAN: the connection is local
        assertTrue(LocalNetwork.connectsToLocalNetwork("https://www.youtube.com/watch?v=abc", "http://192.168.1.2:3128"))
        // the hint's own loopback proxy is not local network
        assertFalse(LocalNetwork.connectsToLocalNetwork("http://nas/x.mp4", "socks5://127.0.0.1:1080"))
    }

    @Test fun `a proxy value with no host counts as no proxy`() {
        assertTrue(LocalNetwork.connectsToLocalNetwork("http://nas/x.mp4", "not a proxy"))
        assertFalse(LocalNetwork.connectsToLocalNetwork("https://example.com/x", "   "))
    }

    // ---- names the shape cannot classify: look them up ---------------------------------------------

    @Test fun `connectionHost is the proxy's host when a proxy is set, else the link's`() {
        assertEquals("192.168.1.2", LocalNetwork.connectionHost("https://www.youtube.com/watch?v=abc", "http://192.168.1.2:3128"))
        assertEquals("www.youtube.com", LocalNetwork.connectionHost("https://www.youtube.com/watch?v=abc", null))
        assertEquals("www.youtube.com", LocalNetwork.connectionHost("https://www.youtube.com/watch?v=abc", "not a proxy"))
        assertNull(LocalNetwork.connectionHost("ytsearch:cats", null))
    }

    @Test fun `a public-looking name needs a lookup, an address or a LAN name does not`() {
        for (h in listOf("nas.example.org", "www.youtube.com", "192-168-1-5.abcdef.plex.direct", "host.tailnet.ts.net", "Example.COM.")) {
            assertTrue(LocalNetwork.needsLookup(h), "$h needs a lookup")
        }
        for (h in listOf("192.168.1.5", "8.8.8.8", "999.1.1.1", "[fe80::1]", "fe80::1%wlan0", "2001:db8::1")) {
            assertFalse(LocalNetwork.needsLookup(h), "$h is an address literal: nothing to look up")
        }
        for (h in listOf("nas", "printer.local", "router.lan", "hub.home.arpa")) {
            assertFalse(LocalNetwork.needsLookup(h), "$h is local by shape already")
        }
        for (h in listOf("localhost", "api.localhost", "LOCALHOST.")) {
            assertFalse(LocalNetwork.needsLookup(h), "$h is loopback")
        }
        assertFalse(LocalNetwork.needsLookup(null))
        assertFalse(LocalNetwork.needsLookup(""))
        assertFalse(LocalNetwork.needsLookup("   "))
    }

    @Test fun `a name that resolves to a LAN address is local, one that resolves to the internet or to nothing is not`() {
        assertTrue(LocalNetwork.anyLocalAddress(listOf("192.168.1.5")))
        assertTrue(LocalNetwork.anyLocalAddress(listOf("100.101.102.103"))) // Tailscale, inside 100.64/10
        assertTrue(LocalNetwork.anyLocalAddress(listOf("93.184.216.34", "10.0.0.7"))) // one local answer is enough
        assertTrue(LocalNetwork.anyLocalAddress(listOf("fe80:0:0:0:a:b:c:d%wlan0"))) // as InetAddress.hostAddress prints it
        assertTrue(LocalNetwork.anyLocalAddress(listOf("fd12:3456:789a:0:0:0:0:1")))
        assertFalse(LocalNetwork.anyLocalAddress(listOf("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946")))
        assertFalse(LocalNetwork.anyLocalAddress(listOf("127.0.0.1", "0:0:0:0:0:0:0:1", "0.0.0.0")))
        assertFalse(LocalNetwork.anyLocalAddress(emptyList()))
    }

    // ---- a globally routable IPv6 address can be on-link: the shape alone cannot tell, the network's prefixes can --------------

    @Test fun `a global unicast IPv6 literal is public by its shape, and is the one kind that may still be on-link`() {
        for (h in listOf("2001:db8::1", "[2606:4700:4700::1111]", "2001:db8:1:0:0:0:0:5", "2a00:1450:4001:81b::200e", "3fff::1", "2000::", "[2001:db8::1]")) {
            assertTrue(LocalNetwork.isGlobalIpv6(h), "$h is global unicast")
            notLocal(h) // the string rules read it as public: only the connected networks can say it is on-link
        }
    }

    @Test fun `loopback, link-local, unique-local, multicast, IPv4 and names are not global IPv6`() {
        for (h in listOf("::1", "::", "fe80::1", "fe80::1%wlan0", "fd12:3456:789a::1", "fc00::1", "ff02::fb", "::ffff:8.8.8.8", "::ffff:192.168.1.5", "4000::1", "1fff::1",
            "8.8.8.8", "192.168.1.5", "nas", "example.com", "2001:db8::zz", "2001:db8:::1", "", "   ")) {
            assertFalse(LocalNetwork.isGlobalIpv6(h), "$h is not global unicast IPv6")
        }
        assertFalse(LocalNetwork.isGlobalIpv6(null))
    }

    @Test fun `an address is inside a prefix when its leading bits agree`() {
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8:1::5", "2001:db8:1::", 64))
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8:1:0:ffff:ffff:ffff:ffff", "2001:db8:1:0:0:0:0:0", 64)) // the last address of the /64, as hostAddress prints it
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8:2::5", "2001:db8:1::", 64))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8:1:1::5", "2001:db8:1::", 64)) // the next /64
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8:1:1::5", "2001:db8:1::", 48))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8:2:1::5", "2001:db8:1::", 48))
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8:1::5", "2001:db8:1::5", 128))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8:1::6", "2001:db8:1::5", 128))
    }

    @Test fun `a prefix length that is not a multiple of 16 is honoured bit by bit`() {
        // 2001:db8:0:2::/63 holds 2001:db8:0:2:: and 2001:db8:0:3::, not 2001:db8:0:4:: or 2001:db8:0:1::
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8:0:2::1", "2001:db8:0:2::", 63))
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8:0:3::1", "2001:db8:0:2::", 63))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8:0:4::1", "2001:db8:0:2::", 63))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8:0:1::1", "2001:db8:0:2::", 63))
        // a /60 (the typical delegated size) holds sixteen /64s
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8:0:f::1", "2001:db8:0:0::", 60))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8:0:10::1", "2001:db8:0:0::", 60))
        // 1 and 127
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8::1", "2000::", 3))
        assertFalse(LocalNetwork.ipv6InPrefix("4001:db8::1", "2000::", 3))
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8::1", "2001:db8::", 127))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8::2", "2001:db8::", 127))
    }

    @Test fun `the host bits of the prefix text are ignored and brackets and a zone id are tolerated`() {
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8:1::5", "2001:db8:1:0:0:0:0:99", 64))
        assertTrue(LocalNetwork.ipv6InPrefix("[2001:db8:1::5]", "2001:db8:1::%wlan0", 64))
        assertTrue(LocalNetwork.ipv6InPrefix("2001:DB8:1::5", "2001:db8:1::", 64))
    }

    @Test fun `a length of 0 holds everything and a length outside 0 to 128 holds nothing`() {
        assertTrue(LocalNetwork.ipv6InPrefix("2001:db8::1", "::", 0))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8::1", "2001:db8::", -1))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8::1", "2001:db8::", 129))
    }

    @Test fun `something that is not an IPv6 literal is never inside a prefix`() {
        assertFalse(LocalNetwork.ipv6InPrefix("192.168.1.5", "2001:db8::", 64))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8::1", "192.168.1.0", 24))
        assertFalse(LocalNetwork.ipv6InPrefix("nas", "2001:db8::", 64))
        assertFalse(LocalNetwork.ipv6InPrefix(null, "2001:db8::", 64))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8::1", null, 64))
        assertFalse(LocalNetwork.ipv6InPrefix("2001:db8::zz", "2001:db8::", 64))
    }

    // ---- proxyOfArgs ----------------------------------------------------------------------------

    @Test fun `proxyOfArgs reads the proxy flag in both spellings`() {
        assertEquals("socks5://192.168.1.5:1080", LocalNetwork.proxyOfArgs(listOf("-f", "best", "--proxy", "socks5://192.168.1.5:1080", "-N", "4")))
        assertEquals("http://p:3128", LocalNetwork.proxyOfArgs(listOf("--proxy=http://p:3128")))
        assertNull(LocalNetwork.proxyOfArgs(listOf("-f", "best")))
        assertNull(LocalNetwork.proxyOfArgs(listOf("--proxy")))
        assertNull(LocalNetwork.proxyOfArgs(listOf("--proxy", "")))
        assertNull(LocalNetwork.proxyOfArgs(emptyList()))
    }
}
