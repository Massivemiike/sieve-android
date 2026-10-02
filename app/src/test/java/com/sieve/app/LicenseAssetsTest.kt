package com.sieve.app

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GPL written offer and the notices the app ships must cover everything the APK carries, and must not
 * drift from what is actually built. These check the text assets against the build files they describe.
 * Unit tests run with the module directory (app/) as the working directory.
 */
class LicenseAssetsTest {
    private val assets = File("src/main/assets/licenses")
    private val mirrors = File("../licenses")
    private val issuesUrl = "https://github.com/Massivemiike/sieve-android/issues"

    private fun text(f: File): String {
        assertTrue(f.isFile, "missing ${f.path}")
        return f.readText().replace("\r\n", "\n")
    }
    private fun asset(name: String) = text(File(assets, name))

    @Test fun everyLicenseTheAboutScreenOffersIsShipped() {
        val about = File("src/main/java/com/sieve/app/ui/settings/AboutRoute.kt").readText()
        val paths = Regex("\"licenses/([A-Za-z0-9_]+\\.txt)\"").findAll(about).map { it.groupValues[1] }.toSet()
        assertTrue(
            paths.containsAll(setOf("GPL.txt", "FFMPEG_SOURCE.txt", "CODEC_LICENSES.txt", "ENGINE_NOTICES.txt", "COPYLEFT_TEXTS.txt")),
            "About must offer the GPL, the source offer, the codec, engine and shared-license notices: $paths",
        )
        for (p in paths) assertTrue(File(assets, p).length() > 0, "licenses/$p is offered in About but missing or empty")
    }

    @Test fun theRepositoryMirrorsMatchTheShippedAssets() {
        for (name in listOf("GPL.txt", "FFMPEG_SOURCE.txt", "CODEC_LICENSES.txt", "ENGINE_NOTICES.txt", "COPYLEFT_TEXTS.txt")) {
            assertEquals(asset(name), text(File(mirrors, name)), "licenses/$name differs from the asset the app ships")
        }
    }

    @Test fun theOfferPinsExactlyWhatFfbuildBuilds() {
        val offer = asset("FFMPEG_SOURCE.txt")
        val script = text(File("../transcode/build-ffmpeg/ffbuild.sh"))
        val pins = Regex("(?m)^[A-Z0-9]+_(?:COMMIT|SHA256)=([0-9a-f]{40,64})$").findAll(script).map { it.groupValues[1] }.toList()
        assertTrue(pins.size >= 8, "expected the git commits and tarball hashes pinned in ffbuild.sh, found $pins")
        for (pin in pins) assertTrue(pin in offer, "ffbuild.sh pins $pin but the written offer does not name it")
    }

    @Test fun theOfferNamesTheYoutubedlAndroidSourceTagTheAppLinks() {
        val gradle = text(File("../engine/build.gradle.kts"))
        val version = Regex("youtubedl-android:library:([0-9.]+)").find(gradle)!!.groupValues[1]
        assertEquals(version, Regex("youtubedl-android:ffmpeg:([0-9.]+)").find(gradle)!!.groupValues[1])
        val offer = asset("FFMPEG_SOURCE.txt")
        assertTrue("io.github.junkfood02.youtubedl-android:library:$version" in offer, "offer must name the library artifact $version")
        assertTrue(Regex("Tag: +$version = commit [0-9a-f]{40}").containsMatchIn(offer), "offer must pin the upstream tag $version to its commit")
        assertTrue("https://github.com/yausername/youtubedl-android" in offer)
    }

    @Test fun theOfferCoversTheBinariesInsideTheLibrary() {
        val offer = asset("FFMPEG_SOURCE.txt")
        for (needed in listOf(
            "libffmpeg.zip.so", "libpython.zip.so", "libqjs.so", "Termux", "termux-packages",
            "GNU Readline", "GNU dbm", "mutagen", "pycryptodomex", "QuickJS", "OpenSSL", "yt-dlp",
        )) assertTrue(needed in offer, "the written offer does not mention $needed")
        assertTrue(Regex("termux-packages +commit [0-9a-f]{40}").containsMatchIn(offer), "offer must pin the Termux recipes to a commit")
    }

    @Test fun theFiveNewCodecsHaveTheirLicenseTextsAndTheAomPatentLicense() {
        val codecs = asset("CODEC_LICENSES.txt")
        for (needed in listOf(
            "Opus 1.6.1", "libvpx 1.17.0", "SVT-AV1 4.2.0", "libwebp 1.6.0", "LAME 3.100",
            "Xiph.Org, Skype Limited",                          // Opus COPYING
            "The WebM Project authors",                         // libvpx LICENSE
            "Additional IP Rights Grant",                       // libvpx / libwebp PATENTS
            "BSD 3-Clause Clear License",                       // SVT-AV1 LICENSE.md
            "Alliance for Open Media Patent License 1.0",       // SVT-AV1 PATENTS.md
            "Google Inc",                                       // libwebp COPYING
            "GNU LIBRARY GENERAL PUBLIC LICENSE",               // LAME COPYING
        )) assertTrue(needed in codecs, "CODEC_LICENSES.txt is missing: $needed")
    }

