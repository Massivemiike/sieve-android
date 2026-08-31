package com.sieve.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sieve.data.db.DownloadTaskEntity
import com.sieve.data.db.SieveDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueDaoTest {
    private lateinit var db: SieveDatabase
    private lateinit var dao: QueueDao

    private fun task(id: String, pos: Long, s: String = "QUEUED", pinned: Boolean = false) =
        DownloadTaskEntity(id = id, position = pos, url = "u$id", kind = "DOWNLOAD", status = s, pinned = pinned)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SieveDatabase::class.java).build()
        dao = db.queueDao()
    }

    @After fun teardown() = db.close()

    @Test fun ordered_by_pinned_then_position() = runTest {
        dao.upsertAll(listOf(task("a", 0), task("b", 1, pinned = true), task("c", 2)))
        val ids = dao.observeOrdered().first().map { it.id }
        assertEquals(listOf("b", "a", "c"), ids)
    }

    @Test fun update_args_only_when_queued() = runTest {
        dao.upsert(task("a", 0, "RUNNING"))
        assertEquals(0, dao.updateArgsIfQueued("a", listOf("-N", "4")))
        dao.upsert(task("b", 1, "QUEUED"))
        assertEquals(1, dao.updateArgsIfQueued("b", listOf("-N", "4")))
        assertEquals(listOf("-N", "4"), dao.getAll().first { it.id == "b" }.args)
    }

    @Test fun prune_removes_old_terminal() = runTest {
        dao.upsert(task("a", 0, "COMPLETED").copy(completedAt = 100))
        dao.upsert(task("b", 1, "QUEUED"))
        assertEquals(1, dao.prune(cutoff = 500))
        assertEquals(listOf("b"), dao.getAll().map { it.id })
    }
}
