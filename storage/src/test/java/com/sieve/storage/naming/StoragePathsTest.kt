package com.sieve.storage.naming

import kotlin.test.Test
import kotlin.test.assertEquals

class StoragePathsTest {

    @Test fun `work dir is deterministic under filesDir work`() {
        assertEquals("/data/app/files/work/job-42", StoragePaths.workDir("/data/app/files", "job-42"))
    }

    @Test fun `work dir tolerates trailing separator on filesDir`() {
        assertEquals("/data/app/files/work/job-42", StoragePaths.workDir("/data/app/files/", "job-42"))
    }

    @Test fun `relative path with no label is Download slash Sieve`() {
        assertEquals("Download/Sieve", StoragePaths.outputRelativePath(null))
        assertEquals("Download/Sieve", StoragePaths.outputRelativePath(""))
    }

    @Test fun `relative path appends sanitized label`() {
        assertEquals("Download/Sieve/Music", StoragePaths.outputRelativePath("Music"))
        assertEquals("Download/Sieve/AC_DC", StoragePaths.outputRelativePath("AC/DC"))
    }

    @Test fun `label separators are flattened to a single segment`() {
        assertEquals("Download/Sieve/a_b", StoragePaths.outputRelativePath("a/b"))
        assertEquals("Download/Sieve/.._x", StoragePaths.sanitizeLabelSegment("../x").let { "Download/Sieve/$it" })
    }
}
