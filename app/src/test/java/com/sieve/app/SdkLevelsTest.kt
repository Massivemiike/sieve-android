package com.sieve.app

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner directive: everything targets the latest SDK (docs/SDK-37.md). "Everything" is the app, the five library modules' instrumentation
 * APKs, the build-tools and what CI installs, so a module (or a CI step) left on an older level is a test failure instead of something
 * a device run finds later:
 *
 *  * every module compiles against the level the app targets, with build-tools of that level;
 *  * every library's `testOptions.targetSdk` (its instrumentation APK; Play Protect blocks one built for minSdk) is the app's targetSdk;
 *  * every workflow that installs the Android SDK (release.yml, and any CI workflow added later) installs the platform and the
 *    build-tools those modules need (AGP would download them on its own, but an explicit install is deterministic).
 *
 * The level itself is not pinned here: moving to the next Android release means changing the build scripts and the workflows together,
 * and this test checks that they moved together. Static reads (unit tests run with the module directory as the working directory);
 * the files it reads are declared as inputs of the unit-test task in app/build.gradle.kts.
 */
class SdkLevelsTest {

    private val moduleDir: File = File(".").absoluteFile.normalize().let { if (File(it, "build.gradle.kts").isFile) it else File(it, "app") }
    private val root: File = checkNotNull(moduleDir.parentFile) { "no repository root above $moduleDir" }
    private val modules = listOf("app", "engine", "data", "queue", "storage", "transcode")
    private val libraries = modules - "app"

    private fun script(module: String): String {
        val f = File(root, "$module/build.gradle.kts")
        assertTrue(f.isFile, "missing ${f.path}")
        return BuildScriptText.stripComments(f.readText())
    }

    private fun workflow(name: String): String {
        val f = File(root, ".github/workflows/$name")
        assertTrue(f.isFile, "missing ${f.path}")
        // a `#` that starts a line or follows whitespace is a comment
        return f.readText().lineSequence().joinToString("\n") { it.replace(Regex("""(^|\s)#.*$""")) { m -> m.groupValues[1] } }
    }

    /** `compileSdk = 37` or `compileSdk { version = release(37) { minorApiLevel = 2 } }`: the major level. */
    private fun compileSdk(code: String): Int {
        val levels = (Regex("""\bcompileSdk\s*=\s*(\d+)""").findAll(code) + Regex("""\bcompileSdk\s*\{[^}]*?\brelease\(\s*(\d+)\s*\)""").findAll(code))
            .map { it.groupValues[1].toInt() }.toList()
        assertEquals(1, levels.size, "expected exactly one literal compileSdk, found $levels")
        return levels.single()
    }

    private fun targetSdks(code: String): List<Int> = Regex("""\btargetSdk\s*=\s*(\d+)""").findAll(code).map { it.groupValues[1].toInt() }.toList()

    private fun buildTools(code: String): String? = Regex("""\bbuildToolsVersion\s*=\s*"([^"]+)"""").find(code)?.groupValues?.get(1)

    private val appTarget: Int
        get() = targetSdks(script("app")).also { assertEquals(1, it.size, "app/build.gradle.kts needs exactly one targetSdk, found $it") }.single()

    @Test fun theAppsTargetIsTheLevelEveryModuleCompilesAgainst() {
        for (m in modules) assertEquals(appTarget, compileSdk(script(m)), "$m compileSdk differs from the app's targetSdk $appTarget")
    }

    @Test fun everyLibraryInstrumentationApkTargetsTheAppsLevel() {
        for (m in libraries) {
            val t = targetSdks(script(m))
            assertEquals(listOf(appTarget), t, "$m must set testOptions.targetSdk = $appTarget exactly once (its instrumentation APK would otherwise target minSdk)")
        }
    }

    @Test fun everyModulePinsBuildToolsOfTheCompileLevel() {
        for (m in modules) {
            val v = buildTools(script(m))
            assertTrue(v != null, "$m must pin buildToolsVersion (AGP would otherwise download its own default on first use)")
            assertEquals(appTarget, v.substringBefore('.').toInt(), "$m buildToolsVersion $v is not of level $appTarget")
        }
        val all = modules.map { buildTools(script(it)) }.toSet()
        assertEquals(1, all.size, "one build-tools version for all modules, found $all")
    }

    @Test fun everyWorkflowThatInstallsTheSdkInstallsThePlatformAndTheBuildToolsTheModulesNeed() {
        val tools = buildTools(script("app"))!!
        val dir = File(root, ".github/workflows")
        val installers = dir.listFiles { f -> f.extension == "yml" || f.extension == "yaml" }.orEmpty()
            .filter { "android-actions/setup-android" in workflow(it.name) }.map { it.name }.sorted()
        assertTrue("release.yml" in installers, "release.yml must install the Android SDK (android-actions/setup-android); found $installers")
        for (w in installers) {
            val yml = workflow(w)
            val platforms = Regex("""(?<!\S)platforms;android-(\d+)(?:\.\d+)?(?!\S)""").findAll(yml).map { it.groupValues[1].toInt() }.toList()
            assertEquals(listOf(appTarget), platforms, "$w must install platforms;android-$appTarget.<minor> (the id has a minor part from 37 on, e.g. android-37.0)")
            assertTrue(Regex("""(?<!\S)build-tools;${Regex.escape(tools)}(?!\S)""").containsMatchIn(yml), "$w must install build-tools;$tools")
        }
    }
}
