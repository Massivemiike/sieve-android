package com.sieve.app.update

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun sha256OfKnownBytes() {
        val f = tmp.newFile("x").apply { writeText("hello") }
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", ApkVerifier.sha256(f))
    }

    @Test fun matchesSha256IsCaseInsensitiveAndTrims() {
        val f = tmp.newFile("y").apply { writeText("hello") }
        assertTrue(ApkVerifier.matchesSha256(f, "  2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824 "))
        assertFalse(ApkVerifier.matchesSha256(f, "deadbeef"))
    }
}
