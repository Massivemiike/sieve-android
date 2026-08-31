package com.sieve.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class UpdateWorkerTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun availableUpdatePostsNotificationAndSucceeds() = runBlocking {
        var notified: UpdateManifest? = null
        UpdateWorker.statusProvider = { UpdateStatus.Available(UpdateManifest(99, "9.9", "u", "s")) }
        UpdateWorker.notifier = { _, m -> notified = m }

        val worker = TestListenableWorkerBuilder<UpdateWorker>(ctx).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(99, notified?.versionCode)
        Unit
    }

    @Test
    fun unknownStatusSucceedsWithoutNotifying() = runBlocking {
        var notified = false
        UpdateWorker.statusProvider = { UpdateStatus.Unknown }
        UpdateWorker.notifier = { _, _ -> notified = true }

        val worker = TestListenableWorkerBuilder<UpdateWorker>(ctx).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(notified)
        Unit
    }
}
