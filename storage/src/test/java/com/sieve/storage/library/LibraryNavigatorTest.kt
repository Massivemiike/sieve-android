package com.sieve.storage.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryNavigatorTest {
    @Test fun `starts at root, cannot go up`() {
        val nav = LibraryNavigator(rootDocumentId = "root")
        assertEquals("root", nav.current)
        assertFalse(nav.canGoUp())
    }

    @Test fun `enter pushes and up pops back to root`() {
        val nav = LibraryNavigator("root")
        nav.enter("child1"); nav.enter("child2")
        assertEquals("child2", nav.current)
        assertTrue(nav.canGoUp())
        nav.up(); assertEquals("child1", nav.current)
        nav.up(); assertEquals("root", nav.current)
        assertFalse(nav.canGoUp())
    }

    @Test fun `up at root is a no-op (bounded by tree root)`() {
        val nav = LibraryNavigator("root")
        nav.up(); assertEquals("root", nav.current)
    }
}
