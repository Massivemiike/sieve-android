package com.sieve.app.di

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import kotlinx.coroutines.withContext
import com.sieve.app.settings.AppPrefs
import com.sieve.app.settings.AppSettings
import com.sieve.app.settings.CookiesStore
import com.sieve.data.db.SieveDatabase
import com.sieve.engine.EngineInit
import com.sieve.engine.repo.YoutubeDLClientImpl
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.engine.repo.YtDlpEngineImpl
import com.sieve.engine.update.GithubReleaseApiImpl
import com.sieve.queue.core.QueueState
import com.sieve.queue.core.awaitNoActiveDownload
import com.sieve.queue.service.JobDriver
import com.sieve.queue.service.QueueManager
import com.sieve.queue.service.QueueNotification
import com.sieve.queue.service.QueueRepository
import com.sieve.queue.service.RealDownloadPort
import com.sieve.queue.service.RealTranscodePort
import com.sieve.queue.service.SystemClock
import com.sieve.storage.StorageModule
import com.sieve.storage.library.SafDocumentStore
import com.sieve.storage.settings.StorageSettings
import com.sieve.transcode.detect.EncoderDetector
import com.sieve.transcode.detect.android.AndroidVideoEncoderProbe
import com.sieve.transcode.runner.android.FfmpegBinary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The process-wide object graph. Built once in [com.sieve.app.SieveApp.onCreate]. Assembles the four
 * library modules into a working app and installs the [QueueRepository] singleton the UI drives.
 */
object AppGraph {

    lateinit var prefs: DataStore<Preferences>; private set
    lateinit var appSettings: AppSettings; private set
    lateinit var storageSettings: StorageSettings; private set
    lateinit var engine: YtDlpEngine; private set
    lateinit var queue: QueueRepository; private set
    lateinit var documentStore: SafDocumentStore; private set
    lateinit var cookiesStore: CookiesStore; private set
    lateinit var encoderDetector: EncoderDetector; private set
    lateinit var ffmpegBinaryPath: String; private set
    var ffmpegEncodersStdout: String = ""; private set

    private lateinit var appContext: Context

    @Volatile private var initialized = false

    @Synchronized
    fun init(app: Application) {
        if (initialized) return
        appContext = app
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        EngineInit.initialize(app)
        ffmpegBinaryPath = FfmpegBinary.path(app)
        ffmpegEncodersStdout = EngineBootstrap.captureFfmpegEncoders(ffmpegBinaryPath)

        prefs = PreferenceDataStoreFactory.create(scope = ioScope) {
            File(app.filesDir, "sieve.preferences_pb")
        }
        appSettings = AppSettings(prefs)
        storageSettings = StorageSettings(prefs)
        documentStore = SafDocumentStore(app)
        cookiesStore = CookiesStore(
            dir = app.filesDir,
            open = { app.contentResolver.openInputStream(Uri.parse(it)) },
            lastModifiedOf = { documentLastModified(app, it) },
        )
        encoderDetector = EncoderDetector(AndroidVideoEncoderProbe(ffmpegEncodersStdout)) {
            Runtime.getRuntime().availableProcessors()
        }

        // No ffmpegLocation: the youtubedl-android :ffmpeg companion (initialized in EngineInit)
        // provides ffmpeg to yt-dlp. Passing our own libsieveffmpeg.so via --ffmpeg-location makes
        // yt-dlp try to spawn it from its embedded Python, which deadlocks.
        engine = YtDlpEngineImpl(YoutubeDLClientImpl(app), GithubReleaseApiImpl())

        val db = Room.databaseBuilder(app, SieveDatabase::class.java, "sieve.db").build()
        val persistence = com.sieve.queue.persist.RoomQueuePersistence(db.queueDao())
        val output = StorageModule.provideOutputLocationProvider(app, prefs)

        val dlPort = RealDownloadPort(engine)
        val txPort = RealTranscodePort(ffmpegBinaryPath)
        // Start from the persisted caps (not the 3/1 defaults) so a restart-time drain already obeys them,
        // then follow later changes so the Settings steppers apply without an app restart.
        val initialPrefs = runCatching { runBlocking { appSettings.flow.first() } }.getOrDefault(AppPrefs())
        val manager = QueueManager(
            JobDriver(dlPort, txPort), dlPort, txPort, persistence, output, SystemClock(),
            initial = QueueState(maxDownloads = initialPrefs.maxDownloads, maxTranscodes = initialPrefs.maxTranscodes),
            // "Downloaded / Transcoded / Failed: <title>"; postDone never throws and skips quietly without the permission.
            onCompleted = { QueueNotification.postDone(app, it) },
            onFailed = { QueueNotification.postDone(app, it) },
        )
        queue = QueueRepository.create(app, manager, appScope)
        queue.followLimits(appSettings.flow.map { it.maxDownloads to it.maxTranscodes })
        autoUpdateYtDlp(ioScope)
        initialized = true
    }

