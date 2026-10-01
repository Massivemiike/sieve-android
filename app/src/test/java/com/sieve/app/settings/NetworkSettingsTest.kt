package com.sieve.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NetworkSettingsTest {

    @Test fun proxyAcceptsTheUsualForms() {
        for (p in listOf(
            "http://127.0.0.1:8080", "https://proxy.example.com:3128", "socks5://127.0.0.1:1080", "socks5h://host:9050",
            "socks4://10.0.0.2:1080", "socks4a://10.0.0.2:1080", "http://user:pa55@proxy.local:8080", "http://user@proxy.local:8080",
            "HTTP://PROXY.EXAMPLE.COM:80", "http://[::1]:8080", "http://proxy.local", "http://proxy.local:8080/",
        )) assertNull(NetworkSettings.proxyError(p), p)
    }

    @Test fun proxyRejectsTheUsualMistakes() {
        for (p in listOf("127.0.0.1:8080", "proxy.example.com", "ftp://host:21", "http://", "http://:8080", "socks5//host:1080", "http://host name:80", "just text")) {
            assertEquals(NetworkSettings.PROXY_HINT, NetworkSettings.proxyError(p), p)
        }
        assertEquals("Port must be 1\u201365535", NetworkSettings.proxyError("http://host:0"))
        assertEquals("Port must be 1\u201365535", NetworkSettings.proxyError("http://host:70000"))
    }

    @Test fun blankProxyMeansUnsetAndIsValid() {
        assertNull(NetworkSettings.proxyError(""))
        assertNull(NetworkSettings.proxyError("   "))
        assertNull(NetworkSettings.normalizeProxy("  "))
        assertEquals("http://h:1", NetworkSettings.normalizeProxy("  http://h:1 "))
    }

    @Test fun userAgentRulesAreLenient() {
        assertNull(NetworkSettings.userAgentError(""))
        assertNull(NetworkSettings.userAgentError("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"))
        assertNotNull(NetworkSettings.userAgentError("two\nlines"))
        assertNotNull(NetworkSettings.userAgentError("tab\there"))
        assertNotNull(NetworkSettings.userAgentError("x".repeat(NetworkSettings.MAX_USER_AGENT + 1)))
        assertNull(NetworkSettings.userAgentError("x".repeat(NetworkSettings.MAX_USER_AGENT)))
        assertEquals("a b", NetworkSettings.normalizeUserAgent("  a b "))
        assertNull(NetworkSettings.normalizeUserAgent("   "))
    }

    @Test fun speedLimitAcceptsNumbersWithKMG() {
        for (v in listOf("", "0", "500", "500K", "500k", "2M", "2m", "1.5M", "10G", " 2 M ")) assertNull(NetworkSettings.speedLimitError(v), v)
    }

    @Test fun speedLimitRejectsEverythingElse() {
        for (v in listOf("fast", "2MB/s", "-1M", "M", "1..5M", "2T", "1,5M")) assertEquals(NetworkSettings.SPEED_HINT, NetworkSettings.speedLimitError(v), v)
    }

    @Test fun speedLimitNormalizesToWhatYtDlpTakes() {
        assertEquals("2M", NetworkSettings.normalizeSpeedLimit(" 2 m "))
        assertEquals("500K", NetworkSettings.normalizeSpeedLimit("500k"))
        assertEquals("1.5M", NetworkSettings.normalizeSpeedLimit("1.5M"))
        assertEquals("500", NetworkSettings.normalizeSpeedLimit("500"))
    }

    @Test fun zeroBlankAndJunkMeanUnlimited() {
        for (v in listOf(null, "", "  ", "0", "0K", "0.0M", "fast")) assertNull(NetworkSettings.normalizeSpeedLimit(v), v)
    }
}
