package com.sieve.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateManifestTest {

    @Test fun parsesAllFields() {
        val m = UpdateManifestJson.parse(
            """{"versionCode":5,"versionName":"1.2.0","apkUrl":"https://x/sieve.apk","sha256":"ABC123","minSdk":26,"changelog":"Fixes"}""",
        )!!
        assertEquals(5, m.versionCode)
        assertEquals("1.2.0", m.versionName)
        assertEquals("https://x/sieve.apk", m.apkUrl)
        assertEquals("ABC123", m.sha256)
        assertEquals(26, m.minSdk)
        assertEquals("Fixes", m.changelog)
    }

    @Test fun ignoresUnknownKeysAndUsesDefaults() {
        val m = UpdateManifestJson.parse(
            """{"versionCode":2,"versionName":"1.0","apkUrl":"u","sha256":"s","futureField":true}""",
        )!!
        assertEquals(2, m.versionCode)
        assertEquals(26, m.minSdk)  // default
        assertEquals("", m.changelog)
    }

    @Test fun malformedOrIncompleteReturnsNull() {
        assertNull(UpdateManifestJson.parse("not json"))
        assertNull(UpdateManifestJson.parse(""))
        assertNull(UpdateManifestJson.parse("""{"versionName":"no code"}"""))
    }
}
