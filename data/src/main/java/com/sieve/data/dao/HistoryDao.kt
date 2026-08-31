package com.sieve.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.sieve.data.db.DownloadHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM download_history ORDER BY downloadedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<DownloadHistoryEntity>>

    @Insert suspend fun insert(entry: DownloadHistoryEntity): Long

    @Transaction
    suspend fun insertAndTrim(entry: DownloadHistoryEntity, cap: Int = 200) {
        insert(entry)
        trimTo(cap)
    }

    @Query(
        """DELETE FROM download_history WHERE id NOT IN
           (SELECT id FROM download_history ORDER BY downloadedAt DESC LIMIT :cap)""",
    )
    suspend fun trimTo(cap: Int)

    @Query(
        """SELECT * FROM download_history WHERE
           title LIKE '%'||:q||'%' OR channel LIKE '%'||:q||'%' OR
           site LIKE '%'||:q||'%' OR format LIKE '%'||:q||'%'
           ORDER BY downloadedAt DESC""",
    )
    fun search(q: String): Flow<List<DownloadHistoryEntity>>

    @Query("SELECT url, COUNT(*) AS cnt FROM download_history GROUP BY url HAVING cnt > 1")
    suspend fun urlCounts(): List<UrlCount>

    @Query("SELECT * FROM download_history WHERE url = :url ORDER BY downloadedAt DESC LIMIT 1")
    suspend fun priorForUrl(url: String): DownloadHistoryEntity?

    @Query("SELECT COUNT(*) FROM download_history WHERE downloadedAt >= :ts")
    fun countSince(ts: Long): Flow<Int>

    @Query("SELECT MAX(downloadedAt) FROM download_history")
    fun latestAt(): Flow<Long?>

    @Query("DELETE FROM download_history WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM download_history") suspend fun clear()
    @Query("SELECT * FROM download_history ORDER BY downloadedAt DESC") suspend fun getAll(): List<DownloadHistoryEntity>
}

data class UrlCount(val url: String, val cnt: Int)
