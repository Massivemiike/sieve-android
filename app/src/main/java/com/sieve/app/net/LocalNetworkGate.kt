package com.sieve.app.net

import com.sieve.engine.site.LocalNetwork
import com.sieve.queue.service.LocalNetworkGuard
import kotlinx.coroutines.CancellationException

/**
 * The two questions Android 17's local-network permission raises, answered from plain functions so they are JVM-tested:
 *
 *  * [needsPermission] (the Download screen, before it reads or queues a link): would this download run into the block?
 *    Then the permission is asked for FIRST, at the point of use, instead of the download timing out.
 *  * [guard] (the queue, after a download failed): is the block what is happening? Then the job fails once with that cause
 *    ([com.sieve.engine.parse.YtdlpErrors.localNetworkBlocked]) and is not retried, instead of being reported as a flaky network.
 *
 * [granted] is "connections to the local network are allowed right now" ([LocalNetworkAccess.isGranted]; always true below
 * Android 17). [proxy] is the Settings proxy as it will be baked into a NEW download. A queued row is judged by the `--proxy`
 * it carries, never by today's setting.
 *
 * [resolve] looks a host name up and returns its addresses as text (empty when it does not resolve or the lookup is too slow;
 * [HostLookup] is the real one). The OS blocks by address, not by name, so a name that is public by its shape (split-horizon DNS,
 * Plex, Tailscale) can still be on the LAN: such a name ([LocalNetwork.needsLookup]) is resolved and its addresses judged. The
 * lookup only runs while the permission is missing: once it is granted, or below Android 17, nothing is resolved and nothing is asked.
 *
 * [onLink] answers for a globally routable IPv6 address (a literal in the link, or what a name resolved to): the platform counts
 * a global address whose prefix the Wi-Fi or Ethernet network holds on-link as the local network, which no shape rule can see
 * ([OnLinkNetworks] reads the connected networks). It is asked only for such addresses and, like the lookup, only while the
 * permission is required and missing; a failing answer counts as "not local".
 */
class LocalNetworkGate(
    private val granted: () -> Boolean,
    private val proxy: suspend () -> String?,
    private val resolve: suspend (host: String) -> List<String> = { emptyList() },
    private val onLink: (address: String) -> Boolean = { false },
) {
    suspend fun needsPermission(url: String?): Boolean =
        !granted() && reachesLocalNetwork(url, proxy())

    val guard: LocalNetworkGuard = LocalNetworkGuard { url, args ->
        !granted() && reachesLocalNetwork(url, LocalNetwork.proxyOfArgs(args))
    }

    /**
     * Local by its shape, else (a name that only a lookup can place) by where it points, else (a global IPv6 address) by the
     * prefixes the connected network holds on-link. A failing lookup, or a failing on-link read, is "not local".
     */
    private suspend fun reachesLocalNetwork(url: String?, proxy: String?): Boolean {
        if (LocalNetwork.connectsToLocalNetwork(url, proxy)) return true
        val host = LocalNetwork.connectionHost(url, proxy) ?: return false
        if (LocalNetwork.isGlobalIpv6(host)) return isOnLink(host)
        if (!LocalNetwork.needsLookup(host)) return false
        val addresses = try {
            resolve(host)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return LocalNetwork.anyLocalAddress(addresses) || addresses.any { LocalNetwork.isGlobalIpv6(it) && isOnLink(it) }
    }

    private fun isOnLink(address: String): Boolean = try {
        onLink(address)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
