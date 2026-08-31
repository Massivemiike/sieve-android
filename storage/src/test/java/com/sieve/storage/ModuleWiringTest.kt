package com.sieve.storage

import com.sieve.queue.service.OutputLocationProvider
import kotlin.test.Test
import kotlin.test.assertNotNull

class ModuleWiringTest {
    @Test fun `queue seam type is visible from storage module`() {
        // Compile-time proof that :storage can see :queue's seam.
        val klass: Class<*> = OutputLocationProvider::class.java
        assertNotNull(klass.name)
    }
}
