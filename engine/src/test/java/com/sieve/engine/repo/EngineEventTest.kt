package com.sieve.engine.repo

import com.sieve.engine.model.DownloadProgress
import kotlin.test.Test
import kotlin.test.assertTrue

class EngineEventTest {
    @Test fun exhaustiveWhenCoversAllArms() {
        val events = listOf(
            EngineEvent.Progress(DownloadProgress(0.5f)),
            EngineEvent.Log("x", null, false),
            EngineEvent.Completed(0),
            EngineEvent.Failed("e"),
            EngineEvent.Cancelled,
        )
        events.forEach { e ->
            val tag = when (e) {
                is EngineEvent.Progress -> "p"
                is EngineEvent.Log -> "l"
                is EngineEvent.Completed -> "c"
                is EngineEvent.Failed -> "f"
                EngineEvent.Cancelled -> "x"
            }
            assertTrue(tag.isNotEmpty())
        }
    }
}
