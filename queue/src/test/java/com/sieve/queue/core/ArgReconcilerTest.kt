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
}
