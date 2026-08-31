package com.sieve.data.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room type converters. `:data` is domain-agnostic — it only knows about primitives and JSON lists,
 * never the `com.sieve.queue.core` enums (that would make `:data` depend on `:queue`, which already
 * depends on `:data`). Status is stored as a plain String; the `:queue` mapping layer (Task 10)
 * converts String ↔ DownloadStatus.
 */
class Converters {
    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type

    @TypeConverter fun fromStringList(list: List<String>?): String = gson.toJson(list ?: emptyList<String>())
    @TypeConverter fun toStringList(json: String?): List<String> =
        if (json.isNullOrBlank()) emptyList() else gson.fromJson(json, listType)
}