    /**
     * Keeps yt-dlp current WITHOUT user action. The bundled binary (youtubedl-android 0.18.1 ships
     * yt-dlp 2025.11.12) is already too old for YouTube (SABR streaming → downloads fail out of the
     * box), so a fresh install MUST self-update before it can download. Throttled to once per 12h;
     * failures are silent (offline first launch just tries again next open, and Settings keeps the
     * manual Update button).
     *
     * The library's updater deletes and recreates the yt-dlp directory IN PLACE, so it must never run
     * underneath a live download: this first waits (up to 2 h) until no download is PREPARING/RUNNING
     * and skips the update if that never happens. The 12 h throttle is stamped only when the update
     * actually succeeded, so a failed or skipped attempt is retried on the next open.
     */
    private fun autoUpdateYtDlp(scope: CoroutineScope) {
        val key = androidx.datastore.preferences.core.longPreferencesKey("ytdlp_auto_updated_at")
        scope.launch {
            runCatching {
                val last = prefs.data.first()[key] ?: 0L
                if (System.currentTimeMillis() - last < 12 * 60 * 60 * 1000L) return@launch
                if (!queue.state.awaitNoActiveDownload(timeoutMs = 2 * 60 * 60 * 1000L, settleMs = 5_000L)) {
                    android.util.Log.i("SieveEngine", "yt-dlp auto-update skipped: downloads still running")
                    return@launch
                }
                val result = engine.doUpdate(com.sieve.engine.update.UpdateChannel.STABLE)
                if (result.ok) {
                    prefs.edit { it[key] = System.currentTimeMillis() }
                    android.util.Log.i("SieveEngine", "yt-dlp auto-update done (now ${engine.version()})")
                } else {
                    android.util.Log.w("SieveEngine", "yt-dlp auto-update failed: ${result.output}")
                }
            }.onFailure { android.util.Log.w("SieveEngine", "yt-dlp auto-update skipped: ${it.message}") }
        }
    }

    /** The picked document's own last-modified time (providers that expose it), else null. */
    private fun documentLastModified(ctx: Context, uri: String): Long? = runCatching {
        ctx.contentResolver.query(
            Uri.parse(uri), arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }.getOrNull()

    /**
     * Materializes a SAF/content source into a real file path ffmpeg can read (native processes can't
     * open a content:// URI). Copies into cacheDir; the transcode work file lands under the SAF sink
     * via the queue's finalize.
     */
    suspend fun materializeSource(uriStr: String, name: String): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ext = name.substringAfterLast('.', "mp4")
        val dst = File(appContext.cacheDir, "tx-src-${System.nanoTime()}.$ext")
        appContext.contentResolver.openInputStream(Uri.parse(uriStr)).use { input ->
            requireNotNull(input) { "cannot open source $uriStr" }
            dst.outputStream().use { input.copyTo(it) }
        }
        dst.absolutePath
    }
}
