package com.sieve.engine.repo

import android.content.Context
import android.util.Log
import com.sieve.engine.update.YtDlpRecordGuard
import com.sieve.engine.update.YtDlpZipapp
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** The only class that touches youtubedl-android. Validated end-to-end by the Phase-0 spike. */
class YoutubeDLClientImpl(private val ctx: Context) : YoutubeDLClient {

    /** `<noBackupFilesDir>/youtubedl-android/yt-dlp/yt-dlp`: the file the library runs (its own constants name every part of the path). */
    private val ytDlpFile: File
        get() = File(File(File(ctx.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin)

    private val recordGuard by lazy {
        YtDlpRecordGuard(
            record = LibraryVersionRecord(ctx.getSharedPreferences(LibraryVersionRecord.PREFS_NAME, Context.MODE_PRIVATE)),
            installedVersion = { YtDlpZipapp.version(ytDlpFile) },
            log = { Log.i(YtDlpRecordGuard.TAG, it) },
        )
    }

    override fun version(): String? = YoutubeDL.getInstance().version(ctx)

    override fun execute(
        processId: String,
        url: String,
        options: List<String>,
        onProgress: (Float, Long, String) -> Unit,
    ): ExecResult {
        val req = YoutubeDLRequest(url)
        // Pass the args VERBATIM. Do NOT use addOption() per-token: youtubedl-android stores each
        // option as a map key (LinkedHashMap<String, List<String>>), so adding a flag and its value
        // as two separate single-arg addOption() calls makes each its own key with an empty value —
        // which breaks every flag+value pair (-f, -P, -o, --ffmpeg-location). addCommands() appends
        // the tokens as-is (customCommandList) before the url, exactly like a real argv.
        req.addCommands(options)
        val resp = YoutubeDL.getInstance().execute(req, processId) { p, e, l -> onProgress(p, e, l) }
        return ExecResult(resp.exitCode, resp.out, resp.err)
    }

    override fun destroy(processId: String): Boolean = YoutubeDL.getInstance().destroyProcessById(processId)

    override fun update(nightly: Boolean): String {
        val channel = if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
        return YoutubeDL.getInstance().updateYoutubeDL(ctx, channel)?.name ?: "UNKNOWN"
    }

    override fun repairVersionRecord(): YtDlpRecordGuard.Repair? = try {
        recordGuard.repairIfStale()
    } catch (e: Exception) {
        Log.w(YtDlpRecordGuard.TAG, "yt-dlp version record check skipped: ${e.message}")
        null
    }
}
