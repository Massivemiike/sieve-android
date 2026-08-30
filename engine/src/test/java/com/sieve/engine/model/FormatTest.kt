package com.sieve.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun durationHms() {
        assertEquals("3:32", Format.duration(212.0))
        assertEquals("1:01:01", Format.duration(3661.0))
        assertEquals("0:59", Format.duration(59.9))
        assertEquals("", Format.duration(0.0))
        assertEquals("", Format.duration(null))
    }

    @Test fun bytesHasTbSizeDoesNot() {
        assertEquals("1.5 TB", Format.bytes(1_500_000_000_000L))
        assertEquals("1500.0 GB", Format.size(1_500_000_000_000L))
    }

    @Test fun bytesSmallTierIsInteger() = assertEquals("500 B", Format.bytes(500L))

    @Test fun sizeFalsyIsQuestion() {
        assertEquals("?", Format.size(null))
        assertEquals("?", Format.size(0L))
    }

    @Test fun speedMiBUnits() {
        assertEquals(2048.0, Format.speedMiB("2GiB/s"), 1e-9)
        assertEquals(0.5, Format.speedMiB("512KiB/s"), 1e-9)
        assertEquals(0.0, Format.speedMiB(null), 1e-9)
        assertEquals(0.0, Format.speedMiB("Unknown"), 1e-9)
    }
}
