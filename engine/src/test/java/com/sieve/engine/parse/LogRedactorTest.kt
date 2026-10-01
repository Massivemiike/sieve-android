package com.sieve.engine.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogRedactorTest {

    @Test fun proxyCredentialsAreStrippedAndTheHostIsKept() {
        val out = LogRedactor.redactArgs(listOf("-f", "best", "--proxy", "socks5://alice:s3cret@10.0.0.2:1080", "-N", "4"))
        assertEquals(listOf("-f", "best", "--proxy", "socks5://***@10.0.0.2:1080", "-N", "4"), out)
        assertFalse(out.joinToString(" ").let { "alice" in it || "s3cret" in it })
    }

    @Test fun aUsernameOnlyProxyIsMaskedToo() =
        assertEquals(listOf("--proxy", "http://***@proxy:8080"), LogRedactor.redactArgs(listOf("--proxy", "http://alice@proxy:8080")))

    @Test fun aProxyWithoutCredentialsAndOtherArgsAreUnchanged() {
        val args = listOf(
            "--encoding", "utf-8", "--no-warnings", "-P", "/data/user/0/com.sieve.app/cache/x", "--proxy", "socks5://127.0.0.1:1080",
            "--user-agent", "Mozilla/5.0 (Linux; Android 14)", "--cookies", "/data/user/0/com.sieve.app/files/cookies.txt",
        )
        assertEquals(args, LogRedactor.redactArgs(args))
    }

    @Test fun theEqualsSpellingOfAFlagIsMaskedToo() {
        assertEquals(listOf("--proxy=http://***@h:1"), LogRedactor.redactArgs(listOf("--proxy=http://u:p@h:1")))
        assertEquals(listOf("--password=***"), LogRedactor.redactArgs(listOf("--password=hunter2")))
        assertEquals(listOf("--add-header=Authorization:***"), LogRedactor.redactArgs(listOf("--add-header=Authorization:Bearer abc")))
    }

    @Test fun headerValuesAreMaskedButTheirNamesStay() {
        assertEquals(
            listOf("--add-header", "Authorization:***", "--add-header", "Referer:***"),
            LogRedactor.redactArgs(listOf("--add-header", "Authorization:Bearer abc.def", "--add-header", "Referer:https://example.com/")),
        )
        assertEquals(listOf("--add-header", "***"), LogRedactor.redactArgs(listOf("--add-header", "no-colon-here")))
    }

    @Test fun loginFlagsAreMasked() {
        val out = LogRedactor.redactArgs(listOf("--username", "alice", "--password", "hunter2", "--video-password", "vp", "--twofactor", "123456"))
        assertEquals(listOf("--username", "***", "--password", "***", "--video-password", "***", "--twofactor", "***"), out)
    }

    @Test fun aValueThatLooksLikeAFlagIsStillMaskedAfterASecretFlag() =
        assertEquals(listOf("--password", "***", "-f", "best"), LogRedactor.redactArgs(listOf("--password", "-f", "-f", "best")))

    @Test fun anEmptyVectorIsFine() = assertEquals(emptyList(), LogRedactor.redactArgs(emptyList()))

    // ---- free text: yt-dlp output and exception messages ----

    @Test fun textUserinfoIsStripped() {
        val raw = "ERROR: Unable to connect to proxy socks5://alice:s3cret@10.0.0.2:1080 (Connection refused)"
        assertEquals("ERROR: Unable to connect to proxy socks5://***@10.0.0.2:1080 (Connection refused)", LogRedactor.redact(raw))
    }

    @Test fun textHeadersAreMaskedToTheEndOfTheLine() {
        val raw = "Authorization: Bearer abc.def\nProxy-Authorization: Basic YWxpY2U6czNjcmV0\nCookie: SID=1; HSID=2\nok line"
        assertEquals("Authorization: ***\nProxy-Authorization: ***\nCookie: ***\nok line", LogRedactor.redact(raw))
    }

    @Test fun ordinaryTextIsUntouched() {
        for (s in listOf(
            "ERROR: [youtube] abc: Unable to download webpage: HTTP Error 429: Too Many Requests",
            "ERROR: [vimeo] 1: The web client only works when logged-in. Use --cookies-from-browser or --cookies for the authentication.",
            "[download] Destination: /storage/emulated/0/Download/Sieve/My cookies recipe [abc].mp4",
            "[download] Destination: /storage/emulated/0/Download/Sieve/Cookie: the movie [abc].mp4",
            "https://example.com/watch?email=a@b.com&t=1",
            "ERROR: unable to open for writing: [Errno 13] Permission denied: '/data/x/video.mp4'",
        )) assertEquals(s, LogRedactor.redact(s), s)
    }
}
