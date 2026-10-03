package com.sieve.queue.core

import com.sieve.engine.args.YtdlpArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArgReconcilerTest {
    @Test fun `ensureContinue prepends -c when missing`() {
        assertEquals(listOf("-c", "-f", "best"), ArgReconciler.ensureContinue(listOf("-f", "best")))
    }

    @Test fun `ensureContinue is idempotent`() {
        assertEquals(listOf("-c", "-f", "best"), ArgReconciler.ensureContinue(listOf("-c", "-f", "best")))
    }

    @Test fun `rewriteFlagValue replaces existing pair`() {
        assertEquals(
            listOf("-f", "best", "-N", "4"),
            ArgReconciler.rewriteFlagValue(listOf("-f", "best", "-N", "2"), "-N", "4"),
        )
    }

    @Test fun `rewriteFlagValue appends when absent`() {
        assertEquals(
            listOf("-f", "best", "-N", "4"),
            ArgReconciler.rewriteFlagValue(listOf("-f", "best"), "-N", "4"),
        )
    }

    @Test fun `stripFlagValue removes flag and its value`() {
        assertEquals(
            listOf("-f", "best"),
            ArgReconciler.stripFlagValue(listOf("-f", "best", "--cookies-from-browser", "chrome"), "--cookies-from-browser"),
        )
    }

    @Test fun `stripFlag removes a bare flag`() {
        assertEquals(listOf("-f", "best"), ArgReconciler.stripFlag(listOf("-f", "best", "--geo-bypass"), "--geo-bypass"))
    }

    @Test fun `injectDownloadOutput sets -P and -o from prepared`() {
        val out = ArgReconciler.injectDownloadOutput(
            listOf("-f", "best", "-P", "/old", "-o", "x.%(ext)s"),
            PreparedOutput(workDir = "/work/job-a", workFileTemplate = "%(title)s [%(id)s].%(ext)s"),
        )
        assertEquals(listOf("-f", "best", "-P", "/work/job-a", "-o", "%(title).150B [%(id)s].%(ext)s"), out)
    }

    @Test fun `injectDownloadOutput points yt-dlp at the job's download archive`() {
        val out = ArgReconciler.injectDownloadOutput(
            listOf("-f", "best"),
            PreparedOutput("/work/job-a", "%(id)s.%(ext)s", archivePath = "/work/job-a.archive.txt"),
        )
        assertEquals(listOf("-f", "best", "-P", "/work/job-a", "-o", "%(id)s.%(ext)s", "--download-archive", "/work/job-a.archive.txt"), out)
    }

    @Test fun `injectDownloadOutput leaves a download archive the caller already chose alone`() {
        val out = ArgReconciler.injectDownloadOutput(
            listOf("--download-archive", "/mine/archive.txt"),
            PreparedOutput("/work/job-a", "%(id)s.%(ext)s", archivePath = "/work/job-a.archive.txt"),
        )
        assertEquals(1, out.count { it == "--download-archive" })
        assertEquals("/mine/archive.txt", out[out.indexOf("--download-archive") + 1])
    }

    @Test fun `byteSafeTemplate clamps an unbounded title and leaves others alone`() {
        assertEquals("%(title).150B [%(id)s].%(ext)s", ArgReconciler.byteSafeTemplate("%(title)s [%(id)s].%(ext)s"))
        assertEquals("%(title).150B [%(id)s].%(ext)s", ArgReconciler.byteSafeTemplate("%(title).150B [%(id)s].%(ext)s"))
        assertEquals("%(id)s.%(ext)s", ArgReconciler.byteSafeTemplate("%(id)s.%(ext)s"))
    }

    // Rows saved before the 1.0.4 preset fix keep the args they were saved with: the reconciler only adds -c and swaps -P/-o.
    @Test fun `args stored by the old presets are spawned as they are, with no migration`() {
        val old720 = "bestvideo[height<=720][vcodec^=avc1]+bestaudio[ext=m4a]/bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/" +
            "bestvideo[height<=720]+bestaudio/best[height<=720]/best"
        val rows = listOf(
            listOf("-f", old720, "-o", "%(title)s [%(id)s].%(ext)s", "-P", "~/Videos/yt-dlp", "--embed-metadata", "--embed-thumbnail", "-N", "4"),
            listOf("-f", "bestaudio/best", "-o", "%(title)s [%(id)s].%(ext)s", "-P", "~/Music", "-x", "--audio-format", "mp3", "--audio-quality", "0"),
            listOf("-f", "bestaudio[ext=webm]/bestaudio/best", "-o", "%(title)s [%(id)s].%(ext)s", "-P", "~/Music", "-x"),
        )
        for (stored in rows) {
            val prepared = PreparedOutput("/work/a", "%(title)s [%(id)s].%(ext)s")
            val out = ArgReconciler.injectDownloadOutput(ArgReconciler.ensureContinue(stored), prepared)
            val expected = listOf("-c") + stored.filterIndexed { i, _ -> i !in 2..5 } +
                listOf("-P", "/work/a", "-o", "%(title).150B [%(id)s].%(ext)s")
            assertEquals(expected, out)
            assertTrue("-S" !in out && "--merge-output-format" !in out && "320K" !in out && "128K" !in out)
        }
    }

    // ...and so do rows saved by the first 1.0.4 release candidate (419d299): their H.264-first MP4 selector and sort stay as stored.
    @Test fun `args stored by the first release candidate are spawned as they are, with no migration`() {
        val rc1Format = "bv[vcodec~='^(avc|h264)']+ba/b[vcodec~='^(avc|h264)']/" +
            "b[format_id=hd][ext=mp4][url~='[?&]tag=(hd|dash_h264[a-z0-9_-]*)(&|\$)']/b[format_id=sd][ext=mp4]/bv*+ba/b"
        for (cap in listOf(1080, 720)) {
            val stored = listOf(
                "-f", rc1Format, "-o", "%(title)s [%(id)s].%(ext)s", "-P", "~/Videos/yt-dlp",
                "-S", "res:$cap,vcodec:h264,acodec:aac,ext:mp4:m4a", "--merge-output-format", "mp4",
                "--embed-metadata", "--embed-thumbnail", "-N", "4",
            )
            val prepared = PreparedOutput("/work/a", "%(title)s [%(id)s].%(ext)s")
            val out = ArgReconciler.injectDownloadOutput(ArgReconciler.ensureContinue(stored), prepared)
            val expected = listOf("-c") + stored.filterIndexed { i, _ -> i !in 2..5 } +
                listOf("-P", "/work/a", "-o", "%(title).150B [%(id)s].%(ext)s")
            assertEquals(expected, out)
            assertTrue(out.none { "proto" in it || "[width>" in it }) // none of the keep-resolution selector or sort crept in
        }
    }

    @Test fun `buildSpawnArgs prepends invariant flags and injects output`() {
        val spec = JobSpec.Download("https://x", listOf("-f", "best"))
        val args = ArgReconciler.buildSpawnArgs(spec, PreparedOutput("/w/a", "%(title)s.%(ext)s"))
        assertEquals(0, args.indexOf("--newline"))
        assertTrue(args.contains("-c"))
        assertTrue(args.contains("--no-warnings"))
        assertEquals("/w/a", args[args.indexOf("-P") + 1])
        assertTrue(!args.contains("https://x"))
    }
}

