package com.sieve.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sieve.data.db.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite ORDER BY addedAt DESC") fun observeAll(): Flow<List<FavoriteEntity>>
    @Query("SELECT url FROM favorite") fun observeUrls(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(f: FavoriteEntity)
    @Query("DELETE FROM favorite WHERE url = :url") suspend fun deleteByUrl(url: String)
    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE url = :url)") suspend fun exists(url: String): Boolean
}
