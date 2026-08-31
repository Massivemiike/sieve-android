package com.sieve.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sieve.app.ui.common.ChipKind
import com.sieve.app.ui.common.EmptyState
import com.sieve.app.ui.common.SectionLabel
import com.sieve.app.ui.common.SieveChip
import com.sieve.app.ui.common.SieveProgress
import com.sieve.app.ui.theme.SieveTheme
import org.junit.Rule
import org.junit.Test

class CommonUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun kitComposesAndShowsText() {
        rule.setContent {
            SieveTheme {
                Column {
                    SieveChip("Downloading", ChipKind.ACCENT, leadingDot = true)
                    SectionLabel("Format", 8)
                    SieveProgress(0.63f)
                    EmptyState(Icons.Filled.Download, "Nothing here", "add something")
                }
            }
        }
        rule.onNodeWithText("Downloading").assertIsDisplayed()
        rule.onNodeWithText("FORMAT").assertIsDisplayed()
        rule.onNodeWithText("Nothing here").assertIsDisplayed()
    }
}
