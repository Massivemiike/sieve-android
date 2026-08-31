package com.sieve.app.library

import com.sieve.app.ui.library.LibraryViewModel
import com.sieve.storage.library.DocumentStore
import com.sieve.storage.library.LibraryEntry
import com.sieve.storage.library.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun entry(id: String, name: String, dir: Boolean, ext: String, size: Long = 100) =
        LibraryEntry(id, "content://x/$id", name, size, 0, dir, ext)

    private val root = listOf(
        entry("music-doc", "Music", dir = true, ext = ""),
        entry("v-doc", "video.mp4", dir = false, ext = "mp4"),
        entry("s-doc", "song.mp3", dir = false, ext = "mp3"),
    )
    private val child = mapOf("music-doc" to listOf(entry("o-doc", "track.opus", false, "opus")))

    private class FakeStore(val root: List<LibraryEntry>, val child: Map<String, List<LibraryEntry>>) : DocumentStore {
        val deleted = mutableListOf<String>()
        override suspend fun listChildren(treeUri: String, parentDocumentId: String?) =
            if (parentDocumentId == null) root else child[parentDocumentId] ?: emptyList()
        override suspend fun rename(uri: String, newName: String): LibraryEntry? = null
        override suspend fun delete(uri: String): Boolean { deleted += uri; return true }
        override suspend fun readText(uri: String, maxBytes: Int) = "" to false
        override suspend fun openReadFd(uri: String) = 0
        override suspend fun openWriteFd(uri: String) = 0
        override suspend fun createChild(parentUri: String, mime: String, name: String): LibraryEntry? = null
    }

    @Test fun browsesFiltersNavigatesDeletes() = runTest {
        val store = FakeStore(root, child)
        val tree = MutableStateFlow<String?>(null)
        val persisted = mutableListOf<String>()
        val vm = LibraryViewModel(store, tree, MutableStateFlow("Sieve"), { persisted += it })
        advanceUntilIdle()

        // no tree -> grant state
        assertNull(vm.state.value.treeUri)
        assertTrue(vm.state.value.entries.isEmpty())

        // grant a tree -> lists root, dir floats to top
        tree.value = "content://tree"
        advanceUntilIdle()
        assertEquals("content://tree", vm.state.value.treeUri)
        assertEquals(3, vm.state.value.entries.size)
        assertTrue(vm.state.value.entries.first().isDir)

        // filter to audio -> just the mp3 (dirs hidden outside ALL)
        vm.setFilter(MediaKind.AUDIO)
        assertEquals(listOf("song.mp3"), vm.state.value.entries.map { it.name })

        // enter the dir -> its children, can go up
        vm.setFilter(MediaKind.ALL)
        vm.enter(vm.state.value.entries.first { it.isDir })
        advanceUntilIdle()
        assertEquals(listOf("track.opus"), vm.state.value.entries.map { it.name })
        assertTrue(vm.state.value.canGoUp)

        // up -> back to root
        vm.up()
        advanceUntilIdle()
        assertEquals(3, vm.state.value.entries.size)

        // delete a file
        val file = vm.state.value.entries.first { it.name == "video.mp4" }
        vm.delete(file)
        advanceUntilIdle()
        assertTrue(store.deleted.contains(file.uri))
        assertTrue(vm.state.value.entries.none { it.name == "video.mp4" })

        // grant new tree persists
        vm.onGrantTree("content://new")
        advanceUntilIdle()
        assertEquals("content://new", persisted.last())
    }
}
