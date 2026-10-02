package com.sieve.app

import com.sieve.app.net.LocalNetworkAccess
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Android 17 local-network permission and the target level go together: an app that targets 37 loses all LAN traffic without
 * `ACCESS_LOCAL_NETWORK`, and the platform documents "do not declare it" for an app that targets 36 or lower (below 37 such an app
 * has the access implicitly). So the manifest must name it exactly while `targetSdk` is 37 or higher, and the name the code asks
 * for at run time ([LocalNetworkAccess.PERMISSION]) must be the one the manifest declares. Static read of the manifest and
 * `app/build.gradle.kts` (both are declared as inputs of the unit-test task there).
 */
class ManifestPermissionsTest {

    private val android = "http://schemas.android.com/apk/res/android"
    private val moduleDir: File = File(".").absoluteFile.let { if (File(it, "build.gradle.kts").isFile) it else File(it, "app") }

    private fun declaredPermissions(): List<Element> {
        val f = File(moduleDir, "src/main/AndroidManifest.xml")
        assertTrue(f.isFile, "missing ${f.absolutePath}")
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f).documentElement
        val nodes = root.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun targetSdk(): Int {
        val code = BuildScriptText.stripComments(File(moduleDir, "build.gradle.kts").readText())
        val all = Regex("""\btargetSdk\s*=\s*(\d+)""").findAll(code).map { it.groupValues[1].toInt() }.toList()
        assertEquals(1, all.size, "expected one literal targetSdk in app/build.gradle.kts, found $all")
        return all.single()
    }

    @Test fun theLocalNetworkPermissionIsDeclaredExactlyWhileTargetSdkIs37OrHigher() {
        val names = declaredPermissions().map { it.getAttributeNS(android, "name") }
        val declared = LocalNetworkAccess.PERMISSION in names
        assertEquals(
            targetSdk() >= LocalNetworkAccess.FIRST_API, declared,
            "targetSdk ${targetSdk()}: ${LocalNetworkAccess.PERMISSION} must be declared if and only if targetSdk >= ${LocalNetworkAccess.FIRST_API} (declared: $names)",
        )
    }

    @Test fun theLocalNetworkPermissionIsDeclaredOnceAndForEveryApiLevel() {
        val mine = declaredPermissions().filter { it.getAttributeNS(android, "name") == LocalNetworkAccess.PERMISSION }
        assertEquals(1, mine.size, "declare ${LocalNetworkAccess.PERMISSION} once")
        // no maxSdkVersion: it must hold on every device that enforces it, today and after Android 17
        assertEquals("", mine.single().getAttributeNS(android, "maxSdkVersion"))
    }
}
