package com.sieve.app

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sieve.app.ui.theme.Appearance
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.app.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test

class ThemeSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun themedContentComposesWithBundledFonts() {
        rule.setContent {
            SieveTheme(Appearance(mode = ThemeMode.DARK)) {
                Surface { Text("Sieve themed") }
            }
        }
        rule.onNodeWithText("Sieve themed").assertIsDisplayed()
    }
}
