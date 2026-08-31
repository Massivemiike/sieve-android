package com.sieve.storage.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeValidatorTest {
    private fun oracle(persisted: Boolean, writable: Boolean) = object : TreePermissionOracle {
        override fun isPersisted(uri: String) = persisted
        override fun canWrite(uri: String) = writable
    }

    @Test fun `null uri is invalid`() {
        assertFalse(TreeValidator.validate(null, oracle(true, true)))
    }

    @Test fun `valid only when persisted AND writable`() {
        assertTrue(TreeValidator.validate("content://t", oracle(true, true)))
        assertFalse(TreeValidator.validate("content://t", oracle(false, true)))
        assertFalse(TreeValidator.validate("content://t", oracle(true, false)))
    }
}
