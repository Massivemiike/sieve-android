package com.sieve.app.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sieve.app.ui.theme.SieveTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class UpdateSettingsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun availableStateShowsInstallAndFires() {
        var installed: UpdateManifest? = null
        val manifest = UpdateManifest(9, "1.9", "https://x/apk", "sha", changelog = "New stuff")
        rule.setContent {
            SieveTheme {
                UpdatesSectionContent(
                    state = UpdateUiState(status = UpdateStatus.Available(manifest)),
                    canInstall = true,
                    onCheck = {},
                    onInstall = { installed = it },
                    onGrantInstall = {},
                )
            }
        }
        rule.onNodeWithText("v1.9 available").assertIsDisplayed()
        rule.onNodeWithTag("update_install").performClick()
        assertEquals(9, installed?.versionCode)
    }

    @Test
    fun upToDateStateShowsCheckButton() {
        var checked = 0
        rule.setContent {
            SieveTheme {
                UpdatesSectionContent(
                    state = UpdateUiState(status = UpdateStatus.UpToDate),
                    canInstall = true,
                    onCheck = { checked++ },
                    onInstall = {},
                    onGrantInstall = {},
                )
            }
        }
        rule.onNodeWithTag("update_check").performClick()
        assertEquals(1, checked)
    }
}
