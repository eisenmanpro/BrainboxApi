package com.afrithecus.brainbox.api.attendance

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Budget bucket for D5. Kenyan school terms do not have a calendar table in the API yet, so the
 * term is derived from the month with the usual three-term split (Jan-Apr, May-Aug, Sep-Dec).
 * The label is stored on the budget row, so changing this mapping never rewrites history.
 */
object AttendanceTerm {

    /** Inclusive local-day range of a term label, as instants for ledger queries. */
    data class Range(val start: Instant, val end: Instant)

    fun label(date: LocalDate): String {
        val term = when (date.monthValue) {
            in 1..4 -> "T1"
            in 5..8 -> "T2"
            else -> "T3"
        }
        return date.year.toString() + "-" + term
    }

    /** "2026-T2" -> 1 May 2026 00:00 .. 1 Sep 2026 00:00 (exclusive) in [zone]. */
    fun range(label: String, zone: ZoneId): Range? {
        val parts = label.trim().split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val (startMonth, endMonthExclusive) = when (parts[1].uppercase()) {
            "T1" -> 1 to 5
            "T2" -> 5 to 9
            "T3" -> 9 to 13
            else -> return null
        }
        val start = LocalDate.of(year, startMonth, 1).atStartOfDay(zone).toInstant()
        val end = LocalDate.of(year, 1, 1).plusMonths(endMonthExclusive.toLong() - 1L)
            .atStartOfDay(zone).toInstant()
        return Range(start, end)
    }
}