/** [ArgReconciler.overflowedFileName]: is "File name too long" PROVEN to be what stopped a row whose yt-dlp error was never stored? */
class ArgReconcilerOverflowTest {
    private val old = "%(title)s [%(id)s].%(ext)s"

    @Test fun `byteSafeTemplate and the default template are the same clamp`() {
        assertEquals(YtdlpArgs.DEFAULT_TEMPLATE, ArgReconciler.byteSafeTemplate(old))
    }

    @Test fun `the least yt-dlp adds is counted, so the limit falls between 243 and 244 title bytes`() {
        // " [x]" + ".ts.part" = 12 bytes: 243 + 12 fits in 255, 244 + 12 does not.
        assertFalse(ArgReconciler.overflowedFileName(old, "a".repeat(243)))
        assertTrue(ArgReconciler.overflowedFileName(old, "a".repeat(244)))
    }

    @Test fun `bytes are counted, not characters`() {
        assertTrue("61 emoji are 61 characters, 244 bytes", ArgReconciler.overflowedFileName(old, "🎬".repeat(61)))
        assertFalse("60 emoji are 240 bytes", ArgReconciler.overflowedFileName(old, "🎬".repeat(60)))
        assertTrue("100 CJK characters are 300 bytes", ArgReconciler.overflowedFileName(old, "字".repeat(100)))
        assertFalse("200 ASCII characters are 200 bytes", ArgReconciler.overflowedFileName(old, "a".repeat(200)))
    }

