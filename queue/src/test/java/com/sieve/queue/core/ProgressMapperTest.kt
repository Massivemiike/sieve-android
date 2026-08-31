package com.sieve.queue.core

import com.sieve.engine.model.DownloadProgress
import com.sieve.transcode.runner.FfmpegProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressMapperTest {
    @Test fun `download percent copies through as fraction and phase`() {
        val u = ProgressMapper.fromDownload(
            DownloadProgress(percent = 0.63f, speed = "1.17MiB/s", eta = "00:42", fragment = "3/10"),
        )
        assertEquals(0.63f, u.fraction!!, 1e-4f)
        assertEquals("1.17MiB/s", u.speed)
        assertEquals("00:42", u.eta)
        assertEquals(Phase.DOWNLOADING, u.phase)
        assertEquals(3, u.fragmentIndex)
        assertEquals(10, u.fragmentCount)
    }

    @Test fun `download dash sentinels become null`() {
        val u = ProgressMapper.fromDownload(DownloadProgress(percent = 0.1f)) // speed/eta/fragment default "—"
        assertNull(u.speed)
        assertNull(u.eta)
        assertNull(u.fragmentIndex)
        assertNull(u.fragmentCount)
    }

    @Test fun `ffmpeg out-time over duration is the fraction`() {
        val u = ProgressMapper.fromFfmpeg(
            FfmpegProgress(outTimeUs = 5_000_000L, percent = null, speed = 2.0, speedRaw = "2.0x"),
            totalDurationSec = 10.0,
        )
        assertEquals(0.5f, u.fraction!!, 1e-4f)
        assertEquals(Phase.TRANSCODING, u.phase)
        assertEquals("2.0x", u.speed)
    }

    @Test fun `ffmpeg uses precomputed percent when present`() {
        val u = ProgressMapper.fromFfmpeg(
            FfmpegProgress(outTimeUs = 5_000_000L, percent = 0.42, speed = null, speedRaw = null),
            totalDurationSec = 10.0,
        )
        assertEquals(0.42f, u.fraction!!, 1e-4f)
    }

    @Test fun `ffmpeg with null duration and null percent is indeterminate`() {
        val u = ProgressMapper.fromFfmpeg(
            FfmpegProgress(outTimeUs = 5_000_000L, percent = null, speed = null, speedRaw = null),
            totalDurationSec = null,
        )
        assertNull(u.fraction)
    }

    @Test fun `ffmpeg fraction is clamped to 0_1`() {
        val u = ProgressMapper.fromFfmpeg(
            FfmpegProgress(outTimeUs = 11_000_000L, percent = null, speed = null, speedRaw = null),
            totalDurationSec = 10.0,
        )
        assertEquals(1.0f, u.fraction!!, 1e-4f)
    }

    @Test fun `parseSpeedMiB normalizes GiB MiB KiB`() {
        assertEquals(1024f, parseSpeedMiB("1GiB/s"), 1e-3f)
        assertEquals(1.17f, parseSpeedMiB("1.17MiB/s"), 1e-3f)
        assertEquals(0.5f, parseSpeedMiB("512KiB/s"), 1e-3f)
        assertEquals(0f, parseSpeedMiB("—"), 1e-3f)
        assertEquals(0f, parseSpeedMiB(null), 1e-3f)
    }

    @Test fun `parseBytes accepts IEC and SI`() {
        assertEquals(15_970_000L, parseBytes("15.97MB"))        // SI: 15.97 * 1e6
        assertEquals(16_745_758L, parseBytes("15.97MiB"))       // IEC: 15.97 * 1024^2 = 16,745,758.72
        assertNull(parseBytes("—"))
    }
}
