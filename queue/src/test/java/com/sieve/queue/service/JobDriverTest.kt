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
import com.sieve.transcode.runner.FfmpegProgress
import com.sieve.transcode.runner.TranscodeEvent
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
