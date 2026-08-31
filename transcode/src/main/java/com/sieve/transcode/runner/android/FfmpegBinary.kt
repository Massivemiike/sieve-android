package com.sieve.transcode.runner.android

import android.content.Context
import java.io.File

/**
 * Resolves the packaged ffmpeg binary. It MUST be executed from `nativeLibraryDir` (named
 * `libffmpeg.so` so the packager keeps it and the loader marks it executable) — Android blocks
 * exec of files written to app data/cache. Requires `android:extractNativeLibs="true"` +
 * `useLegacyPackaging = true` so the `.so` is unpacked to disk rather than mmap'd from the APK.
 */
object FfmpegBinary {
    fun path(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath
}
