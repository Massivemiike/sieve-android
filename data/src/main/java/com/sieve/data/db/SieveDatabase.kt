package com.sieve.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sieve.data.dao.FavoriteDao
import com.sieve.data.dao.HistoryDao
import com.sieve.data.dao.PresetDao
import com.sieve.data.dao.QueueDao
import com.sieve.data.dao.SubscriptionDao

@Database(
    entities = [
        DownloadTaskEntity::class, DownloadHistoryEntity::class,
        CustomPresetEntity::class, FavoriteEntity::class, SubscriptionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SieveDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao
    abstract fun historyDao(): HistoryDao
    abstract fun presetDao(): PresetDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun subscriptionDao(): SubscriptionDao
}
