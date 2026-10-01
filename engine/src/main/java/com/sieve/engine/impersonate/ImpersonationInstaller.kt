package com.sieve.engine.impersonate

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Puts curl_cffi into youtubedl-android's Python so yt-dlp can impersonate a browser's TLS
 * fingerprint, as the desktop yt-dlp.exe does. Sites that check it (Vimeo's player pages among
 * them) answer 401/403 to plain Python TLS. The zip is built by `engine/build-impersonate/build.sh`.
 *
 * The bundle's `_cffi_backend` is compiled for CPython 3.12, so it only goes into a `python3.12`
 * layout: if the library ever ships another Python, this skips and yt-dlp keeps plain urllib (the
 * pre-impersonation behaviour) until the bundle is rebuilt.
 */
object ImpersonationInstaller {
    const val ASSET_ZIP = "impersonate/sieve-impersonate.zip"
    const val ASSET_VERSION = "impersonate/VERSION"
    internal const val MARKER = ".sieve-impersonate"

    sealed interface Outcome {
        data object Installed : Outcome
        data object AlreadyCurrent : Outcome
        data class Skipped(val reason: String) : Outcome
    }

    /** youtubedl-android unpacks its Python under `<noBackupFilesDir>/youtubedl-android/packages/python`. */
    fun sitePackages(noBackupDir: File): File =
        File(noBackupDir, "youtubedl-android/packages/python/usr/lib/python3.12/site-packages")

    /**
     * Unpacks the bundle into [sitePackages] unless the marker already names [version]. [version] must
     * change whenever the zip's bytes do (build.sh appends the zip's SHA-256 to it), or a rebuilt bundle
     * would never replace an installed one. The marker is written last, so an interrupted install is
     * redone on the next start. The library re-extracts its Python (dropping site-packages) when it
     * updates, which also triggers a reinstall.
     */
    fun install(sitePackages: File, version: String, openZip: () -> InputStream): Outcome {
        val pythonLib = sitePackages.parentFile
        if (pythonLib == null || !pythonLib.isDirectory) return Outcome.Skipped("no python3.12 at $pythonLib")
        val wanted = version.trim()
        val marker = File(sitePackages, MARKER)
        if (marker.isFile && marker.readText().trim() == wanted) return Outcome.AlreadyCurrent

        sitePackages.mkdirs()
        marker.delete()
        val root = sitePackages.canonicalFile
        // Clear every top-level entry this bundle owns first, so no file from an older bundle survives.
        val owned = openZip().use { input ->
            ZipInputStream(input).use { zip -> generateSequence { zip.nextEntry }.map { it.name.substringBefore('/') }.toSet() }
        }
        for (name in owned) resolveInside(root, name).deleteRecursively()
        // Metadata directories carry the version in their NAME (curl_cffi-0.16.3.dist-info), so a bumped
        // component would leave the old one beside the new one, and importlib.metadata.version() may answer
        // with either. Drop every other version of each distribution this bundle ships (this also cleans
        // installs made before this ran, which no manifest could).
        val shipped = owned.mapNotNull(::distributionOf).toSet()
        sitePackages.list().orEmpty().filter { distributionOf(it) in shipped }.forEach { resolveInside(root, it).deleteRecursively() }

        openZip().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = resolveInside(root, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        marker.writeText("$wanted\n")
        return Outcome.Installed
    }

    /** The normalized distribution of a `<name>-<version>.dist-info` / `.egg-info` entry; null for anything else. */
    private fun distributionOf(entry: String): String? {
        val base = listOf(".dist-info", ".egg-info").firstNotNullOfOrNull { suffix -> entry.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix) }
            ?: return null
        return base.substringBefore('-').lowercase().replace('.', '_')
    }

    private fun resolveInside(root: File, name: String): File {
        val f = File(root, name).canonicalFile
        if (f == root || !f.path.startsWith(root.path + File.separator)) {
            throw IOException("zip entry outside site-packages: $name")
        }
        return f
    }
}
