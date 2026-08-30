package com.sieve.engine.args

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalFlagTest {
    @Test fun splitsOnSlashKeepsLast() {
        assertEquals("--extract-audio", YtdlpArgs.canonicalFlag("-x / --extract-audio"))
        assertEquals("--password", YtdlpArgs.canonicalFlag("--username / --password"))
        assertEquals("--embed-metadata", YtdlpArgs.canonicalFlag("--embed-metadata"))
    }
}

class YtdlpArgsTest {
    private val s = EngineSettings()

    @Test fun t1Baseline() = assertEquals(
        listOf("-f", "bestvideo*+bestaudio/best", "-o", "%(title)s [%(id)s].%(ext)s", "-P", "~/Videos/yt-dlp"),
        YtdlpArgs.build(DownloadArgsOptions(format = "bestvideo*+bestaudio/best"), s),
    )

    @Test fun t2EmptyFormatStillEmits() = assertEquals(
        listOf("-f", "", "-o", YtdlpArgs.DEFAULT_TEMPLATE, "-P", "~/Videos/yt-dlp"),
        YtdlpArgs.build(DownloadArgsOptions(format = ""), s),
    )

    @Test fun t10OnToggleDedupVsExtras() {
        val out = YtdlpArgs.build(
            DownloadArgsOptions(
                format = "best",
                extraArgs = listOf("--embed-thumbnail"),
                toggleOpts = linkedMapOf("--embed-thumbnail" to ToggleValue.On),
            ), s,
        )
        assertEquals(1, out.count { it == "--embed-thumbnail" })
    }

    @Test fun t11AliasDedupMissIntentional() {
        val out = YtdlpArgs.build(
            DownloadArgsOptions(
                format = "best",
                extraArgs = listOf("-x"),
                toggleOpts = linkedMapOf("-x / --extract-audio" to ToggleValue.On),
            ), s,
        )
        assertTrue(out.contains("-x"))
        assertTrue(out.contains("--extract-audio"))
    }

    @Test fun t16PlaylistEndZeroSkipped() =
        assertFalse(YtdlpArgs.build(DownloadArgsOptions(format = "best", playlistEnd = 0), s).contains("--playlist-end"))

    @Test fun t17ExplicitArchiveSuppressesFallback() {
        val out = YtdlpArgs.build(
            DownloadArgsOptions(format = "best", downloadArchive = "/a/x.txt"),
            s.copy(autoArchive = true, archiveFile = "/b/y.txt"),
        )
        assertTrue(out.contains("/a/x.txt"))
        assertFalse(out.contains("/b/y.txt"))
    }

    @Test fun t28SubsAllWins() {
        val out = YtdlpArgs.build(DownloadArgsOptions(format = "best", subtitleLangs = listOf("en", "all")), s)
        assertEquals("all", out[out.indexOf("--sub-langs") + 1])
    }

    @Test fun textToggleEmitsFlagAndValue() {
        val out = YtdlpArgs.build(
            DownloadArgsOptions(
                format = "best",
                toggleOpts = linkedMapOf("--sponsorblock-remove" to ToggleValue.Text("sponsor,intro")),
            ), s,
        )
        val i = out.indexOf("--sponsorblock-remove")
        assertTrue(i >= 0)
        assertEquals("sponsor,intro", out[i + 1])
    }

    @Test fun settingsProxyAndHeaderNoSpace() {
        val out = YtdlpArgs.build(
            DownloadArgsOptions(format = "best"),
            EngineSettings(proxy = "http://p:8080", customHeaders = linkedMapOf("Referer" to "https://x")),
        )
        assertEquals("http://p:8080", out[out.indexOf("--proxy") + 1])
        assertEquals("Referer:https://x", out[out.indexOf("--add-header") + 1])
    }

    @Test fun speedLimitZeroAndEmptyUnset() {
        assertFalse(YtdlpArgs.build(DownloadArgsOptions(format = "best", speedLimit = "0"), s).contains("--limit-rate"))
        assertFalse(YtdlpArgs.build(DownloadArgsOptions(format = "best", speedLimit = ""), s).contains("--limit-rate"))
        assertTrue(YtdlpArgs.build(DownloadArgsOptions(format = "best", speedLimit = "2M"), s).contains("--limit-rate"))
    }

    @Test fun emptyTextToggleEmitsNothing() {
        val out = YtdlpArgs.build(
            DownloadArgsOptions(format = "best", toggleOpts = linkedMapOf("--sub-langs" to ToggleValue.Text(""))),
            s,
        )
        assertFalse(out.contains("--sub-langs"))
    }
}
