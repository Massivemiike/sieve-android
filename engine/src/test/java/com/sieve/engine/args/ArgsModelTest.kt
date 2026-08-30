package com.sieve.engine.args

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ArgsModelTest {
    @Test fun toggleValueVariantsDistinct() {
        assertEquals(ToggleValue.Text("x"), ToggleValue.Text("x"))
        assertNotEquals(ToggleValue.Text("x"), ToggleValue.Text("y"))
        assertNotEquals<ToggleValue>(ToggleValue.On, ToggleValue.Off)
    }

    @Test fun defaults() {
        assertEquals(1, EngineSettings().concurrentFragments)
        assertFalse(DownloadArgsOptions(format = "best").audioOnly)
    }
}
