package com.siddhantdalal.bikelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RouteBudgetTest {

    private val nov14 = LocalDate.of(2026, 11, 14)

    @Test
    fun `fresh usage has room`() {
        val usage = RouteBudget.Usage("", 0, "", 0).on(nov14)
        assertTrue(usage.hasRoom)
        assertEquals(RouteBudget.Usage("2026-11-14", 0, "2026-11", 0), usage)
    }

    @Test
    fun `daily limit blocks the next destination`() {
        val usage = RouteBudget.Usage("2026-11-14", RouteBudget.DAILY_LIMIT, "2026-11", RouteBudget.DAILY_LIMIT)
        assertFalse(usage.on(nov14).hasRoom)
    }

    @Test
    fun `a new day resets the daily count but not the monthly one`() {
        val yesterday = RouteBudget.Usage("2026-11-13", RouteBudget.DAILY_LIMIT, "2026-11", 900)
        val today = yesterday.on(nov14)
        assertEquals(0, today.dayCount)
        assertEquals(900, today.monthCount)
        assertTrue(today.hasRoom)
    }

    @Test
    fun `monthly limit blocks even on a fresh day`() {
        val usage = RouteBudget.Usage("2026-11-13", 3, "2026-11", RouteBudget.MONTHLY_LIMIT)
        assertFalse(usage.on(nov14).hasRoom)
    }

    @Test
    fun `a new month resets both counts`() {
        val october = RouteBudget.Usage("2026-10-31", 150, "2026-10", RouteBudget.MONTHLY_LIMIT)
        val usage = october.on(LocalDate.of(2026, 11, 1))
        assertEquals(RouteBudget.Usage("2026-11-01", 0, "2026-11", 0), usage)
    }

    @Test
    fun `using one counts towards both limits`() {
        val usage = RouteBudget.Usage("2026-11-14", 4, "2026-11", 40).plusOne()
        assertEquals(5, usage.dayCount)
        assertEquals(41, usage.monthCount)
    }

    @Test
    fun `the monthly cap stays inside Google's free 7,000 a month`() {
        assertTrue(RouteBudget.MONTHLY_LIMIT < 7_000)
    }
}
