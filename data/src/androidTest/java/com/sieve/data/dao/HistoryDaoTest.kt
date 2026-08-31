package com.sieve.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sieve.data.db.DownloadHistoryEntity
import com.sieve.data.db.SieveDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDaoTest {
    private lateinit var db: SieveDatabase
    private lateinit var dao: HistoryDao

    private fun h(url: String, title: String = "t", at: Long) =
        DownloadHistoryEntity(url = url, title = title, site = "yt", channel = "c", format = "best", downloadedAt = at)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SieveDatabase::class.java).build()
        dao = db.historyDao()
    }

    @After fun teardown() = db.close()

    @Test fun insertAndTrim_caps_at_200() = runTest {
        repeat(210) { dao.insertAndTrim(h("u$it", at = it.toLong()), cap = 200) }
        assertEquals(200, dao.getAll().size)
        assertNull(dao.getAll().firstOrNull { it.url == "u0" })
    }

    @Test fun urlCounts_flags_dupes() = runTest {
        dao.insert(h("dup", at = 1)); dao.insert(h("dup", at = 2)); dao.insert(h("once", at = 3))
        val counts = dao.urlCounts().associate { it.url to it.cnt }
        assertEquals(2, counts["dup"])
        assertNull(counts["once"])
    }

    @Test fun search_matches_title_and_site() = runTest {
        dao.insert(h("u1", title = "Cats compilation", at = 1))
        dao.insert(h("u2", title = "Dogs", at = 2))
        assertEquals(listOf("u1"), dao.search("cat").first().map { it.url })
    }

    @Test fun priorForUrl_returns_latest() = runTest {
        dao.insert(h("u", at = 1)); dao.insert(h("u", at = 5))
        assertEquals(5L, dao.priorForUrl("u")!!.downloadedAt)
    }
}
