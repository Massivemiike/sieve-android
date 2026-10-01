package com.sieve.app.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.sieve.app.settings.AppPrefs
import com.sieve.app.settings.CookiesInfo
import com.sieve.app.ui.settings.NetworkActions
import com.sieve.app.ui.settings.SettingsScreen
import com.sieve.app.ui.settings.SettingsUiState
import com.sieve.app.ui.settings.SettingsViewModel
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

    @Test
    fun theUpdateResultShowsUnderTheEngineRowAndDismissesOnTap() {
        var dismissed = 0
        rule.setContent {
            SieveTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        engineVersion = "2025.01.01",
                        updateMessage = SettingsViewModel.UPDATE_BLOCKED_MESSAGE, updateMessageIsError = true,
                    ),
                    onGrant = {}, onTheme = {}, onAccent = {}, onDefaultPreset = {},
                    onMaxDownloads = {}, onMaxTranscodes = {}, onUpdateEngine = {}, onReset = {}, onOpenAbout = {},
                    onDismissUpdateMessage = { dismissed++ },
                )
            }
        }

        scrollTo("update_message")
        rule.onNodeWithText("Wait for downloads to finish before updating yt-dlp.").assertExists()
        rule.onNodeWithTag("update_message").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun noUpdateMessageMeansNoUpdateLine() {
        setScreen(SettingsUiState(engineVersion = "2025.01.01"), NetworkActions())
        scrollTo("update_engine")
        rule.onNodeWithTag("update_message").assertDoesNotExist()
    }

    private fun setScreen(state: SettingsUiState, network: NetworkActions) = rule.setContent {
        SieveTheme {
            SettingsScreen(
                state = state, onGrant = {}, onTheme = {}, onAccent = {}, onDefaultPreset = {},
                onMaxDownloads = {}, onMaxTranscodes = {}, onUpdateEngine = {}, onReset = {}, onOpenAbout = {},
                network = network,
            )
        }
    }

    private fun scrollTo(tag: String) = rule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag(tag))

    @Test
    fun proxyEditorOnlyOffersSaveForAValidProxy() {
        var saved: String? = "untouched"
        setScreen(SettingsUiState(), NetworkActions(onProxy = { saved = it }))

        scrollTo("proxy_row")
        rule.onNodeWithTag("proxy_row").performClick()
        rule.onNodeWithTag("edit_field").performTextInput("not a proxy")
        rule.onNodeWithTag("edit_save").assertIsNotEnabled()

        rule.onNodeWithTag("edit_field").performTextClearance()
        rule.onNodeWithTag("edit_field").performTextInput("socks5://127.0.0.1:1080")
        rule.onNodeWithTag("edit_save").assertIsEnabled().performClick()
        assertEquals("socks5://127.0.0.1:1080", saved)
    }

    @Test
    fun speedLimitEditorClearsTheSetting() {
        var saved: String? = "untouched"
        setScreen(SettingsUiState(app = AppPrefs(speedLimit = "2M")), NetworkActions(onSpeedLimit = { saved = it }))

        scrollTo("speed_row")
        rule.onNodeWithText("2M/s").assertExists()
        rule.onNodeWithTag("speed_row").performClick()
        rule.onNodeWithTag("edit_clear").performClick()
        assertEquals(null, saved)
    }

    @Test
    fun cookiesRowShowsTheCountAndWarnsWhenStale() {
        var removed = 0
        val fortyFiveDaysAgo = System.currentTimeMillis() - 45L * 24 * 60 * 60 * 1000
        setScreen(
            SettingsUiState(app = AppPrefs(cookiesFileUri = "/data/cookies.txt"), cookies = CookiesInfo(12, fortyFiveDaysAgo)),
            NetworkActions(onRemoveCookies = { removed++ }),
        )

        scrollTo("cookies_status")
        rule.onNodeWithText("cookies.txt loaded (12 cookies)").assertExists()
        rule.onNodeWithTag("cookies_warning").assertExists()
        rule.onNodeWithTag("cookies_remove").performClick()
        assertEquals(1, removed)
    }

    @Test
    fun cookiesImportButtonFiresThePickerAndAFailureMessageShows() {
        var picks = 0
        setScreen(
            SettingsUiState(cookiesMessage = "Couldn't read that file."),
            NetworkActions(onPickCookies = { picks++ }),
        )

        scrollTo("cookies_import")
        rule.onNodeWithTag("cookies_import").performClick()
        assertEquals(1, picks)
        rule.onNodeWithText("Couldn't read that file.").assertExists()
        rule.onNodeWithTag("cookies_remove").assertDoesNotExist()
    }
}
