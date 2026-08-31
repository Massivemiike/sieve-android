package com.sieve.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

/** The five bottom-nav destinations (v1). About is a pushed detail, not a bar entry. */
enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    DOWNLOAD("download", "Download", Icons.Filled.Download),
    QUEUE("queue", "Queue", Icons.AutoMirrored.Filled.List),
    TRANSCODE("transcode", "Transcode", Icons.Filled.SwapHoriz),
    LIBRARY("library", "Library", Icons.Filled.Folder),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

const val ROUTE_ABOUT = "about"
