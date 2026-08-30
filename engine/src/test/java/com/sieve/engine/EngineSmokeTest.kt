package com.sieve.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Task 0: proves the pure-JVM unit-test source set is wired without Robolectric. */
class EngineSmokeTest {
    @Test fun sanity() = assertEquals(4, 2 + 2)
}
