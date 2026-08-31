package com.sieve.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The Sieve theme. Resolves [ThemeMode.AUTO] via the system setting, picks the dark/light
 * Sieve color scheme seeded with [Appearance.accent], and applies the Sieve type scale + shapes.
 */
@Composable
fun SieveTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    val dark = when (appearance.mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val colors = if (dark) sieveDarkColors(appearance.accent) else sieveLightColors(appearance.accent)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                colors.background.luminance() > 0.5f
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = SieveTypography,
        shapes = SieveShapes,
        content = content,
    )
}
