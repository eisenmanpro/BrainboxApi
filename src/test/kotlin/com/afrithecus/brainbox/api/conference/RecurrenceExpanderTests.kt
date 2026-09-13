package com.afrithecus.brainbox.api.conference

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** RRULE expansion used to materialise recurring conference slots (CONF-1). */
class RecurrenceExpanderTests {

    private val zone = ZoneId.of("Africa/Nairobi")
    private val start = Instant.parse("2026-09-07T07:00:00Z") // Monday 10:00 EAT

    @Test
    fun `weekly count expands on the same weekday`() {
        val result = RecurrenceExpander.expand(start, "FREQ=WEEKLY;COUNT=3", zone)
        check(result.occurrences.size == 3)
        check(Duration.between(result.occurrences[0], result.occurrences[1]).toDays() == 7L)
        check(!result.truncated)
    }

    @Test
    fun `weekly byday expands each selected weekday`() {
        val result = RecurrenceExpander.expand(start, "FREQ=WEEKLY;BYDAY=MO,WE;COUNT=3", zone)
        check(result.occurrences.size == 3)
        check(Duration.between(result.occurrences[0], result.occurrences[1]).toDays() == 2L)
        check(Duration.between(result.occurrences[1], result.occurrences[2]).toDays() == 5L)
    }

    @Test
    fun `daily interval and monthly step`() {
        val daily = RecurrenceExpander.expand(start, "FREQ=DAILY;INTERVAL=2;COUNT=3", zone)
        check(daily.occurrences.size == 3)
        check(Duration.between(daily.occurrences[0], daily.occurrences[1]).toDays() == 2L)
        val monthly = RecurrenceExpander.expand(start, "FREQ=MONTHLY;COUNT=3", zone)
        check(monthly.occurrences.size == 3)
        check(Duration.between(monthly.occurrences[0], monthly.occurrences[1]).toDays() >= 27L)
    }

    @Test
    fun `unknown rules and missing counts fall back safely`() {
        check(RecurrenceExpander.expand(start, "FREQ=YEARLY;COUNT=3", zone).occurrences.size == 1)
        check(RecurrenceExpander.expand(start, null, zone).occurrences.size == 1)
        check(RecurrenceExpander.expand(start, "FREQ=WEEKLY", zone).occurrences.size == 8)
    }

    @Test
    fun `count is capped and until limits the window`() {
        val capped = RecurrenceExpander.expand(start, "FREQ=WEEKLY;COUNT=100", zone, maxCount = 52)
        check(capped.occurrences.size == 52)
        check(capped.truncated)
        val until = RecurrenceExpander.expand(start, "FREQ=WEEKLY;UNTIL=20260921T070000Z", zone)
        check(until.occurrences.size == 3)
    }
}
