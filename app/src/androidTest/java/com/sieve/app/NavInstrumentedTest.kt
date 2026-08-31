package com.sieve.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavSwitchesDestinations() {
        rule.onNodeWithText("Sieve").assertIsDisplayed() // Download screen wordmark

        rule.onNodeWithTag("nav_queue").performClick()
        rule.onNodeWithText("queue-screen").assertIsDisplayed()

        rule.onNodeWithTag("nav_transcode").performClick()
        rule.onNodeWithText("transcode-screen").assertIsDisplayed()

        rule.onNodeWithTag("nav_library").performClick()
        rule.onNodeWithText("library-screen").assertIsDisplayed()

        rule.onNodeWithTag("nav_settings").performClick()
        rule.onNodeWithText("settings-screen").assertIsDisplayed()

        rule.onNodeWithTag("nav_download").performClick()
        rule.onNodeWithText("Sieve").assertIsDisplayed()
    }
}
