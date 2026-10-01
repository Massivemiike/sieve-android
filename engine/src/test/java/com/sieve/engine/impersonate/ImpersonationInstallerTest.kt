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

    @Test fun upgradeDropsTheOldVersionsDistInfoSoMetadataCannotReportTheOldVersion() {
        val sp = pythonTree()
        ImpersonationInstaller.install(sp, "v1") { ByteArrayInputStream(bundle) }          // ships curl_cffi-0.16.3.dist-info
        File(sp, "mutagen-1.47.0.dist-info").mkdirs(); File(sp, "mutagen-1.47.0.dist-info/METADATA").writeText("keep")
        val v2 = zipOf(
            "curl_cffi/__init__.py" to "init2",
            "curl_cffi-0.17.0.dist-info/METADATA" to "Name: curl_cffi\nVersion: 0.17.0",
        )
        ImpersonationInstaller.install(sp, "v2") { ByteArrayInputStream(v2) }

        assertFalse(File(sp, "curl_cffi-0.16.3.dist-info").exists())
        assertEquals("Name: curl_cffi\nVersion: 0.17.0", File(sp, "curl_cffi-0.17.0.dist-info/METADATA").readText())
        assertEquals("keep", File(sp, "mutagen-1.47.0.dist-info/METADATA").readText())     // not ours: untouched
    }

    @Test fun staleMetadataFromAnInstallThatPredatesTheFixIsCleanedToo() {
        // site-packages as an older app version left it: no manifest of what was installed, just the dirs.
        val sp = pythonTree()
        for (d in listOf("curl_cffi-0.16.3.dist-info", "cffi-2.0.0.dist-info", "certifi-2026.7.22.dist-info", "numpy-1.0.dist-info")) {
            File(sp, "$d").mkdirs(); File(sp, "$d/METADATA").writeText("old")
        }
        File(sp, ImpersonationInstaller.MARKER).writeText("old-version\n")
        val v2 = zipOf(
            "curl_cffi-0.17.0.dist-info/METADATA" to "new", "cffi-2.1.0.dist-info/METADATA" to "new",
            "certifi-2026.8.1.dist-info/METADATA" to "new",
        )
        ImpersonationInstaller.install(sp, "v2") { ByteArrayInputStream(v2) }

        assertEquals(
            setOf("curl_cffi-0.17.0.dist-info", "cffi-2.1.0.dist-info", "certifi-2026.8.1.dist-info", "numpy-1.0.dist-info"),
            sp.list()!!.filter { it.endsWith(".dist-info") }.toSet(),
        )
    }

    @Test fun aDistributionIsNotMistakenForOneWithALongerName() {
        // Shipping cffi-*.dist-info must not remove curl_cffi-*.dist-info (or the other way round).
        val sp = pythonTree()
        File(sp, "curl_cffi-0.16.3.dist-info").mkdirs()
        File(sp, "cffi-2.0.0.dist-info").mkdirs()
        ImpersonationInstaller.install(sp, "v2") { ByteArrayInputStream(zipOf("cffi-2.1.0.dist-info/METADATA" to "new")) }

        assertTrue(File(sp, "curl_cffi-0.16.3.dist-info").isDirectory)
        assertFalse(File(sp, "cffi-2.0.0.dist-info").exists())
        assertTrue(File(sp, "cffi-2.1.0.dist-info").isDirectory)
    }

    @Test fun egg_infoOfAShippedDistributionIsReplacedToo() {
        val sp = pythonTree()
        File(sp, "oldpkg-1.0-py3.12.egg-info").mkdirs()
        ImpersonationInstaller.install(sp, "v2") { ByteArrayInputStream(zipOf("oldpkg-2.0-py3.12.egg-info/PKG-INFO" to "new")) }

        assertFalse(File(sp, "oldpkg-1.0-py3.12.egg-info").exists())
        assertTrue(File(sp, "oldpkg-2.0-py3.12.egg-info/PKG-INFO").isFile)
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
