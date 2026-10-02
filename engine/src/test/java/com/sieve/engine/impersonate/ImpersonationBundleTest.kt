package com.sieve.engine.impersonate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The install marker is the VERSION asset. It must change whenever the zip's bytes change, even if every
 * pinned component version stayed the same, or a rebuilt bundle ("link flags", a shim patch...) is never
 * reinstalled over an existing install. build.sh therefore appends `sha256-<first 12 hex of the zip>`;
 * this fails if the zip is rebuilt or swapped without refreshing VERSION. Unit tests run in the module directory.
 */
class ImpersonationBundleTest {
    private val dir = File("src/main/assets/impersonate")

    @Test fun versionCarriesTheHashOfTheZipItShipsWith() {
        val version = File(dir, "VERSION").readText().trim()
        val zipSha = MessageDigest.getInstance("SHA-256").digest(File(dir, "sieve-impersonate.zip").readBytes())
            .joinToString("") { "%02x".format(it) }

        val stamped = Regex("""(?:^| )sha256-([0-9a-f]{12})$""").find(version)?.groupValues?.get(1)
        assertNotNull("VERSION must end with sha256-<12 hex of sieve-impersonate.zip> (build.sh writes it): $version", stamped)
        assertTrue("VERSION says sha256-$stamped but the zip is $zipSha: rebuild with build.sh or refresh VERSION", zipSha.startsWith(stamped!!))
    }

    @Test fun versionIsASingleLineTheInstallerCanCompare() {
        val raw = File(dir, "VERSION").readText()
        assertEquals(1, raw.trim().lines().size)
    }
}
