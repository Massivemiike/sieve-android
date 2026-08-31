package com.sieve.queue.persist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sieve.data.db.SieveDatabase
import com.sieve.queue.core.DownloadStatus
import com.sieve.queue.core.JobSpec
import com.sieve.queue.core.OutputRequest
import com.sieve.queue.core.QueueJob
import com.sieve.queue.core.UnifiedProgress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomQueuePersistenceTest {
    private lateinit var db: SieveDatabase
    private lateinit var p: RoomQueuePersistence

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SieveDatabase::class.java).build()
        p = RoomQueuePersistence(db.queueDao())
    }

    @After fun teardown() = db.close()

    @Test fun round_trip_running_download_loads_as_queued() = runTest {
        p.upsert(
            QueueJob(
                "dl-1", JobSpec.Download("https://x", listOf("-f", "best")),
                OutputRequest("Downloads/Sieve", "%(title)s.%(ext)s"), status = DownloadStatus.RUNNING,
                progress = UnifiedProgress(fraction = 0.7f),
            ),
        )
        val loaded = p.loadAll()
        assertEquals(1, loaded.size)
        assertEquals(DownloadStatus.QUEUED, loaded[0].status) // persist projection
        assertEquals(listOf("-f", "best"), (loaded[0].spec as JobSpec.Download).engineArgs)
    }
}
