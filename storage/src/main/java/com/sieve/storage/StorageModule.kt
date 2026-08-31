package com.sieve.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.sieve.queue.service.OutputLocationProvider
import com.sieve.storage.service.DefaultSinkSelector
import com.sieve.storage.service.JavaWorkDirFs
import com.sieve.storage.service.SafOutputProvider
import com.sieve.storage.settings.AndroidTreePermissionOracle
import com.sieve.storage.settings.StorageSettings
import com.sieve.storage.settings.TreeValidator
import com.sieve.storage.sink.AppFilesSink
import com.sieve.storage.sink.MediaStoreDownloadsSink
import com.sieve.storage.sink.SafTreeSink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Wires the storage subsystem into an [OutputLocationProvider] the `:app` hands to the QueueManager
 * (replacing the v1 `RealOutputProviderV1` stub). :app calls this once at DI time.
 */
object StorageModule {

    fun provideOutputLocationProvider(
        context: Context,
        settingsStore: DataStore<Preferences>,
    ): OutputLocationProvider {
        val settings = StorageSettings(settingsStore)
        val oracle = AndroidTreePermissionOracle(context)

        val selector = DefaultSinkSelector(
            treeSinkFactory = {
                val uri = runBlocking { settings.prefs.first() }.outputTreeUri
                if (uri != null && TreeValidator.validate(uri, oracle)) SafTreeSink(context, Uri.parse(uri)) else null
            },
            mediaStoreFactory = {
                val useMs = runBlocking { settings.prefs.first() }.useMediaStore
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && useMs) MediaStoreDownloadsSink(context) else null
            },
            appFilesFactory = { AppFilesSink(context) },
        )

        return SafOutputProvider(
            filesDirPath = context.filesDir.absolutePath,
            fs = JavaWorkDirFs(),
            selector = selector,
        )
    }
}