    @Test fun theCodecNoticeIsGeneratedFromTheVersionsFfbuildPins() {
        val script = text(File("../transcode/build-ffmpeg/ffbuild.sh"))
        val codecs = asset("CODEC_LICENSES.txt")
        for (v in listOf("LAME", "OPUS", "VPX", "SVTAV1", "WEBP")) {
            val ver = Regex("(?m)^${v}_VER=(\\S+)$").find(script)!!.groupValues[1]
            val sha = Regex("(?m)^${v}_SHA256=([0-9a-f]{64})$").find(script)!!.groupValues[1]
            assertTrue(sha in codecs, "CODEC_LICENSES.txt was not regenerated for $v $ver (sha256 $sha): run transcode/build-ffmpeg/codec-licenses.sh")
        }
    }

    @Test fun theOfferSaysWhereToAskForTheSource() {
        // "Ask the project" is no offer without a way to ask.
        assertTrue(issuesUrl in asset("FFMPEG_SOURCE.txt"), "the written offer must give an address to ask at: $issuesUrl")
    }

    /** The download page is where the APK is primarily distributed, so it carries the same offer as the app. */
    private val site get() = text(File("../site/index.html"))

    @Test fun theDownloadPageOfferNamesEverythingTheAppsOfferDoes() {
        val offer = asset("FFMPEG_SOURCE.txt")
        val script = text(File("../transcode/build-ffmpeg/ffbuild.sh"))
        val page = site
        // Part 1: every pinned commit and tarball hash, and every flag of the real configure line.
        val pins = Regex("(?m)^[A-Z0-9]+_(?:COMMIT|SHA256)=([0-9a-f]{40,64})$").findAll(script).map { it.groupValues[1] }.toList()
        for (pin in pins) assertTrue(pin in page, "ffbuild.sh pins $pin but the download page's offer does not name it")
        val flags = Regex("--enable-(?:lib[a-z0-9]+|gpl|version3|jni|mediacodec)").findAll(script).map { it.value }.toSet()
        assertTrue(flags.size >= 10, "expected the configure flags in ffbuild.sh, found $flags")
        for (flag in flags) assertTrue(flag in page, "the download page's configure line lacks $flag")
        // Part 2: the library tag, the Termux recipes, QuickJS.
        val gradle = text(File("../engine/build.gradle.kts"))
        val version = Regex("youtubedl-android:library:([0-9.]+)").find(gradle)!!.groupValues[1]
        val tagCommit = Regex("Tag: +$version = commit ([0-9a-f]{40})").find(offer)!!.groupValues[1]
        val termuxCommit = Regex("termux-packages +commit ([0-9a-f]{40})").find(offer)!!.groupValues[1]
        val quickJs = Regex("quickjs-[0-9-]+\\.tar\\.xz\\s+sha256: +([0-9a-f]{64})").find(offer)!!.groupValues[1]
        for (needed in listOf(version, tagCommit, termuxCommit, quickJs, "GNU Readline", "mutagen", "Termux", "QuickJS", "yt-dlp")) {
            assertTrue(needed in page, "the download page's offer does not mention $needed")
        }
        // Part 3, the three-year term, and somewhere to ask.
        assertTrue("curl-impersonate" in page && "build-impersonate/build.sh" in page, "the download page's offer must cover the impersonation bundle")
        assertTrue("three (3) years" in page, "the download page must state the three-year term")
        assertTrue(issuesUrl in page, "the download page must say where to ask for the source")
    }

    @Test fun theDownloadPageLinksEveryNoticeFileAndTheyExist() {
        val page = site
        val linked = Regex("https://github\\.com/Massivemiike/sieve-android/blob/main/([A-Za-z0-9_./-]+\\.txt)").findAll(page).map { it.groupValues[1] }.toSet()
        for (name in listOf("FFMPEG_SOURCE.txt", "CODEC_LICENSES.txt", "ENGINE_NOTICES.txt", "COPYLEFT_TEXTS.txt", "IMPERSONATE_LICENSES.txt")) {
            assertTrue(linked.any { it.endsWith("/$name") }, "the download page does not link $name")
        }
        for (path in linked) assertTrue(File("..", path).isFile, "the download page links $path, which is not in the repository")
    }

    @Test fun theReadmeDoesNotOverclaimWhatShips() {
        val readme = text(File("../README.md"))
        assertFalse("licenses of all bundled components" in readme, "README still claims every bundled component's license ships in the app")
        assertTrue("CODEC_LICENSES.txt" in readme && "ENGINE_NOTICES.txt" in readme, "README should list the notice files that do ship")
    }
}
