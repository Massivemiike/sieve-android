package com.sieve.app

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every module whose APK (the app, or a library's instrumentation APK) loads a native binary BY FILE PATH from nativeLibraryDir has to
 * package its `.so` files the legacy way, extracted to disk on install:
 *
 *  * `:app` and `:transcode` (and `:storage`, whose smoke execs it): `libsieveffmpeg.so` is exec'd as a child process;
 *  * `:engine`: youtubedl-android opens `lib/<abi>/libpython.zip.so` and the ffmpeg zip by path. With AGP 9 and minSdk 26 the default is
 *    `extractNativeLibs=false` (stored, mmap'd out of the APK), so `:engine`'s instrumentation APK has no such file on the device and
 *    `EngineInstrumentedTest.engineInitDoesNotThrowAndClientConstructs` fails with ENOENT.
 *
 * `packaging { jniLibs { useLegacyPackaging = true } }` is what makes AGP write `extractNativeLibs="true"` into the merged manifest (the app's source
 * manifest must not carry the attribute itself: docs/SDK-37.md section 2). A static read of the build scripts (unit tests run with the module
 * directory as the working directory); the scripts are declared as inputs of the unit-test task in app/build.gradle.kts.
 */
class NativeLibPackagingTest {

    private val moduleDir: File = File(".").absoluteFile.normalize().let { if (File(it, "build.gradle.kts").isFile) it else File(it, "app") }
    private val root: File = checkNotNull(moduleDir.parentFile) { "no repository root above $moduleDir" }

    private fun script(module: String): String {
        val f = File(root, "$module/build.gradle.kts")
        assertTrue(f.isFile, "missing ${f.path}")
        return BuildScriptText.stripComments(f.readText())
    }

    /** `jniLibs { useLegacyPackaging = true }` inside a `packaging { ... }` block, whatever the whitespace. */
    internal fun extractsNativeLibs(code: String): Boolean =
        Regex("""\bpackaging\s*\{[^{}]*\bjniLibs\s*\{[^{}]*\buseLegacyPackaging\s*=\s*true\b""").containsMatchIn(code)

    @Test fun everyModuleThatLoadsNativeBinariesByPathExtractsThem() {
        for (m in listOf("app", "engine", "storage", "transcode")) {
            assertTrue(
                extractsNativeLibs(script(m)),
                "$m/build.gradle.kts must set packaging { jniLibs { useLegacyPackaging = true } }: its APK (the app, or the library's instrumentation APK) " +
                    "needs the native libs extracted to nativeLibraryDir (libsieveffmpeg.so is exec'd, youtubedl-android opens libpython.zip.so by path)",
            )
        }
    }

    @Test fun theAppSourceManifestDoesNotCarryExtractNativeLibsItself() {
        // AGP 9 warns about it on every manifest task and writes the merged value from the packaging block (docs/SDK-37.md section 2).
        // Only the app manifest is a declared input of the unit-test task (app/build.gradle.kts), so only it is read here.
        val manifest = File(moduleDir, "src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile, "missing ${manifest.path}")
        assertFalse("extractNativeLibs" in manifest.readText(), "the app's source manifest must not set extractNativeLibs; use packaging.jniLibs.useLegacyPackaging")
    }

    @Test fun theRecognizerAcceptsTheShapesTheScriptsUseAndRejectsTheDefault() {
        assertTrue(extractsNativeLibs("android { packaging { jniLibs { useLegacyPackaging = true } } }"))
        assertTrue(extractsNativeLibs("android {\n    packaging {\n        // note\n        jniLibs { useLegacyPackaging = true }\n        resources { excludes += \"/META-INF/{AL2.0,LGPL2.1}\" }\n    }\n}"))
        assertFalse(extractsNativeLibs("android { defaultConfig { minSdk = 26 } }"), "no packaging block = AGP 9 default = not extracted")
        assertFalse(extractsNativeLibs("android { packaging { jniLibs { useLegacyPackaging = false } } }"))
        assertFalse(extractsNativeLibs("android { packaging { resources { excludes += \"x\" } } ; jniLibs { useLegacyPackaging = true } }"), "outside packaging {} is not the same setting")
        assertEquals(false, extractsNativeLibs(""))
    }
}
