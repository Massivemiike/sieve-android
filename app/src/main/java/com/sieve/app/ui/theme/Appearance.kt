package com.sieve.app.ui.theme

import androidx.compose.ui.graphics.Color

enum class ThemeMode { DARK, LIGHT, AUTO }

/** Hoisted appearance state, seeded from AppSettings (Task 4). */
data class Appearance(
    val mode: ThemeMode = ThemeMode.DARK,
    val accent: Color = SieveAmber,
)

/** The token accent swatches offered in Settings (Appearance). */
val AccentSwatches: List<Pair<String, Color>> = listOf(
    "Amber" to SieveAmber,
    "Teal" to SieveGood,
    "Blue" to Color(0xFF6F9DE0),
    "Rose" to SieveBad,
)

/** Parse a stored "#RRGGBB" accent, falling back to amber. */
fun accentFromHex(hex: String?): Color = try {
    if (hex.isNullOrBlank()) SieveAmber
    else Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    SieveAmber
}
