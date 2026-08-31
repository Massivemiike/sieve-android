package com.sieve.app

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildConfigTest {

    @Test
    fun versionInfoIsPresent() {
        assertTrue(BuildConfig.VERSION_CODE >= 1, "versionCode must be >= 1")
        assertTrue(BuildConfig.VERSION_NAME.isNotBlank(), "versionName must be set")
        assertTrue(BuildConfig.APPLICATION_ID == "com.sieve.app")
    }
}
