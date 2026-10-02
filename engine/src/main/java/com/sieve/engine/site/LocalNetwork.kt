package com.sieve.engine.site

/**
 * Which hosts Android 17's "local network protection" treats as the local network (a pure, string-level classifier, so
 * every rule is JVM-tested).
 *
 * An app that targets API 37 can only open connections to LAN addresses once the user has granted it
 * `android.permission.ACCESS_LOCAL_NETWORK` (the NEARBY_DEVICES group). The block sits in the network stack, so it covers
 * every socket of the app's UID, including the yt-dlp child process. TCP then times out and UDP fails with EPERM; the
 * app's own loopback traffic (`127.0.0.1`, `localhost`), DNS to port 53 and the internet are not affected
 * (developer.android.com/privacy-and-security/local-network-permission).
 *
 * What counts as local, as documented: IPv4 `169.254/16`, `100.64/10`, `10/8`, `172.16/12`, `192.168/16`, `224/4` and
 * `255.255.255.255`; IPv6 link-local, unique-local (the usual "stub network"), multicast `ff00::/8`; and `.local` names (mDNS).
 * The platform's definition also counts IPv6 "directly-connected routes (on-link)", and that includes a GLOBALLY routable address
 * whose prefix the Wi-Fi or Ethernet network has on-link (a host on the same /64 reached by its public address, a DDNS or
 * split-horizon name whose AAAA record is such an address). The shape of an address cannot show that, because it depends on the
 * prefixes of the network the phone is on: [isGlobalIpv6] says when the question arises and [ipv6InPrefix] answers it for one
 * prefix, which a caller that can read the network's `LinkProperties` supplies (`OnLinkNetworks` in :app).
 * A name that is not an address cannot be classified without resolving it, so the rule is deliberately generous: a
 * single-label name (`nas`, `jellyfin`) and the reserved private suffixes ([PRIVATE_SUFFIXES]) are treated as local. A wrong
 * "local" only costs one permission prompt; a wrong "public" would be a LAN download that times out with no explanation.
 * The one case the shape cannot show is a public-looking name that resolves to a LAN address (split-horizon DNS, Plex, Tailscale):
 * [needsLookup] says when a caller that can resolve names has to, and [anyLocalAddress] reads the answer.
 *
 * [hostOf] / [connectsToLocalNetwork] are what the callers use; the proxy rule is in the latter's documentation.
 */
object LocalNetwork {

    /** Names under these suffixes are LAN names by convention (RFC 6762 mDNS, RFC 8375, the reserved `.internal`, and the router defaults). */
    private val PRIVATE_SUFFIXES = listOf(".local", ".localdomain", ".lan", ".home.arpa", ".internal")

    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")

    /** What a scheme-less `host[:port]/path` may have as its authority: a plain host or a bracketed IPv6 literal, an optional numeric port. */
    private val SCHEMELESS_AUTHORITY = Regex("""^(?:\[[0-9A-Za-z:.%]+]|[^\s:/?#@\[\]]+)(?::\d*)?$""")

