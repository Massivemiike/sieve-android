package com.sieve.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sieve.app.di.AppGraph
import com.sieve.queue.service.QueueRepository
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AppGraphInstrumentedTest {

    @Test
    fun graphInstallsQueueRepositoryAndResolvesFfmpeg() {
        // SieveApp.onCreate has already run AppGraph.init on the real Application under test.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = QueueRepository.get(ctx) // throws if install() never ran
        assertNotNull(repo)
        assertTrue(AppGraph.ffmpegBinaryPath.endsWith("libffmpeg.so"))
    }
}
