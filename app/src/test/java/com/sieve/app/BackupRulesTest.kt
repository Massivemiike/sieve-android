package com.sieve.app

import com.sieve.app.settings.CookiesStore
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * cookies.txt, the proxy (which may carry "user:pass@") and queue rows (engine args with --proxy and
 * --cookies) must never reach a Google Drive backup or a device-to-device transfer. Both rule files
 * are needed: minSdk 26 reads fullBackupContent (up to API 30), API 31+ reads dataExtractionRules.
 * Unit tests run with the module directory as the working directory.
 */
class BackupRulesTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String): Element {
        val f = File(path)
        assertTrue(f.isFile, "missing ${f.absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(f).documentElement
    }

    /** `domain/path` of every <exclude> directly under [parent]. */
    private fun excludes(parent: Element): Set<String> =
        parent.getElementsByTagName("exclude").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }.map { "${it.getAttribute("domain")}/${it.getAttribute("path")}" }.toSet()
        }

    private fun section(root: Element, name: String): Element {
        val nodes = root.getElementsByTagName(name)
        assertEquals(1, nodes.length, "expected one <$name>")
        return nodes.item(0) as Element
    }

    /** What must stay out of any backup. The names are pinned to the code below. */
    private val required = setOf(
        "file/${CookiesStore.FILE_NAME}",
        "file/${CookiesStore.FILE_NAME}.tmp",
        "file/sieve.preferences_pb",
        "file/sieve.preferences_pb.tmp",
        "database/sieve.db",
        "database/sieve.db-wal",
        "database/sieve.db-shm",
        "database/sieve.db-journal",
        // The self-update APK (tens of MB) in getExternalFilesDir(null)/updates: Auto Backup includes the
        // "external" domain by default, and one over the 25 MB quota makes the whole backup be skipped.
        "external/updates",
    )

    @Test fun theManifestPointsAtBothRuleFiles() {
        val app = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(android, "fullBackupContent"))
    }

    @Test fun androidSixToElevenExcludesTheSecrets() {
        val root = parse("src/main/res/xml/backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        val missing = required - excludes(root)
        assertTrue(missing.isEmpty(), "backup_rules.xml does not exclude $missing")
    }

    @Test fun androidTwelvePlusExcludesTheSecretsFromBackupAndFromDeviceTransfer() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        for (name in listOf("cloud-backup", "device-transfer")) {
            val missing = required - excludes(section(root, name))
            assertTrue(missing.isEmpty(), "<$name> does not exclude $missing")
        }
    }

    @Test fun mediaAndWorkFilesStayOutOfBackupToo() {
        // work/<job> holds partial downloads and output/Sieve the last-resort sink: they would blow the 25 MB cap.
        val media = setOf("file/work", "file/output")
        assertTrue(excludes(parse("src/main/res/xml/backup_rules.xml")).containsAll(media))
        val extraction = parse("src/main/res/xml/data_extraction_rules.xml")
        for (name in listOf("cloud-backup", "device-transfer")) assertTrue(excludes(section(extraction, name)).containsAll(media), name)
    }

    @Test fun theExcludedNamesAreTheOnesTheAppActuallyCreates() {
        // Renaming the DataStore file or the database would silently re-expose it; this fails first.
        val graph = File("src/main/java/com/sieve/app/di/AppGraph.kt").readText()
        assertTrue("\"sieve.preferences_pb\"" in graph, "AppGraph no longer names the DataStore file sieve.preferences_pb; update the backup rules")
        assertTrue("\"sieve.db\"" in graph, "AppGraph no longer names the database sieve.db; update the backup rules")
        // ...and the same for the update APK's folder.
        val downloader = File("src/main/java/com/sieve/app/update/ApkDownloader.kt").readText()
        assertTrue("getExternalFilesDir(null), \"updates\"" in downloader, "ApkDownloader no longer writes to external files/updates; update the backup rules")
    }
}
