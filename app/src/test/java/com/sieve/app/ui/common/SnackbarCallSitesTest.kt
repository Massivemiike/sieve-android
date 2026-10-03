package com.sieve.app.ui.common

import com.sieve.app.BuildScriptText
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every snackbar in :app goes through [AppSnackbars], so that the one rule it enforces (a snackbar that stays up until the
 * user acts never holds up another) cannot be bypassed by a call site that talks to a host directly. A static read of the
 * sources (unit tests run with the module directory as the working directory).
 */
class SnackbarCallSitesTest {

    private val moduleDir: File = File(".").absoluteFile.normalize().let { if (File(it, "build.gradle.kts").isFile) it else File(it, "app") }
    private val sources: List<File> = File(moduleDir, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun `the sources were found`() {
        assertTrue(sources.any { it.name == "AppSnackbars.kt" }, "no AppSnackbars.kt under ${moduleDir.path}/src/main/java")
    }

    @Test fun `no source but AppSnackbars calls showSnackbar`() {
        val offenders = sources.filter { it.name != "AppSnackbars.kt" }
            .filter { Regex("""\bshowSnackbar\s*\(""").containsMatchIn(BuildScriptText.stripComments(it.readText())) }
            .map { it.name }
        assertEquals(emptyList<String>(), offenders, "these call SnackbarHostState.showSnackbar directly; use AppSnackbars.show / showPreemptible")
    }
}
