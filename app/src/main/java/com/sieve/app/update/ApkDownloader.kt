package com.sieve.app.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Streams the update APK into the app's external files dir (private, but a real file the installer
 *  session can read). Returns the file or null on failure. */
class ApkDownloader(private val context: Context) {

    suspend fun download(url: String, versionCode: Int, onProgress: (Float) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val dst = File(dir, "sieve-$versionCode.apk")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "Sieve")
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                if (conn.responseCode !in 200..299) return@runCatching null
                val total = conn.contentLengthLong.takeIf { it > 0 }
                conn.inputStream.use { input ->
                    dst.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            readTotal += n
                            if (total != null) onProgress((readTotal.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                dst
            }.getOrNull()
        }
}
