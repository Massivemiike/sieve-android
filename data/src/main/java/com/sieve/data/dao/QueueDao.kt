package com.sieve.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.sieve.data.db.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Query("SELECT * FROM download_task ORDER BY pinned DESC, position ASC")
    fun observeOrdered(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_task ORDER BY position ASC")
    suspend fun getAll(): List<DownloadTaskEntity>

    @Upsert suspend fun upsert(task: DownloadTaskEntity)
    @Upsert suspend fun upsertAll(tasks: List<DownloadTaskEntity>)

    @Query("UPDATE download_task SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE download_task SET args = :args WHERE id = :id AND status = 'QUEUED'")
    suspend fun updateArgsIfQueued(id: String, args: List<String>): Int

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_task WHERE status IN ('COMPLETED','CANCELLED') AND completedAt < :cutoff")
    suspend fun prune(cutoff: Long): Int

    @Transaction
    suspend fun replaceAll(tasks: List<DownloadTaskEntity>) { clear(); upsertAll(tasks) }

    @Query("DELETE FROM download_task") suspend fun clear()
}
