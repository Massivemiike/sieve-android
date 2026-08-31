package com.sieve.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sieve.data.db.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscription ORDER BY addedAt DESC") fun observeAll(): Flow<List<SubscriptionEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(s: SubscriptionEntity)
    @Query("DELETE FROM subscription WHERE id = :id") suspend fun deleteById(id: String)
    @Query("UPDATE subscription SET lastCheckedAt = :ts WHERE id = :id") suspend fun updateLastChecked(id: String, ts: Long)
}
