package com.siddhantdalal.bikelink

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth

/**
 * Caps how many destinations Bike Link asks Google to route: [DAILY_LIMIT] a day and [MONTHLY_LIMIT] a month.
 *
 * Google bills the Navigation SDK per destination, with 7,000 free a month on India pricing. The project's quotas
 * can't be lowered in Google Cloud, but the API key only works from this app, so this cap is a hard ceiling on cost.
 * Rerouting and guidance aren't billed, so they don't count.
 */
object RouteBudget {

    const val DAILY_LIMIT = 200
    const val MONTHLY_LIMIT = 6_500

    private const val PREFS = "route_budget"

    /** Destinations used on [day] and in [month]. Pure, so the rules can be unit-tested. */
    data class Usage(val day: String, val dayCount: Int, val month: String, val monthCount: Int) {

        /** This usage as of [date]: a new day or month starts its count from zero. */
        fun on(date: LocalDate): Usage {
            val today = date.toString()
            val thisMonth = YearMonth.from(date).toString()
            return Usage(
                day = today,
                dayCount = if (day == today) dayCount else 0,
                month = thisMonth,
                monthCount = if (month == thisMonth) monthCount else 0,
            )
        }

        val hasRoom: Boolean get() = dayCount < DAILY_LIMIT && monthCount < MONTHLY_LIMIT

        fun plusOne(): Usage = copy(dayCount = dayCount + 1, monthCount = monthCount + 1)
    }

    fun hasRoom(context: Context): Boolean = load(context).on(LocalDate.now()).hasRoom

    /** Records one destination if the budget allows it. Returns false, recording nothing, once it's used up. */
    @Synchronized
    fun tryUse(context: Context): Boolean {
        val usage = load(context).on(LocalDate.now())
        if (!usage.hasRoom) return false
        save(context, usage.plusOne())
        return true
    }

    private fun load(context: Context): Usage {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Usage(
            day = prefs.getString("day", "").orEmpty(),
            dayCount = prefs.getInt("day_count", 0),
            month = prefs.getString("month", "").orEmpty(),
            monthCount = prefs.getInt("month_count", 0),
        )
    }

    private fun save(context: Context, usage: Usage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("day", usage.day)
            .putInt("day_count", usage.dayCount)
            .putString("month", usage.month)
            .putInt("month_count", usage.monthCount)
            .apply()
    }
}
