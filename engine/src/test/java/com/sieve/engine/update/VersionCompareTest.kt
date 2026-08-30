package com.sieve.engine.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionCompareTest {
    @Test fun newer() = assertTrue(VersionCompare.isNewer("2025.08.20", "2025.07.01"))
    @Test fun equalNotNewer() = assertFalse(VersionCompare.isNewer("2025.08.20", "2025.08.20"))
    @Test fun revComponent() = assertTrue(VersionCompare.isNewer("2025.08.20.1", "2025.08.20"))
    @Test fun olderNotNewer() = assertFalse(VersionCompare.isNewer("2025.07.01", "2025.08.20"))
    @Test fun unparsableNoUpdate() = assertFalse(VersionCompare.isNewer("nightly", "2025.08.20"))
    @Test fun nullsNoUpdate() = assertFalse(VersionCompare.isNewer(null, "2025.08.20"))
}