    @Test fun `a template that already clamps the title cannot have overflowed because of it`() {
        val long = "🎬".repeat(70)
        assertFalse(ArgReconciler.overflowedFileName(YtdlpArgs.DEFAULT_TEMPLATE, long))
        assertFalse(ArgReconciler.overflowedFileName("%(title).100B [%(id)s].%(ext)s", long))
        assertFalse("no title in the template", ArgReconciler.overflowedFileName("%(id)s.%(ext)s", long))
    }

    @Test fun `a row with no title proves nothing`() {
        assertFalse(ArgReconciler.overflowedFileName(old, ""))
    }

    // likelyOverflowedFileName: the same question once the real part-file suffix of the row's site is counted.

    @Test fun `Facebook's part-file suffix is 47 bytes, so its captions overflow from 209 bytes, not 244`() {
        val suffix = " [1370361647863285].f1234567890123456v.mp4.part"
        assertEquals(47, suffix.toByteArray().size)
        assertEquals(YtdlpArgs.MAX_FILENAME_BYTES, "a".repeat(208).toByteArray().size + suffix.toByteArray().size)

        assertFalse(ArgReconciler.likelyOverflowedFileName(old, "a".repeat(208), "facebook"))
        assertTrue(ArgReconciler.likelyOverflowedFileName(old, "a".repeat(209), "facebook"))
        // The measured 221-byte caption: not PROVEN (244 + 12 hits nothing), but its part file was 268 bytes.
        val caption = "🎬🔥 ".repeat(24) + "Cats!"
        assertEquals(221, caption.toByteArray().size)
        assertFalse(ArgReconciler.overflowedFileName(old, caption))
        assertTrue(ArgReconciler.likelyOverflowedFileName(old, caption, "facebook"))
    }

    @Test fun `only Facebook's suffix is known, so any other site stays at what is proven`() {
        val title = "a".repeat(221)
        for (site in listOf("youtube", "instagram", "twitter", "Unknown", "", "facebookish", "notfacebook")) {
            assertFalse(site, ArgReconciler.likelyOverflowedFileName(old, title, site))
        }
        for (site in listOf("facebook", "Facebook", "facebook:reel", "facebook:ads")) {
            assertTrue(site, ArgReconciler.likelyOverflowedFileName(old, title, site))
        }
    }

    @Test fun `what is proven is also likely, on every site`() {
        for (site in listOf("youtube", "facebook", "Unknown")) {
            assertTrue(site, ArgReconciler.likelyOverflowedFileName(old, "a".repeat(244), site))
            assertFalse(site, ArgReconciler.likelyOverflowedFileName(old, "a".repeat(100), site))
        }
    }

    @Test fun `a clamped title is not likely to have overflowed either`() {
        assertFalse(ArgReconciler.likelyOverflowedFileName(YtdlpArgs.DEFAULT_TEMPLATE, "🎬".repeat(70), "facebook"))
        assertFalse(ArgReconciler.likelyOverflowedFileName("%(id)s.%(ext)s", "a".repeat(221), "facebook"))
        assertFalse(ArgReconciler.likelyOverflowedFileName(old, "", "facebook"))
    }
}
