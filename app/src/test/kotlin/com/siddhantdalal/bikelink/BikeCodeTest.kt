package com.siddhantdalal.bikelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeCodeTest {

    @Test
    fun `formats in two groups of four`() {
        assertEquals("ABCD-EFGH", BikeCode.formatted("ABCDEFGH"))
    }

    @Test
    fun `accepts the code with or without dash and in any case`() {
        assertTrue(BikeCode.matches("ABCDEFGH", "ABCD-EFGH"))
        assertTrue(BikeCode.matches("ABCDEFGH", "abcdefgh"))
        assertTrue(BikeCode.matches("ABCDEFGH", " abcd efgh "))
    }

    @Test
    fun `rejects wrong or missing codes`() {
        assertFalse(BikeCode.matches("ABCDEFGH", "ABCD-EFGJ"))
        assertFalse(BikeCode.matches("ABCDEFGH", "ABCD"))
        assertFalse(BikeCode.matches("ABCDEFGH", ""))
        assertFalse(BikeCode.matches("ABCDEFGH", null))
    }
}
