package com.sieve.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * Resolves a host name for [LocalNetworkGate]: the addresses it points at, as text, or an empty list when the name does not
 * resolve, the lookup fails, or it takes longer than [timeoutMs]. It never throws. The system resolver does the work (DNS to
 * port 53 is not part of the local-network block); a lookup that is too slow is left to finish on its own thread while the
 * caller moves on, because a blocking `InetAddress` call cannot be interrupted.
 */
class HostLookup(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val lookup: (host: String) -> List<String> = ::systemLookup,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    suspend fun addresses(host: String): List<String> {
        val pending = scope.async { runCatching { lookup(host) }.getOrDefault(emptyList()) }
        return withTimeoutOrNull(timeoutMs) { pending.await() } ?: emptyList()
    }

    companion object {
        /** A DNS answer normally arrives in tens of milliseconds; this is the longest a tap on Analyze or Download waits for one. */
        const val TIMEOUT_MS = 1_500L

        /** Every address of [host], as `InetAddress.hostAddress` prints it (an IPv6 scope id comes after a `%`). */
        fun systemLookup(host: String): List<String> = InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
    }
}
