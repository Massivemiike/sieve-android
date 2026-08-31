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
        assertEquals(listOf("-f", "best", "-P", "/work/job-a", "-o", "%(title)s [%(id)s].%(ext)s"), out)
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
