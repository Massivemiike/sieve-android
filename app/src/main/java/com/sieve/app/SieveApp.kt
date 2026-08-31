package com.sieve.app

import android.app.Application
import androidx.work.Configuration
import com.sieve.app.di.AppGraph
import com.sieve.app.update.UpdateWorker

class SieveApp : Application(), Configuration.Provider {
    // Enables WorkManager on-demand initialization (works on device and in Robolectric).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        runCatching { UpdateWorker.schedule(this) }
    }
}
