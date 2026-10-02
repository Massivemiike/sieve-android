package com.sieve.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.sieve.engine.site.LocalNetwork
import java.net.Inet6Address

/** An IPv6 prefix a connected network holds on-link: [address] (as `InetAddress.hostAddress` prints it) and its length in bits. */
class OnLinkPrefix(val address: String, val length: Int)

/**
 * Whether a globally routable IPv6 address is on the local network, which only the network the phone is on can say.
 *
 * Android 17 defines the local network by its routes, not by address ranges: "directly-connected routes (on-link)" of a
 * broadcast-capable network (Wi-Fi, Ethernet) count, and that includes global addresses. A NAS reached by `http://[2001:db8:1::5]:8000/`,
 * or by a DDNS name whose AAAA record is such an address, is on the LAN when the Wi-Fi's `2001:db8:1::/64` is on-link, and blocked
 * without the permission, yet [LocalNetwork.isLocalHost] reads the string as public. [LocalNetworkGate] asks [isOnLink] for exactly
 * those addresses ([LocalNetwork.isGlobalIpv6]), only while the permission is required and missing.
 *
 * [prefixes] is what the connected broadcast-capable networks hold on-link right now ([systemPrefixes]); injected so the decision is
 * JVM-tested. The platform's definition leaves out cellular and VPN traffic, so only Wi-Fi and Ethernet networks that are not a VPN
 * are read; being generous elsewhere would only cost an extra prompt.
 */
class OnLinkNetworks(private val prefixes: () -> List<OnLinkPrefix>) {

    fun isOnLink(address: String): Boolean =
        LocalNetwork.isGlobalIpv6(address) && prefixes().any { LocalNetwork.ipv6InPrefix(address, it.address, it.length) }

    companion object {
        /** The real one: the networks the system reports right now. */
        fun of(context: Context): OnLinkNetworks {
            val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            return OnLinkNetworks { systemPrefixes(connectivity) }
        }

        /**
         * The IPv6 prefixes every connected Wi-Fi or Ethernet network (not a VPN) holds on-link. `allNetworks` is deprecated in favour
         * of network callbacks, which track changes over time; this is a one-shot read at the moment of a tap, which is what it is for.
         */
        @Suppress("DEPRECATION")
        fun systemPrefixes(connectivity: ConnectivityManager?): List<OnLinkPrefix> {
            if (connectivity == null) return emptyList()
            return connectivity.allNetworks.flatMap { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                val broadcastCapable = caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (!broadcastCapable) emptyList() else connectivity.getLinkProperties(network)?.let(::prefixesOf).orEmpty()
            }
        }

        /**
         * The IPv6 prefixes [properties] holds on-link: the subnet of every address the interface carries, and every route that needs
         * no gateway (a prefix the router announced as on-link, a stub network such as Thread). The default route is not one.
         */
        fun prefixesOf(properties: LinkProperties): List<OnLinkPrefix> {
            val out = ArrayList<OnLinkPrefix>()
            for (link in properties.linkAddresses) {
                val address = link.address
                if (address is Inet6Address) address.hostAddress?.let { out += OnLinkPrefix(it, link.prefixLength) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // RouteInfo.getDestination() (an IpPrefix) is API 29
                for (route in properties.routes) {
                    val destination = route.destination
                    val address = destination.address
                    if (!route.hasGateway() && destination.prefixLength > 0 && address is Inet6Address) {
                        address.hostAddress?.let { out += OnLinkPrefix(it, destination.prefixLength) }
                    }
                }
            }
            return out
        }
    }
}
