package com.sieve.app.net

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Android 17's local-network permission, as far as it is a fact about the device (the decisions are in [LocalNetworkGate]).
 *
 * An app that targets API 37 may only open connections to LAN addresses (a NAS or media-server link, a `.local` name, a proxy on
 * 192.168.x.x) once `ACCESS_LOCAL_NETWORK` is granted; it is a runtime permission in the NEARBY_DEVICES group. Below Android 17
 * the permission does not exist: asking `checkSelfPermission` for it there answers "denied", so every question is answered
 * "not required" first, and it is never requested.
 */
object LocalNetworkAccess {
    /** `android.permission.ACCESS_LOCAL_NETWORK` (declared in the manifest; a test keeps the two equal). */
    const val PERMISSION: String = Manifest.permission.ACCESS_LOCAL_NETWORK

    /** The first API level that has the permission and enforces it (Android 17). */
    const val FIRST_API: Int = Build.VERSION_CODES.CINNAMON_BUN

    fun isRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= FIRST_API

    /** True when connections to the local network are allowed: below Android 17 always, otherwise when the permission is granted. */
    fun isGranted(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        !isRequired(sdkInt) || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Sieve's page in the system settings, where the Nearby devices permission is switched on once the system stops asking. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
