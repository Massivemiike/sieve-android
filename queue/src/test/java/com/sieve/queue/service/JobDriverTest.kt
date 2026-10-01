package com.sieve.queue.service

import app.cash.turbine.test
import com.sieve.engine.model.DownloadProgress
import com.sieve.engine.repo.EngineEvent
import com.sieve.queue.core.CancelReason
import com.sieve.queue.core.JobSignal
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.Outcome
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.RetryClass
import com.sieve.queue.core.RetryClassifier
import com.sieve.transcode.runner.FfmpegProgress
import com.sieve.transcode.runner.TranscodeEvent
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobDriverTest {
    private fun dl(id: String) = QueueJob(id, JobSpec.Download("u", listOf("-f", "best")), OutputRequest("d", "o"))
    private fun tx(id: String, dur: Double?) = QueueJob(id, JobSpec.Transcode("/in", emptyList(), dur, false), OutputRequest("d", "o"))

    @Test fun `download completed exit 0 maps to Succeeded`() = runTest {
        val ports = FakeDownloadPort {
            flow {
                emit(EngineEvent.Progress(DownloadProgress(0.5f, "1MiB/s", "00:10", "1/2")))
                emit(EngineEvent.Completed(0))
            }
        }
        JobDriver(ports, FakeTranscodePort()).drive(dl("a")) { null }.test {
            assertTrue(awaitItem() is JobSignal.Progress)
            assertEquals(Outcome.Succeeded, (awaitItem() as JobSignal.Terminal).outcome)
            awaitComplete()
        }
    }

    @Test fun `download completed nonzero maps to Failed not success`() = runTest {
        val ports = FakeDownloadPort { flow { emit(EngineEvent.Completed(1)) } }
        JobDriver(ports, FakeTranscodePort()).drive(dl("a")) { null }.test {
            assertTrue((awaitItem() as JobSignal.Terminal).outcome is Outcome.Failed)
            awaitComplete()
        }
    }

    @Test fun `download failure carries the ERROR line, not just the exit code`() = runTest {
        val blob = "WARNING: [youtube] abc: Some formats may be missing\n" +
            "ERROR: [youtube] abc: Unable to download API page: HTTP Error 429: Too Many Requests\n"
        val ports = FakeDownloadPort {
            flow {
                emit(EngineEvent.Log(blob, null, true))
                emit(EngineEvent.Completed(1))
            }
        }
        JobDriver(ports, FakeTranscodePort()).drive(dl("a")) { null }.test {
            assertTrue((awaitItem() as JobSignal.Log).isError) // still forwarded to the row's log
            val info = ((awaitItem() as JobSignal.Terminal).outcome as Outcome.Failed).info
            assertEquals("ERROR: [youtube] abc: Unable to download API page: HTTP Error 429: Too Many Requests", info.message)
            assertFalse("WARNING" in info.message)
            assertEquals(1, info.exitCode)
            assertEquals(blob, info.stderrTail)
            // the whole point: the queue's retry policy now sees a real 429
            assertEquals(RetryClass.TRANSIENT, RetryClassifier.classify(info))
            awaitComplete()
        }
    }

    @Test fun `download failure network error classifies transient`() = runTest {
        val blob = "ERROR: [youtube] abc: Unable to download webpage: The read operation timed out"
        val ports = FakeDownloadPort { flow { emit(EngineEvent.Log(blob, null, true)); emit(EngineEvent.Completed(1)) } }
        JobDriver(ports, FakeTranscodePort()).drive(dl("a")) { null }.test {
            awaitItem() // log
            val info = ((awaitItem() as JobSignal.Terminal).outcome as Outcome.Failed).info
            assertEquals(blob, info.message)
            assertEquals(RetryClass.TRANSIENT, RetryClassifier.classify(info))
            awaitComplete()
        }
    }

    @Test fun `download failure without an ERROR line uses the last non-blank line`() = runTest {
        val blob = "java.lang.IllegalStateException: instance not initialized\n\n"
        val ports = FakeDownloadPort { flow { emit(EngineEvent.Log(blob, null, true)); emit(EngineEvent.Completed(1)) } }
        JobDriver(ports, FakeTranscodePort()).drive(dl("a")) { null }.test {
            awaitItem()
            val info = ((awaitItem() as JobSignal.Terminal).outcome as Outcome.Failed).info
            assertEquals("java.lang.IllegalStateException: instance not initialized", info.message)
            assertEquals(RetryClass.PERMANENT, RetryClassifier.classify(info))
            awaitComplete()
        }
    }

    @Test fun `download failure message is capped and stderrTail keeps the last 4000 chars`() = runTest {
        val blob = "ERROR: " + "x".repeat(6000)
        val ports = FakeDownloadPort { flow { emit(EngineEvent.Log(blob, null, true)); emit(EngineEvent.Completed(1)) } }
        JobDriver(ports, FakeTranscodePort()).drive(dl("a")) { null }.test {
            awaitItem()
            val info = ((awaitItem() as JobSignal.Terminal).outcome as Outcome.Failed).info
            assertEquals(500, info.message.length)
            assertEquals(4000, info.stderrTail!!.length)
            awaitComplete()
        }
    }

    @Test fun `non-error logs do not become the failure message`() = runTest {
        val ports = FakeDownloadPort {
            flow {
                emit(EngineEvent.Log("[download] Destination: x.mp4", null, false))
                emit(EngineEvent.Completed(2))
            }
        }
        JobDriver(ports, FakeTranscodePort()).drive(dl("a")) { null }.test {
            awaitItem() // log
            val info = ((awaitItem() as JobSignal.Terminal).outcome as Outcome.Failed).info
            assertEquals("yt-dlp exited 2", info.message)
            assertEquals(2, info.exitCode)
            assertNull(info.stderrTail)
            awaitComplete()
        }
    }

    @Test fun `download Cancelled uses supplied cancelReason`() = runTest {
        val ports = FakeDownloadPort { flow { emit(EngineEvent.Cancelled) } }
        JobDriver(ports, FakeTranscodePort()).drive(dl("a")) { CancelReason.PAUSE }.test {
            assertEquals(Outcome.Cancelled(CancelReason.PAUSE), (awaitItem() as JobSignal.Terminal).outcome)
            awaitComplete()
        }
    }

    @Test fun `transcode Done nonzero with cancelReason is Cancelled`() = runTest {
        val ports = FakeTranscodePort { flow { emit(TranscodeEvent.Done(255, "killed", "sigterm")) } }
        JobDriver(FakeDownloadPort(), ports).drive(tx("a", 10.0)) { CancelReason.USER_CANCEL }.test {
            assertEquals(Outcome.Cancelled(CancelReason.USER_CANCEL), (awaitItem() as JobSignal.Terminal).outcome)
            awaitComplete()
        }
    }

    // ffmpeg treats the 'q' that Cancel/Pause writes as a normal stop: it finalizes a truncated file and
    // exits 0. The stamped reason must win over the exit code or a cancelled job is saved as finished.
    @Test fun `transcode Done 0 with USER_CANCEL is Cancelled not Succeeded`() = runTest {
        val ports = FakeTranscodePort { flow { emit(TranscodeEvent.Done(0, null, "")) } }
        JobDriver(FakeDownloadPort(), ports).drive(tx("a", 10.0)) { CancelReason.USER_CANCEL }.test {
            assertEquals(Outcome.Cancelled(CancelReason.USER_CANCEL), (awaitItem() as JobSignal.Terminal).outcome)
            awaitComplete()
        }
    }

    @Test fun `transcode Done 0 with PAUSE is Cancelled not Succeeded`() = runTest {
        val ports = FakeTranscodePort { flow { emit(TranscodeEvent.Done(0, null, "")) } }
        JobDriver(FakeDownloadPort(), ports).drive(tx("a", 10.0)) { CancelReason.PAUSE }.test {
            assertEquals(Outcome.Cancelled(CancelReason.PAUSE), (awaitItem() as JobSignal.Terminal).outcome)
            awaitComplete()
        }
    }

    @Test fun `transcode Done 0 without cancelReason is Succeeded`() = runTest {
        val ports = FakeTranscodePort { flow { emit(TranscodeEvent.Done(0, null, "")) } }
        JobDriver(FakeDownloadPort(), ports).drive(tx("a", 10.0)) { null }.test {
            assertEquals(Outcome.Succeeded, (awaitItem() as JobSignal.Terminal).outcome)
            awaitComplete()
        }
    }

    @Test fun `transcode Done nonzero without cancelReason is Failed`() = runTest {
        val ports = FakeTranscodePort { flow { emit(TranscodeEvent.Done(1, "bad codec", "tail")) } }
        JobDriver(FakeDownloadPort(), ports).drive(tx("a", 10.0)) { null }.test {
            val t = (awaitItem() as JobSignal.Terminal).outcome as Outcome.Failed
            assertEquals("bad codec", t.info.message)
            assertEquals(1, t.info.exitCode)
            awaitComplete()
        }
    }

    @Test fun `transcode progress with null duration is indeterminate`() = runTest {
        val ports = FakeTranscodePort {
            flow {
                emit(TranscodeEvent.Progress(FfmpegProgress(outTimeUs = 5_000_000L, percent = null, speed = 2.0, speedRaw = "2x")))
                emit(TranscodeEvent.Done(0, null, ""))
            }
        }
        JobDriver(FakeDownloadPort(), ports).drive(tx("a", null)) { null }.test {
            assertNull((awaitItem() as JobSignal.Progress).progress.fraction)
            assertEquals(Outcome.Succeeded, (awaitItem() as JobSignal.Terminal).outcome)
            awaitComplete()
        }
    }

    @Test fun `signals after first terminal are dropped`() = runTest {
        val ports = FakeDownloadPort {
            flow {
                emit(EngineEvent.Completed(0))
                emit(EngineEvent.Progress(DownloadProgress(0.9f))) // stray after terminal
            }
        }
        JobDriver(ports, FakeTranscodePort()).drive(dl("a")) { null }.test {
            assertTrue(awaitItem() is JobSignal.Terminal)
            awaitComplete()
        }
    }
}
