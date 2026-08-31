package com.sieve.app.di

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import kotlinx.coroutines.withContext
import com.sieve.app.settings.AppSettings
import com.sieve.data.db.SieveDatabase
import com.sieve.engine.EngineInit
import com.sieve.engine.repo.YoutubeDLClientImpl
import com.sieve.engine.repo.YtDlpEngine
import com.sieve.engine.repo.YtDlpEngineImpl
import com.sieve.engine.update.GithubReleaseApiImpl
import com.sieve.queue.service.JobDriver
import com.sieve.queue.service.QueueManager
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
        encoderDetector = EncoderDetector(AndroidVideoEncoderProbe(ffmpegEncodersStdout)) {
            Runtime.getRuntime().availableProcessors()
        }

        engine = YtDlpEngineImpl(YoutubeDLClientImpl(app), GithubReleaseApiImpl())

        val db = Room.databaseBuilder(app, SieveDatabase::class.java, "sieve.db").build()
        val persistence = com.sieve.queue.persist.RoomQueuePersistence(db.queueDao())
        val output = StorageModule.provideOutputLocationProvider(app, prefs)

        val dlPort = RealDownloadPort(engine)
        val txPort = RealTranscodePort(ffmpegBinaryPath)
        val manager = QueueManager(
            JobDriver(dlPort, txPort), dlPort, txPort, persistence, output, SystemClock(),
        )
        queue = QueueRepository.create(app, manager, appScope)
        initialized = true
    }

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
