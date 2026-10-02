package com.sieve.engine.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The version of the yt-dlp that will run is read from the zipapp itself: the library keeps no version file beside it. */
class YtDlpZipappTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(bytes: ByteArray) = File(tmp.root, "yt-dlp").apply { writeBytes(bytes) }
    private fun read(bytes: ByteArray) = YtDlpZipapp.version(file(bytes))

    @Test fun readsTheVersionOfARealYtDlpLayout() {
        // shebang line + a zip whose offsets are relative to the zip's own start: what youtubedl-android ships and downloads
        assertEquals("2025.11.12", read(ZipappFixture.build("2025.11.12")))
    }

    @Test fun readsAPlainZipWithoutAShebang() {
        assertEquals("2026.08.19", read(ZipappFixture.build("2026.08.19", shebang = false)))
    }

    @Test fun readsAZipappWhoseOffsetsAreAbsolute() {
        // what `python -m zipapp` writes: the same prefix, offsets counted from the start of the file
        assertEquals("2026.08.19", read(ZipappFixture.build("2026.08.19", absoluteOffsets = true)))
    }

    @Test fun readsAnUncompressedEntry() {
        assertEquals("2026.08.19", read(ZipappFixture.build("2026.08.19", stored = true)))
    }

    @Test fun readsAnArchiveWithAZipComment() {
        assertEquals("2026.08.19", read(ZipappFixture.build("2026.08.19", comment = "built by make")))
    }

    @Test fun nightlyAndMasterBuildsKeepTheirRevision() {
        assertEquals("2026.08.19.232830", read(ZipappFixture.build("2026.08.19.232830")))
    }

    @Test fun readsDunderVersionNotThePackageVersionBesideIt() {
        val py = "_pkg_version = '1.1.1'\n__version__ = '2.2.2'\n"
        assertEquals("2.2.2", read(ZipappFixture.build("x", versionPy = py)))
    }

    @Test fun aMissingFileIsNull() {
        assertNull(YtDlpZipapp.version(File(tmp.root, "absent")))
    }

    @Test fun anEmptyFileIsNull() {
        assertNull(read(ByteArray(0)))
    }

    @Test fun somethingThatIsNotAZipIsNull() {
        assertNull(read("#!/usr/bin/env python3\nprint('hello')\n".repeat(500).toByteArray()))
    }

    @Test fun aTruncatedDownloadIsNull() {
        val whole = ZipappFixture.build("2026.08.19")
        assertNull(read(whole.copyOf(whole.size / 2)))
        assertNull(read(whole.copyOf(whole.size - 10))) // loses the end-of-central-directory record
    }

    @Test fun aZipWithoutVersionPyIsNull() {
        val bytes = ZipappFixture.build("2026.08.19").also { b ->
            // the central directory's copy of the name (the last one in the file) becomes "zt_dlp/version.py": no such entry any more
            val at = String(b, Charsets.ISO_8859_1).lastIndexOf("yt_dlp/version.py")
            b[at] = 'z'.code.toByte()
        }
        assertNull(read(bytes))
    }

    @Test fun aVersionPyWithoutADunderVersionIsNull() {
        assertNull(read(ZipappFixture.build("x", versionPy = "RELEASE_GIT_HEAD = 'abc'\n")))
    }

    @Test fun aDamagedCentralDirectoryIsNullNotACrash() {
        val bytes = ZipappFixture.build("2026.08.19")
        val firstEntry = String(bytes, Charsets.ISO_8859_1).indexOf("PK\u0001\u0002") // the central directory starts here
        for (i in 4 until 44) bytes[firstEntry + i] = 0x7F // sizes and name length far past the end
        assertNull(read(bytes))
    }

    @Test fun aDamagedLocalHeaderIsNullNotACrash() {
        val bytes = ZipappFixture.build("2026.08.19")
        val localHeader = String(bytes, Charsets.ISO_8859_1).indexOf("yt_dlp/version.py") - 30 // the first copy of the name is the local header's
        bytes[localHeader] = 0
        assertNull(read(bytes))
    }
}
