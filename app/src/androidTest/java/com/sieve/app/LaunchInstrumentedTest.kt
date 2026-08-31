package com.sieve.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToDownloadDestination() {
        // The Download screen shows the Sieve wordmark in its top bar.
        rule.onNodeWithText("Sieve").assertIsDisplayed()
    }
}
