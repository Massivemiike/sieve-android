package com.sieve.app.theme

import androidx.compose.ui.graphics.Color
import com.sieve.app.ui.theme.SieveAmber
import com.sieve.app.ui.theme.sieveDarkColors
import com.sieve.app.ui.theme.sieveLightColors
import kotlin.test.Test
import kotlin.test.assertEquals

class ColorSchemeTest {

    @Test
    fun darkSchemeUsesSieveCanvasAndAccent() {
        val s = sieveDarkColors(SieveAmber)
        assertEquals(Color(0xFF08090B), s.background)
        assertEquals(Color(0xFF0D0E11), s.surface)
        assertEquals(SieveAmber, s.primary)
    }

    @Test
    fun lightSchemeUsesSievePaperAndAccent() {
        val s = sieveLightColors(SieveAmber)
        assertEquals(Color(0xFFFAFAF9), s.background)
        assertEquals(SieveAmber, s.primary)
    }
}
