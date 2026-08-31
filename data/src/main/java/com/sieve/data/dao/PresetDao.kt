package com.sieve.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sieve.data.db.CustomPresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM custom_preset") fun observeAll(): Flow<List<CustomPresetEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: CustomPresetEntity)
    @Query("DELETE FROM custom_preset WHERE id = :id") suspend fun deleteById(id: String)
    @Query("SELECT * FROM custom_preset") suspend fun getAll(): List<CustomPresetEntity>
}
