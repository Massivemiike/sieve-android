package com.sieve.app.queue

import com.sieve.app.ui.queue.QueueReveal
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hand-off from "the user added a download" to "the Queue tab shows its row", and who is supposed to make the request. */
class QueueRevealTest {

    @Before @After fun clear() { QueueReveal.id.value?.let(QueueReveal::consume) }

    @Test fun `nothing is pending in a new process`() {
        assertNull(QueueReveal.id.value)
    }

    @Test fun `a request is held until it is consumed`() {
        QueueReveal.request("a")
        assertEquals("a", QueueReveal.id.value)
        QueueReveal.consume("a")
        assertNull(QueueReveal.id.value)
    }

    @Test fun `the newest request wins, so a batch of adds reveals its last row`() {
        QueueReveal.request("first")
        QueueReveal.request("second")
        assertEquals("second", QueueReveal.id.value)
    }

    @Test fun `finishing an old request does not wipe a newer one`() {
        QueueReveal.request("old")
        QueueReveal.request("new")
        QueueReveal.consume("old")
        assertEquals("new", QueueReveal.id.value)
    }

    /**
     * "Any other path that enqueues": the Download and Transcode screens are the only callers of the queue's `enqueue`. Each must
     * ask for the reveal in the same breath, or a new row on a long queue is still appended below the fold. A static read of the
     * sources (the unit-test task re-runs whenever the compiled sources change), so a third path cannot be added quietly.
     */
    @Test fun `every production enqueue asks the Queue tab to reveal its row`() {
        val moduleDir = File(".").absoluteFile.let { if (File(it, "build.gradle.kts").isFile) it else File(it, "app") }
        val sources = File(moduleDir, "src/main/java")
        assertTrue(sources.isDirectory, "missing ${sources.absolutePath}")
        val enqueues = sources.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().filter { "queue.enqueue(" in it }.map { f.name to it.trim() } }.toList()
        assertTrue(enqueues.size >= 2, "expected the Download and Transcode enqueue calls, found $enqueues")
        enqueues.forEach { (file, line) ->
            assertTrue("QueueReveal.request(" in line, "$file enqueues without QueueReveal.request(): $line")
        }
    }
}
