package com.sieve.engine.repo

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

/** The only class that touches youtubedl-android. Validated end-to-end by the Phase-0 spike. */
class YoutubeDLClientImpl(private val ctx: Context) : YoutubeDLClient {

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
}