    /**
     * The host of a download link or a proxy setting: `https://nas/x.mp4` -> `nas`, `socks5://u:p@192.168.1.5:1080` ->
     * `192.168.1.5`, `http://[fe80::1]:8080/` -> `[fe80::1]`, `nas:8096/x` -> `nas`. Null when there is none: blank, text with
     * spaces, or something that is not a URL at all (`ytsearch:cats`, a bare word) — those never reach a socket as written.
     */
    fun hostOf(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val hasScheme = SCHEME.containsMatchIn(t)
        val rest = if (hasScheme) t.substring(t.indexOf("://") + 3) else t
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        // A scheme-less value needs a path after the authority, or `ytsearch:cats`, `magnet:?xt=...` and a lone word would read as hosts.
        if (!hasScheme && rest.getOrNull(authority.length) != '/') return null
        val hostPort = authority.substringAfterLast('@')
        if (!hasScheme && !SCHEMELESS_AUTHORITY.matches(hostPort)) return null
        val host = if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            hostPort.substring(0, close + 1)
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon >= 0 && hostPort.substring(colon + 1).all { it in '0'..'9' }) hostPort.substring(0, colon) else hostPort
        }
        return host.ifEmpty { null }
    }

    /** True when connecting to [host] is local-network traffic (see the class comment). Loopback, public names and addresses are not. */
    fun isLocalHost(host: String?): Boolean {
        val h = normalized(host)
        if (h.isEmpty()) return false
        if (':' in h) return ipv6(h)?.let(::ipv6IsLocal) ?: false
        ipv4(h)?.let { return ipv4IsLocal(it) }
        if (DOTTED_QUAD_SHAPE.matches(h)) return false // `999.1.1.1`: not an address and not a name that resolves
        if (h == "localhost" || h.endsWith(".localhost")) return false // loopback, never blocked
        if ('.' !in h) return true // a single-label name: `nas`, `printer`
        return PRIVATE_SUFFIXES.any { h.endsWith(it) }
    }

    /**
     * Whether the connection a download makes is local-network traffic. With a proxy set, yt-dlp connects to the PROXY (the
     * link is only named inside the proxied request), so the proxy host decides and a LAN link behind an internet proxy is
     * not local; without one the link's own host decides. A proxy value that has no host (blank, junk) counts as no proxy.
     */
    fun connectsToLocalNetwork(url: String?, proxy: String? = null): Boolean = isLocalHost(connectionHost(url, proxy))

    /** The host the socket of a download goes to: the proxy's when a proxy is set, else the link's. Null when there is none. */
    fun connectionHost(url: String?, proxy: String? = null): String? = hostOf(proxy) ?: hostOf(url)

    /**
     * True when only a DNS lookup can tell whether connecting to [host] is local-network traffic: a name (not an address
     * literal, not loopback) that the rules of [isLocalHost] read as public. A public-looking name can still point at a LAN
     * address: split-horizon DNS (`nas.example.org` answering `192.168.1.5` at home), a Plex `<ip>.<hash>.plex.direct` name, a
     * Tailscale `.ts.net` name (`100.64/10` is on the list). The OS blocks by the address, not by the name, so the caller
     * resolves such a name and passes the answers to [anyLocalAddress]. Names that are already local by shape need no lookup.
     */
    fun needsLookup(host: String?): Boolean {
        val h = normalized(host)
        if (h.isEmpty() || ':' in h) return false // nothing to look up, or an IPv6 literal
        if (DOTTED_QUAD_SHAPE.matches(h)) return false // an IPv4 literal (or junk that is not a name either)
        if (h == "localhost" || h.endsWith(".localhost")) return false // loopback, never blocked
        return !isLocalHost(h)
    }

    /** True when any of the [addresses] a name resolved to (as `InetAddress.hostAddress` prints them) is local-network traffic. */
    fun anyLocalAddress(addresses: List<String>): Boolean = addresses.any(::isLocalHost)

    /**
     * True for an IPv6 literal in the global unicast range `2000::/3` (brackets and a zone id are ignored). [isLocalHost] reads such an
     * address as public, and it is, unless the network the phone is on holds its prefix on-link: whether it does is not a property
     * of the string, so a caller that can read the connected networks asks [ipv6InPrefix] for each on-link prefix.
     */
    fun isGlobalIpv6(host: String?): Boolean {
        val h = normalized(host)
        if (':' !in h) return false
        val g = ipv6(h) ?: return false
        return (g[0] and 0xE000) == 0x2000
    }

    /**
     * True when the IPv6 address [address] lies inside [prefix]/[length] (`2001:db8:1::5` is inside `2001:db8:1::`/64). False when
     * either is not an IPv6 literal or [length] is not 0..128. A length of 0 holds every address: a caller that is after on-link
     * prefixes leaves the default route out.
     */
    fun ipv6InPrefix(address: String?, prefix: String?, length: Int): Boolean {
        if (length !in 0..128) return false
        val a = ipv6Of(address) ?: return false
        val p = ipv6Of(prefix) ?: return false
        var bits = length
        for (i in 0 until 8) {
            if (bits <= 0) break
            val take = minOf(16, bits)
            val mask = (0xFFFF shl (16 - take)) and 0xFFFF
            if ((a[i] and mask) != (p[i] and mask)) return false
            bits -= take
        }
        return true
    }

    private fun ipv6Of(text: String?): IntArray? = normalized(text).takeIf { ':' in it }?.let(::ipv6)

    /** The value of `--proxy` in a yt-dlp argument list (`--proxy X` or `--proxy=X`), or null when there is none. */
    fun proxyOfArgs(args: List<String>): String? {
        val i = args.indexOf("--proxy")
        if (i >= 0 && i + 1 < args.size) return args[i + 1].ifBlank { null }
        return args.firstOrNull { it.startsWith("--proxy=") }?.substringAfter('=')?.ifBlank { null }
    }

    private val DOTTED_QUAD_SHAPE = Regex("""^\d+\.\d+\.\d+\.\d+$""")

    /** Lower-cased, without the brackets of an IPv6 literal, an IPv6 zone id (`fe80::1%wlan0`) or the root dot of an absolute name. */
    private fun normalized(host: String?): String {
        var h = host?.trim()?.lowercase().orEmpty()
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        return h.substringBefore('%').trimEnd('.')
    }

    private fun ipv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val n = p.toInt()
            if (n > 255) return null
            out[i] = n
        }
        return out
    }

    private fun ipv4IsLocal(o: IntArray): Boolean {
        val a = o[0]
        val b = o[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127) ||
            a in 224..239 ||
            (o.all { it == 255 })
    }

    /** The eight 16-bit groups of an IPv6 literal (`::` expanded, a dotted IPv4 tail folded in), or null when it is not one. */
    private fun ipv6(h: String): IntArray? {
        var s = h
        var v4Tail: IntArray? = null
        if ('.' in s) {
            val cut = s.lastIndexOf(':')
            val v4 = ipv4(s.substring(cut + 1)) ?: return null
            v4Tail = intArrayOf((v4[0] shl 8) or v4[1], (v4[2] shl 8) or v4[3])
            s = s.substring(0, cut + 1) + "0:0" // two placeholder groups, replaced below
        }
        val halves = s.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map { g ->
                if (g.isEmpty() || g.length > 4) return null
                g.toIntOrNull(16) ?: return null
            }
        }
        val head = groups(halves[0]) ?: return null
        val out = IntArray(8)
        if (halves.size == 1) {
            if (head.size != 8) return null
            head.forEachIndexed { i, v -> out[i] = v }
        } else {
            val tail = groups(halves[1]) ?: return null
            if (head.size + tail.size > 7) return null
            head.forEachIndexed { i, v -> out[i] = v }
            tail.forEachIndexed { i, v -> out[8 - tail.size + i] = v }
        }
        if (v4Tail != null) {
            out[6] = v4Tail[0]
            out[7] = v4Tail[1]
        }
        return out
    }

    private fun ipv6IsLocal(g: IntArray): Boolean {
        // ::ffff:a.b.c.d is an IPv4 address in IPv6 clothes: it is local exactly when the IPv4 address is.
        if ((0..4).all { g[it] == 0 } && g[5] == 0xFFFF) {
            return ipv4IsLocal(intArrayOf(g[6] shr 8, g[6] and 0xFF, g[7] shr 8, g[7] and 0xFF))
        }
        return (g[0] and 0xFFC0) == 0xFE80 || // fe80::/10 link-local
            (g[0] and 0xFE00) == 0xFC00 || // fc00::/7 unique local (the usual stub network)
            (g[0] and 0xFF00) == 0xFF00 // ff00::/8 multicast
    }
}
