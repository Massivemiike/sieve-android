package com.sieve.storage.naming

import kotlin.test.Test
import kotlin.test.assertEquals

class CollisionResolverTest {

    @Test fun `no collision returns name unchanged`() {
        assertEquals("a.mp4", CollisionResolver.resolve("a.mp4", emptySet()))
        assertEquals("a.mp4", CollisionResolver.resolve("a.mp4", setOf("b.mp4")))
    }

    @Test fun `single collision appends (1) before extension`() {
        assertEquals("a (1).mp4", CollisionResolver.resolve("a.mp4", setOf("a.mp4")))
    }

    @Test fun `picks lowest free index`() {
        assertEquals("a (2).mp4", CollisionResolver.resolve("a.mp4", setOf("a.mp4", "a (1).mp4")))
    }

    @Test fun `name without extension still gets suffix`() {
        assertEquals("folder (1)", CollisionResolver.resolve("folder", setOf("folder")))
    }

    @Test fun `dotfile with no stem treated as extensionless`() {
        assertEquals(".env (1)", CollisionResolver.resolve(".env", setOf(".env")))
    }

    @Test fun `group keeps a shared suffix across sidecars`() {
        val members = listOf("foo.mp4", "foo.en.srt", "foo.jpg")
        val existing = setOf("foo.mp4")
        assertEquals(
            listOf("foo (1).mp4", "foo (1).en.srt", "foo (1).jpg"),
            CollisionResolver.resolveGroup(members, existing),
        )
    }

    @Test fun `group with no collisions is unchanged`() {
        val members = listOf("foo.mp4", "foo.en.srt")
        assertEquals(members, CollisionResolver.resolveGroup(members, setOf("bar.mp4")))
    }

    @Test fun `group skips an index blocked by any member`() {
        val members = listOf("foo.mp4", "foo.en.srt")
        val existing = setOf("foo.mp4", "foo (1).en.srt")
        assertEquals(
            listOf("foo (2).mp4", "foo (2).en.srt"),
            CollisionResolver.resolveGroup(members, existing),
        )
    }
}
