package com.sieve.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Verifies a downloaded APK before install: its sha256 must match the manifest, and its signing
 * certificate must match the currently-installed app (a differently-signed APK — e.g. from a
 * hijacked mirror — is rejected; PackageInstaller would reject it too, but we fail fast).
 */
object ApkVerifier {

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    fun matchesSha256(file: File, expected: String): Boolean =
        sha256(file).equals(expected.trim(), ignoreCase = true)

    fun installedSignerSha256(context: Context, packageName: String): String? = runCatching {
        certSha256(packageInfoWithSigners(context.packageManager, packageName))
    }.getOrNull()

    /** True when [apkFile] is signed by the same certificate as the installed app. */
    fun apkSignerMatchesInstalled(context: Context, apkFile: File): Boolean = runCatching {
        val apkSigner = archiveInfoWithSigners(context.packageManager, apkFile.absolutePath)?.let { certSha256(it) }
            ?: return false
        val installed = installedSignerSha256(context, context.packageName) ?: return false
        apkSigner == installed
    }.getOrDefault(false)

    // ── internals ──────────────────────────────────────────────────────
    private fun packageInfoWithSigners(pm: PackageManager, pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)

    private fun archiveInfoWithSigners(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)

    private fun certSha256(info: PackageInfo): String? {
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION") info.signatures?.firstOrNull()
        } ?: return null
        return MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
