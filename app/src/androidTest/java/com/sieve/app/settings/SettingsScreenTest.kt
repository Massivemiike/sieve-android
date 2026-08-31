package com.sieve.app.settings

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.sieve.app.settings.AppPrefs
import com.sieve.app.ui.settings.SettingsScreen
import com.sieve.app.ui.settings.SettingsUiState
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.app.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun themeSegmentAndResetFireCallbacks() {
        var theme: ThemeMode? = null
        var reset = 0
        rule.setContent {
            SieveTheme {
                SettingsScreen(
                    state = SettingsUiState(app = AppPrefs(themeMode = ThemeMode.DARK), engineVersion = "2025.01.01"),
                    onGrant = {}, onTheme = { theme = it }, onAccent = {}, onDefaultPreset = {},
                    onMaxDownloads = {}, onMaxTranscodes = {}, onUpdateEngine = {}, onReset = { reset++ }, onOpenAbout = {},
                )
            }
        }

        rule.onNodeWithText("STORAGE").assertExists()
        rule.onNodeWithTag("seg_light").performClick()
        assertEquals(ThemeMode.LIGHT, theme)

        rule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("reset_btn"))
        rule.onNodeWithTag("reset_btn").performClick()
        assertEquals(1, reset)
    }
}
