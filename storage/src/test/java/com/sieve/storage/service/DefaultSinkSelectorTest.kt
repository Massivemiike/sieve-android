package com.sieve.storage.service

import com.sieve.storage.sink.DestinationSink
import com.sieve.storage.sink.FakeSink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultSinkSelectorTest {
    private fun sel(tree: DestinationSink?, ms: DestinationSink?, app: DestinationSink) =
        DefaultSinkSelector({ tree }, { ms }, { app })

    private val job = QueueJobFixtures.download("j")

    @Test fun `prefers granted tree`() = runTest {
        val tree = FakeSink("tree"); val ms = FakeSink("ms"); val app = FakeSink("app")
        assertEquals("tree", sel(tree, ms, app).select(job).rootLabel)
    }

    @Test fun `falls back to media store when no tree`() = runTest {
        val ms = FakeSink("ms"); val app = FakeSink("app")
        assertEquals("ms", sel(null, ms, app).select(job).rootLabel)
    }

    @Test fun `falls back to app files when neither tree nor media store`() = runTest {
        val app = FakeSink("app")
        assertEquals("app", sel(null, null, app).select(job).rootLabel)
    }
}
