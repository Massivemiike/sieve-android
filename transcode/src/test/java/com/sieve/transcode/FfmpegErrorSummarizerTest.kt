package com.sieve.transcode

import com.sieve.transcode.runner.FfmpegErrorSummarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 16: ordered stderr → summary rules, MediaCodec cluster first. */
class FfmpegErrorSummarizerTest {

    @Test fun E1_hardwareInitFailureFirst() {
        val msg = FfmpegErrorSummarizer.summarize("[h264_mediacodec @ 0x] Cannot open encoder\nother noise")
        assertEquals("Hardware (MediaCodec) encoder failed — retrying on software (libx264/libx265).", msg)
    }

    @Test fun isHardwareEncoderInitFailure_detectsMediaCodec() {
        assertTrue(FfmpegErrorSummarizer.isHardwareEncoderInitFailure("[h264_mediacodec] Cannot open encoder"))
        assertTrue(FfmpegErrorSummarizer.isHardwareEncoderInitFailure("Failed to configure MediaCodec"))
        assertFalse(FfmpegErrorSummarizer.isHardwareEncoderInitFailure("some unrelated warning"))
    }

    @Test fun E2_inputNotFound() {
        assertEquals("Input file not found or inaccessible",
            FfmpegErrorSummarizer.summarize("open /x: No such file or directory"))
    }

    @Test fun E3_permissionDenied() {
        assertEquals("Permission denied — output folder not writable",
            FfmpegErrorSummarizer.summarize("Error: Permission denied"))
    }

    @Test fun E4_unknownEncoderExtractsName() {
        assertEquals("Unknown encoder 'libfoo' — switch to a different encoder",
            FfmpegErrorSummarizer.summarize("Unknown encoder 'libfoo'"))
    }

    @Test fun E5_invalidArgument() {
        assertEquals("Invalid ffmpeg arguments — check preset/raw args",
            FfmpegErrorSummarizer.summarize("Invalid argument"))
    }

    @Test fun E6_alreadyExists() {
        assertEquals("Output file already exists",
            FfmpegErrorSummarizer.summarize("File 'out.mp4' already exists. Exiting."))
    }

    @Test fun E7_diskFull() {
        assertEquals("Disk full", FfmpegErrorSummarizer.summarize("write error: No space left on device"))
    }

    @Test fun E8_fallbackToLastErrorLine() {
        val stderr = "frame=  10 fps=5\nsomething odd happened\nconversion failed here\ndone"
        assertEquals("conversion failed here", FfmpegErrorSummarizer.summarize(stderr))
    }

    @Test fun E9_emptyStderr() {
        assertEquals("Unknown ffmpeg failure", FfmpegErrorSummarizer.summarize("   \n  \n"))
    }
}
