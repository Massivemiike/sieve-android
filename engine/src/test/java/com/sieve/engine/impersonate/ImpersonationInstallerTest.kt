package com.sieve.engine.impersonate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImpersonationInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** A youtubedl-android-shaped python3.12 tree; returns its site-packages (not yet created). */
    private fun pythonTree(): File {
        val lib = File(tmp.root, "youtubedl-android/packages/python/usr/lib/python3.12").apply { mkdirs() }
        return File(lib, "site-packages")
    }

    private val bundle = zipOf(
        "curl_cffi/__init__.py" to "init",
        "curl_cffi/_wrapper.abi3.so" to "elf",
        "_cffi_backend.cpython-312.so" to "elf2",
        "curl_cffi-0.16.3.dist-info/METADATA" to "Name: curl_cffi",
    )

    @Test fun sitePackagesFollowsTheLibraryLayout() {
        assertEquals(pythonTree(), ImpersonationInstaller.sitePackages(tmp.root))
    }

    @Test fun installsFilesAndWritesTheMarker() {
        val sp = pythonTree()
        val outcome = ImpersonationInstaller.install(sp, "v1\n") { ByteArrayInputStream(bundle) }
        assertEquals(ImpersonationInstaller.Outcome.Installed, outcome)
        assertEquals("elf", File(sp, "curl_cffi/_wrapper.abi3.so").readText())
        assertEquals("elf2", File(sp, "_cffi_backend.cpython-312.so").readText())
        assertEquals("v1\n", File(sp, ImpersonationInstaller.MARKER).readText())
    }

    @Test fun sameVersionIsNotReinstalled() {
        val sp = pythonTree()
        ImpersonationInstaller.install(sp, "v1") { ByteArrayInputStream(bundle) }
        var opened = 0
        val outcome = ImpersonationInstaller.install(sp, "v1") { opened++; ByteArrayInputStream(bundle) }
        assertEquals(ImpersonationInstaller.Outcome.AlreadyCurrent, outcome)
        assertEquals(0, opened)
    }

    @Test fun newVersionReplacesOwnedDirsAndLeavesOtherPackagesAlone() {
        val sp = pythonTree()
        ImpersonationInstaller.install(sp, "v1") { ByteArrayInputStream(bundle) }
        File(sp, "curl_cffi/stale.py").writeText("old")
        File(sp, "mutagen").mkdirs(); File(sp, "mutagen/__init__.py").writeText("keep")
        val v2 = zipOf("curl_cffi/__init__.py" to "init2")
        val outcome = ImpersonationInstaller.install(sp, "v2") { ByteArrayInputStream(v2) }
        assertEquals(ImpersonationInstaller.Outcome.Installed, outcome)
        assertFalse(File(sp, "curl_cffi/stale.py").exists())
        assertEquals("init2", File(sp, "curl_cffi/__init__.py").readText())
        assertEquals("keep", File(sp, "mutagen/__init__.py").readText())
        assertEquals("v2\n", File(sp, ImpersonationInstaller.MARKER).readText())
    }

    @Test fun skipsWhenThePythonIsNot312() {
        val lib = File(tmp.root, "youtubedl-android/packages/python/usr/lib/python3.13").apply { mkdirs() }
        val outcome = ImpersonationInstaller.install(ImpersonationInstaller.sitePackages(tmp.root), "v1") {
            ByteArrayInputStream(bundle)
        }
        assertTrue(outcome is ImpersonationInstaller.Outcome.Skipped)
        assertFalse(File(lib.parentFile, "python3.12").exists())
    }

    @Test fun interruptedInstallIsRedone() {
        val sp = pythonTree()
        var opens = 0
        val result = runCatching {
            ImpersonationInstaller.install(sp, "v1") {
                if (++opens == 2) throw IOException("asset read failed mid-install")
                ByteArrayInputStream(bundle)
            }
        }
        assertTrue(result.isFailure)
        assertFalse(File(sp, ImpersonationInstaller.MARKER).exists())
        val outcome = ImpersonationInstaller.install(sp, "v1") { ByteArrayInputStream(bundle) }
        assertEquals(ImpersonationInstaller.Outcome.Installed, outcome)
    }

    @Test(expected = IOException::class)
    fun rejectsEntriesThatEscapeSitePackages() {
        val evil = zipOf("../../evil.so" to "x")
        ImpersonationInstaller.install(pythonTree(), "v1") { ByteArrayInputStream(evil) }
    }
}
