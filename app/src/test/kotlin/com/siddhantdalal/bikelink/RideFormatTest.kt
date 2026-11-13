package com.siddhantdalal.bikelink

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.util.Locale

class RideFormatTest {

    @Test
    fun `durations round up to whole minutes`() {
        assertEquals("1 min", RideFormat.duration(30))
        assertEquals("1 min", RideFormat.duration(60))
        assertEquals("16 min", RideFormat.duration(15 * 60 + 1))
        assertEquals("59 min", RideFormat.duration(59 * 60))
    }

    @Test
    fun `an hour or more shows hours and minutes`() {
        assertEquals("1 h 00 min", RideFormat.duration(60 * 60))
        assertEquals("1 h 05 min", RideFormat.duration(65 * 60))
        assertEquals("19 h 20 min", RideFormat.duration(19 * 3600 + 20 * 60))
    }

    @Test
    fun `short distances are in metres, rounded to 10`() {
        assertEquals("0 m", RideFormat.distance(0))
        assertEquals("300 m", RideFormat.distance(296))
        assertEquals("990 m", RideFormat.distance(994))
    }

    @Test
    fun `longer distances are in kilometres`() {
        assertEquals("6.2 km", RideFormat.distance(6_200))
        assertEquals("9.9 km", RideFormat.distance(9_940))
        assertEquals("12 km", RideFormat.distance(11_600))
        assertEquals("1034 km", RideFormat.distance(1_034_000))
    }

    @Test
    fun `negative values never show`() {
        assertEquals("0 min", RideFormat.duration(-5))
        assertEquals("0 m", RideFormat.distance(-5))
    }

    companion object {
        private lateinit var saved: Locale

        // The decimal separator follows the phone's language, so pin one for predictable assertions.
        @BeforeClass
        @JvmStatic
        fun pinLocale() {
            saved = Locale.getDefault()
            Locale.setDefault(Locale.UK)
        }

        @AfterClass
        @JvmStatic
        fun restoreLocale() = Locale.setDefault(saved)
    }
}
