package com.sieve.transcode.runner.android

import android.content.Context
import java.io.File

/**
 * Resolves the packaged ffmpeg binary. It MUST be executed from `nativeLibraryDir` (named
 * `lib*.so` so the packager keeps it and the loader marks it executable) — Android blocks
 * exec of files written to app data/cache. Requires `packaging.jniLibs.useLegacyPackaging = true` (set in :app, which
 * packages the APK, and in this module) so the `.so` is unpacked to disk rather than mmap'd from the APK. AGP then writes
 * `android:extractNativeLibs="true"` into the merged manifest itself; do not declare it in a source manifest (AGP 9 warns).
 *
 * Named `libsieveffmpeg.so` (not `libffmpeg.so`) to avoid a jniLibs collision with the
 * youtubedl-android `:ffmpeg` companion, which ships its own `libffmpeg.so` for yt-dlp's
 * post-processing. This is Sieve's OWN full-gpl build (HW mediacodec + loudnorm) used by :transcode.
 */
object FfmpegBinary {
    fun path(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libsieveffmpeg.so").absolutePath
}
