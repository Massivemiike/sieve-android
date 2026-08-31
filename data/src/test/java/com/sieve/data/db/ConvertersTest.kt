package com.sieve.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {
    private val c = Converters()

    @Test fun `string list round trips`() {
        val list = listOf("-f", "best", "--add-header", "X:Y")
        assertEquals(list, c.toStringList(c.fromStringList(list)))
    }

    @Test fun `empty and null string list`() {
        assertEquals(emptyList<String>(), c.toStringList(c.fromStringList(emptyList())))
        assertEquals(emptyList<String>(), c.toStringList(null))
    }
}
