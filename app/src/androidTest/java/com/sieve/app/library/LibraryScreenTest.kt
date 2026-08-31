package com.sieve.app.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sieve.app.ui.library.LibraryRoute
import com.sieve.app.ui.library.LibraryViewModel
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.storage.library.DocumentStore
import com.sieve.storage.library.LibraryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeStore(val entries: List<LibraryEntry>) : DocumentStore {
        override suspend fun listChildren(treeUri: String, parentDocumentId: String?) = entries
        override suspend fun rename(uri: String, newName: String): LibraryEntry? = null
        override suspend fun delete(uri: String) = true
        override suspend fun readText(uri: String, maxBytes: Int) = "" to false
        override suspend fun openReadFd(uri: String) = 0
        override suspend fun openWriteFd(uri: String) = 0
        override suspend fun createChild(parentUri: String, mime: String, name: String): LibraryEntry? = null
    }

    @Test
    fun listsFilesAndFilters() {
        val entries = listOf(
            LibraryEntry("v", "content://x/v", "spring.mp4", 1_200_000_000, 0, false, "mp4"),
            LibraryEntry("a", "content://x/a", "lofi.mp3", 5_000_000, 0, false, "mp3"),
        )
        val vm = LibraryViewModel(FakeStore(entries), MutableStateFlow("content://tree"), MutableStateFlow("Sieve"), {})

        rule.setContent { SieveTheme { LibraryRoute(vm = vm) } }
        rule.waitUntil(4_000) {
            rule.onAllNodesWithText("spring.mp4").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("spring.mp4").assertIsDisplayed()
        rule.onNodeWithText("lofi.mp3").assertIsDisplayed()

        // filter to Audio -> the mp4 disappears
        rule.onNodeWithTag("filter_Audio").performClick()
        rule.waitUntil(4_000) {
            rule.onAllNodesWithText("spring.mp4").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("lofi.mp3").assertIsDisplayed()
    }
}
