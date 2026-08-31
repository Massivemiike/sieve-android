package com.sieve.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import android.Manifest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavInstrumentedTest {

    @get:Rule(order = 0)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavSwitchesDestinations() {
        rule.onNodeWithText("Sieve").assertIsDisplayed() // Download screen wordmark

        rule.onNodeWithTag("nav_queue").performClick()
        rule.onNodeWithText("Queue is empty").assertIsDisplayed() // real Queue screen (empty)

        rule.onNodeWithTag("nav_transcode").performClick()
        rule.onNodeWithText("Pick a video to transcode").assertIsDisplayed() // real Transcode screen (no source)

        rule.onNodeWithTag("nav_library").performClick()
        rule.onNodeWithText("Choose a folder").assertIsDisplayed() // real Library screen (no tree granted)

        rule.onNodeWithTag("nav_settings").performClick()
        rule.onNodeWithText("STORAGE").assertIsDisplayed() // real Settings screen (first section label)

        rule.onNodeWithTag("nav_download").performClick()
        rule.onNodeWithText("Sieve").assertIsDisplayed()
    }
}
