package com.sieve.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL

/**
 * One-time youtubedl-android initialization. `:app` calls this at startup — it cannot touch the
 * youtubedl-android dependency directly (an internal `implementation` dep of `:engine`), so the
 * engine module owns the init call.
 */
object EngineInit {
    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        try {
            YoutubeDL.getInstance().init(context)
        } catch (e: Exception) {
            // Already initialized (or a partial init from a prior launch) — safe to ignore.
        }
        initialized = true
    }

    val isInitialized: Boolean get() = initialized
}
