package com.sieve.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "download_task", indices = [Index("position"), Index("status")])
data class DownloadTaskEntity(
    @PrimaryKey val id: String,             // keep "dl-<ts>-<n>"
    val position: Long,
    val url: String,
    val kind: String,                        // "DOWNLOAD" | "TRANSCODE"
    val status: String,                      // DownloadStatus.name; :data stays domain-agnostic
    val title: String = "",
    val channel: String = "",
    val site: String = "Unknown",
    val format: String = "",
    val durationSec: Long? = null,
    val sizeBytes: Long? = null,
    val progress: Float = 0f,                // persisted 0 or 1 only (matches desktop)
    val thumbnailUrl: String = "",
    val args: List<String> = emptyList(),
    // transcode-only fields (null for downloads)
    val inputPath: String? = null,
    val presetArgs: List<String>? = null,
    val totalDurationSec: Double? = null,
    val usedHardwareEncoder: Boolean = false,
    val outputDirLabel: String = "",
    val outputTemplate: String = "",
    val attempt: Int = 0,
    val nextEligibleAt: Long = 0L,           // transient auto-retry backoff; must survive process death
    val error: String? = null,
    val filePath: String? = null,
    val notes: String? = null,
    val colorTag: String? = null,
    val pinned: Boolean = false,
    val completedAt: Long? = null,
)

@Entity(tableName = "download_history", indices = [Index("url"), Index("downloadedAt")])
data class DownloadHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String = "",
    val channel: String = "",
    val site: String = "",
    val format: String = "",
    val sizeBytes: Long? = null,
    val filePath: String? = null,       // content:// uri on Android
    val thumbnailUrl: String = "",
    val durationSec: Long? = null,
    val downloadedAt: Long,             // epoch millis
)

@Entity(tableName = "custom_preset")
data class CustomPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String = "custom",
    val config: String,                 // opaque JSON blob
)

@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey val url: String,
    val title: String = "",
    val thumbnailUrl: String? = null,
    val addedAt: Long,
)

@Entity(tableName = "subscription")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val site: String = "",
    val addedAt: Long,
    val lastCheckedAt: Long? = null,
)
