package com.sieve.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Sieve brand accents (from yt-dlp-gui/src/styles/tokens.css)
val SieveAmber = Color(0xFFE0A458)
val SieveGood = Color(0xFF4EC9A8)
val SieveBad = Color(0xFFE06B7D)
val SieveInk = Color(0xFF20160A) // on-accent text

// ── Dark (default) — tokens.css :root ──────────────────────────────────
private val DarkBg0 = Color(0xFF08090B)
private val DarkBg1 = Color(0xFF0D0E11)
private val DarkBg2 = Color(0xFF14161A)
private val DarkBg3 = Color(0xFF1B1E23)
private val DarkLine = Color(0xFF23262C)
private val DarkLineSoft = Color(0xFF181A1F)
private val DarkFg0 = Color(0xFFF3F4F7)
private val DarkFg2 = Color(0xFF9AA0AA)

fun sieveDarkColors(accent: Color = SieveAmber): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = SieveInk,
    primaryContainer = accent.copy(alpha = 0.14f),
    onPrimaryContainer = accent,
    secondary = accent,
    onSecondary = SieveInk,
    tertiary = SieveGood,
    onTertiary = SieveInk,
    background = DarkBg0,
    onBackground = DarkFg0,
    surface = DarkBg1,
    onSurface = DarkFg0,
    surfaceVariant = DarkBg2,
    onSurfaceVariant = DarkFg2,
    surfaceContainerLowest = DarkBg0,
    surfaceContainerLow = DarkBg1,
    surfaceContainer = DarkBg2,
    surfaceContainerHigh = DarkBg3,
    surfaceContainerHighest = Color(0xFF24272D),
    outline = DarkLine,
    outlineVariant = DarkLineSoft,
    error = SieveBad,
    onError = SieveInk,
)

// ── Light — tokens.css [data-theme="light"] ────────────────────────────
private val LightBg0 = Color(0xFFFAFAF9)
private val LightBg1 = Color(0xFFFFFFFF)
private val LightBg2 = Color(0xFFF4F4F3)
private val LightBg3 = Color(0xFFEBEBE9)
private val LightLine = Color(0xFFE3E3E0)
private val LightFg0 = Color(0xFF15171A)
private val LightFg2 = Color(0xFF5A5F68)

fun sieveLightColors(accent: Color = SieveAmber): ColorScheme = lightColorScheme(
    primary = accent,
    onPrimary = SieveInk,
    primaryContainer = accent.copy(alpha = 0.16f),
    onPrimaryContainer = Color(0xFFB9750F),
    secondary = accent,
    onSecondary = SieveInk,
    tertiary = Color(0xFF2F8A6B),
    onTertiary = Color(0xFFFFFFFF),
    background = LightBg0,
    onBackground = LightFg0,
    surface = LightBg1,
    onSurface = LightFg0,
    surfaceVariant = LightBg2,
    onSurfaceVariant = LightFg2,
    surfaceContainerLowest = LightBg1,
    surfaceContainerLow = LightBg0,
    surfaceContainer = LightBg2,
    surfaceContainerHigh = LightBg3,
    surfaceContainerHighest = Color(0xFFDFDFDC),
    outline = LightLine,
    outlineVariant = Color(0xFFEFEFEC),
    error = Color(0xFFC14B5D),
    onError = Color(0xFFFFFFFF),
)
