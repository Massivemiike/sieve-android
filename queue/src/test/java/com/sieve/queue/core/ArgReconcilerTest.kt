package com.sieve.queue.core

import org.junit.Assert.assertEquals
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
        assertEquals(
            listOf(
                "-f", "best", "-P", "/work/job-a", "-o", "%(sieve_title).150B [%(id)s].%(ext)s",
                "--parse-metadata", "%(title)S:%(sieve_title)s",
            ),
            out,
        )
    }

    @Test fun `a row persisted by 1_0_3 or the 1_0_4 RC is spawned with the byte-exact title cut`() {
        // YtdlpArgs.build() put -o and -P in the persisted args; the output seam's template is the persisted outputTemplate.
        val out = ArgReconciler.buildSpawnArgs(
            JobSpec.Download("https://x", listOf("-f", "bestvideo*+bestaudio/best", "-o", "%(title).150B [%(id)s].%(ext)s", "-P", "~/Videos/yt-dlp", "--embed-metadata")),
            PreparedOutput("/work/job-a", "%(title).150B [%(id)s].%(ext)s"),
        )
        assertEquals(
            listOf(
                "--newline", "-c", "--no-warnings",
                "-f", "bestvideo*+bestaudio/best", "--embed-metadata",
                "-P", "/work/job-a", "-o", "%(sieve_title).150B [%(id)s].%(ext)s",
                "--parse-metadata", "%(title)S:%(sieve_title)s",
            ),
            out,
        )
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

    @Test fun `byteSafeTemplate cuts the sanitized title copy and leaves other templates alone`() {
        assertEquals("%(sieve_title).150B [%(id)s].%(ext)s", ArgReconciler.byteSafeTemplate("%(title)s [%(id)s].%(ext)s"))
        assertEquals("%(sieve_title).150B [%(id)s].%(ext)s", ArgReconciler.byteSafeTemplate("%(title).150B [%(id)s].%(ext)s"))
        assertEquals("%(sieve_title).150B [%(id)s].%(ext)s", ArgReconciler.byteSafeTemplate("%(sieve_title).150B [%(id)s].%(ext)s"))
        assertEquals("%(id)s.%(ext)s", ArgReconciler.byteSafeTemplate("%(id)s.%(ext)s"))
        assertEquals("%(playlist_title)s/%(id)s.%(ext)s", ArgReconciler.byteSafeTemplate("%(playlist_title)s/%(id)s.%(ext)s"))
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
