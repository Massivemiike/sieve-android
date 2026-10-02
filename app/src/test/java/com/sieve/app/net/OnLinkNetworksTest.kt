package com.sieve.app.net

import android.app.Application
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [OnLinkNetworks]: Android 17 counts a globally routable IPv6 address as the local network when a Wi-Fi or Ethernet network holds
 * its prefix on-link. The decision is plain prefix arithmetic over what [OnLinkNetworks.prefixesOf] and [OnLinkNetworks.systemPrefixes]
 * read from the connected networks; the platform classes that carry that information cannot be built from the public API
 * (`LinkAddress`, `RouteInfo` have no public constructors), so the tests build them the way the framework does, by reflection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnLinkNetworksTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun v6(text: String): InetAddress = InetAddress.getByName(text)

    private fun linkAddress(text: String, prefixLength: Int): LinkAddress =
        ReflectionHelpers.callConstructor(
            LinkAddress::class.java,
            ClassParameter.from(InetAddress::class.java, v6(text)),
            ClassParameter.from(Int::class.javaPrimitiveType, prefixLength),
        )

    private fun route(prefix: String, length: Int, gateway: String? = null): RouteInfo =
        ReflectionHelpers.callConstructor(
            RouteInfo::class.java,
            ClassParameter.from(IpPrefix::class.java, IpPrefix(v6(prefix), length)),
            ClassParameter.from(InetAddress::class.java, gateway?.let(::v6)),
            ClassParameter.from(String::class.java, null), // the LinkProperties built here has no interface name, and addRoute insists the two match
        )

    private fun properties(addresses: List<Pair<String, Int>> = emptyList(), routes: List<RouteInfo> = emptyList()): LinkProperties {
        val lp = LinkProperties()
        for ((a, len) in addresses) {
            ReflectionHelpers.callInstanceMethod<Boolean>(lp, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, linkAddress(a, len)))
        }
        routes.forEach { lp.addRoute(it) }
        return lp
    }

    // ---- prefixesOf: what a link holds on-link -------------------------------------------------------------------------------

    @Test fun `the subnet of every IPv6 address the link carries is on-link`() {
        val lp = properties(addresses = listOf("2001:db8:1::10" to 64, "fe80::1" to 64, "fd12:3456:789a::7" to 64))
        assertEquals(3, OnLinkNetworks.prefixesOf(lp).size)
        assertEquals(listOf(64, 64, 64), OnLinkNetworks.prefixesOf(lp).map { it.length })
        val networks = OnLinkNetworks { OnLinkNetworks.prefixesOf(lp) }
        assertTrue(networks.isOnLink("2001:db8:1::99")) // another host of the Wi-Fi's /64
        assertTrue(networks.isOnLink("2001:db8:1:0:ffff:ffff:ffff:ffff"))
        assertFalse(networks.isOnLink("2001:db8:2::99")) // the next /64 is not on the link
    }

    @Test fun `an IPv4 address is not a prefix of interest`() {
        assertEquals(emptyList(), OnLinkNetworks.prefixesOf(properties(addresses = listOf("192.168.1.20" to 24))).map { it.address })
    }

    @Test fun `a route without a gateway is on-link, a route through a gateway and the default route are not`() {
        val lp = properties(
            routes = listOf(
                route("2001:db8:2::", 64), // a prefix the router announced as on-link, or a stub network
                route("2001:db8:3::", 64, gateway = "fe80::1"), // reached through the router: not on-link
                route("::", 0), // the default route, even without a gateway, would make everything look local
                route("192.168.50.0", 24), // IPv4
            ),
        )
        val networks = OnLinkNetworks { OnLinkNetworks.prefixesOf(lp) }
        assertTrue(networks.isOnLink("2001:db8:2::7"))
        assertFalse(networks.isOnLink("2001:db8:3::7"))
        assertFalse(networks.isOnLink("2606:4700:4700::1111"))
    }

    // ---- systemPrefixes: which of the connected networks count ----------------------------------------------------------------

    private fun connectivity() = app.getSystemService(ConnectivityManager::class.java)

    private var nextNetId = 100

    /** Connects a network that reports [transports] and [lp] (null capabilities when [transports] is null). */
    @Suppress("DEPRECATION") // the shadow's addNetwork still takes the legacy NetworkInfo
    private fun connect(transports: List<Int>?, lp: LinkProperties): Network {
        val shadow = shadowOf(connectivity())
        val network = ShadowNetwork.newInstance(nextNetId++)
        shadow.addNetwork(network, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED))
        if (transports != null) {
            val caps = ShadowNetworkCapabilities.newInstance()
            transports.forEach { shadowOf(caps).addTransportType(it) }
            shadow.setNetworkCapabilities(network, caps)
        }
        shadow.setLinkProperties(network, lp)
        return network
    }

    private val wifiLan get() = properties(addresses = listOf("2001:db8:1::10" to 64))

    private fun onLink(address: String) = OnLinkNetworks { OnLinkNetworks.systemPrefixes(connectivity()) }.isOnLink(address)

    @Test fun `a Wi-Fi network's prefix counts`() {
        connect(listOf(NetworkCapabilities.TRANSPORT_WIFI), wifiLan)
        assertTrue(onLink("2001:db8:1::5"))
        assertFalse(onLink("2001:db8:9::5"))
    }

    @Test fun `an Ethernet network's prefix counts`() {
        connect(listOf(NetworkCapabilities.TRANSPORT_ETHERNET), wifiLan)
        assertTrue(onLink("2001:db8:1::5"))
    }

    @Test fun `a cellular network's prefix does not, it is not broadcast-capable`() {
        connect(listOf(NetworkCapabilities.TRANSPORT_CELLULAR), wifiLan)
        assertFalse(onLink("2001:db8:1::5"))
    }

    @Test fun `a VPN does not, even when it carries the Wi-Fi transport of the network under it`() {
        connect(listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN), wifiLan)
        assertFalse(onLink("2001:db8:1::5"))
    }

    @Test fun `a network that reports no capabilities does not`() {
        connect(null, wifiLan)
        assertFalse(onLink("2001:db8:1::5"))
    }

    @Test fun `with a VPN up, the Wi-Fi network under it still counts`() {
        connect(listOf(NetworkCapabilities.TRANSPORT_VPN), properties(addresses = listOf("2001:db8:77::2" to 64)))
        connect(listOf(NetworkCapabilities.TRANSPORT_WIFI), wifiLan)
        assertTrue(onLink("2001:db8:1::5"))
        assertFalse(onLink("2001:db8:77::5"), "the VPN's own prefix is not the local network")
    }

    @Test fun `no connectivity service means no prefixes`() {
        assertEquals(emptyList(), OnLinkNetworks.systemPrefixes(null).map { it.address })
    }

    // ---- the decision on its own ----------------------------------------------------------------------------------------------

    @Test fun `only a global unicast address is ever asked about`() {
        val networks = OnLinkNetworks { listOf(OnLinkPrefix("2001:db8:1:0:0:0:0:0", 64), OnLinkPrefix("fe80:0:0:0:0:0:0:0", 64)) }
        assertTrue(networks.isOnLink("2001:db8:1::5"))
        assertTrue(networks.isOnLink("[2001:db8:1::5]"))
        assertFalse(networks.isOnLink("fe80::5"), "link-local is local by its shape, so it is not asked here")
        assertFalse(networks.isOnLink("192.168.1.5"))
        assertFalse(networks.isOnLink("nas.example.org"))
        assertFalse(networks.isOnLink("2001:db8:2::5"))
    }

    @Test fun `no prefixes means nothing is on-link`() {
        assertFalse(OnLinkNetworks { emptyList() }.isOnLink("2001:db8:1::5"))
    }
}
