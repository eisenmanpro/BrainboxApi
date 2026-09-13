package com.afrithecus.brainbox.api.conference

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Minimal RFC 5545 RRULE expansion for conference slots (CONF-1). The server
 * expands a recurring slot into concrete occurrences at create time so every
 * occurrence is independently bookable; clients never implement RRULE.
 *
 * Supported: FREQ=DAILY|WEEKLY|MONTHLY, INTERVAL, COUNT, UNTIL and BYDAY (weekly).
 * An unknown or unparseable rule yields no additional occurrences.
 */
object RecurrenceExpander {

    data class Result(val occurrences: List<Instant>, val truncated: Boolean)

    fun expand(
        start: Instant,
        rule: String?,
        zone: ZoneId,
        defaultCount: Int = 8,
        maxCount: Int = 52,
    ): Result {
        val trimmed = rule?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result(listOf(start), false)
        val parts = trimmed.split(";").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx).trim().uppercase() to part.substring(idx + 1).trim()
        }.toMap()
        val freq = parts["FREQ"]?.uppercase() ?: return Result(listOf(start), false)
        val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        val requested = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 } ?: defaultCount
        val count = requested.coerceAtMost(maxCount)
        val until = parts["UNTIL"]?.let(::parseUntil)
        val base = start.atZone(zone)
        val out = mutableListOf<ZonedDateTime>()
        var guard = 0
        when (freq) {
            "DAILY" -> {
                var cursor = base
                while (out.size < count && guard < count * interval + 366) {
                    if (until != null && cursor.toInstant().isAfter(until)) break
                    out += cursor
                    cursor = cursor.plusDays(interval.toLong())
                    guard++
                }
            }
            "WEEKLY" -> {
                val days = parseByDay(parts["BYDAY"]) ?: listOf(base.dayOfWeek)
                var weekStart = base.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                while (out.size < count && guard < count * interval + 104) {
                    days.sortedBy { it.value }.forEach { day ->
                        val candidate = ZonedDateTime.of(weekStart.with(day), base.toLocalTime(), zone)
                        if (out.size < count && !candidate.isBefore(base) && (until == null || !candidate.toInstant().isAfter(until))) {
                            out += candidate
                        }
                    }
                    weekStart = weekStart.plusWeeks(interval.toLong())
                    guard++
                }
            }
            "MONTHLY" -> {
                var cursor = base
                while (out.size < count && guard < count * interval + 24) {
                    if (until != null && cursor.toInstant().isAfter(until)) break
                    out += cursor
                    cursor = cursor.plusMonths(interval.toLong())
                    guard++
                }
            }
            else -> return Result(listOf(start), false)
        }
        val occurrences = out.map { it.toInstant() }.distinct().sorted()
        return Result(occurrences.ifEmpty { listOf(start) }, requested > maxCount)
    }

    private fun parseByDay(raw: String?): List<DayOfWeek>? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val days = value.split(",").mapNotNull { token ->
            when (token.trim().uppercase().takeLast(2)) {
                "MO" -> DayOfWeek.MONDAY
                "TU" -> DayOfWeek.TUESDAY
                "WE" -> DayOfWeek.WEDNESDAY
                "TH" -> DayOfWeek.THURSDAY
                "FR" -> DayOfWeek.FRIDAY
                "SA" -> DayOfWeek.SATURDAY
                "SU" -> DayOfWeek.SUNDAY
                else -> null
            }
        }
        return days.takeIf { it.isNotEmpty() }
    }

    private fun parseUntil(raw: String): Instant? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        runCatching { return Instant.parse(value) }
        runCatching { return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC) }
        runCatching { return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC) }
        runCatching { return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant() }
        runCatching { return LocalDate.parse(value).plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant() }
        runCatching { return ZonedDateTime.parse(value).toInstant() }
        return null
    }
}
