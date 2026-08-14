package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the [RRuleExpander.MAX_ITERATIONS] per-series expansion work-bound (US2).
 *
 * The bound is a DoS guard (CWE-400): a pathologically frequent rule (FREQ=SECONDLY)
 * or a very wide window can otherwise force effectively unbounded generation. When a
 * single series would produce more than [RRuleExpander.MAX_ITERATIONS] generated
 * instances the expander aborts with [RRuleExpander.ExpansionLimitException] rather
 * than returning a truncated (silently wrong) list.
 *
 * Two properties are asserted together:
 *  - EFFECTIVE: a far-seed FREQ=SECONDLY (a seed decades before the window) aborts
 *    quickly. This is the case ical4j 4.3.0 mishandles: its own seed fast-forward in
 *    RecurDateSpliterator doubles an int multiplier and overflows to 0 for a large
 *    seconds gap, spinning forever. The expander fast-forwards the seed itself with
 *    overflow-safe long math to avoid it.
 *  - FALSE-POSITIVE-FREE: a far-seed FREQ=HOURLY over a one-week window (~168 hits)
 *    must NOT abort, and a plain FREQ=MONTHLY far-seed rule must still emit its true
 *    day-of-month (the fast-forward must not day-clamp the seed).
 */
@DisplayName("RRuleExpander expansion work-bound (MAX_ITERATIONS)")
class RRuleExpanderLimitTest {

    private val expander = RRuleExpander()
    private val zone = ZoneId.of("UTC")

    // ═══════════════════════════════════════════════════════════════════
    // AC1: a bounded series returns its occurrences
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `DAILY over 100 days returns all occurrences without aborting`() {
        val event = event(rrule = RRule(freq = Frequency.DAILY), start = zdt(2026, 1, 1))
        val range = TimeRange(zdt(2026, 1, 1).toInstant(), zdt(2026, 1, 1).plusDays(100).toInstant())

        val occurrences = expander.expand(event, range)

        assertTrue(occurrences.size in 99..101, "expected ~100 daily occurrences, got ${occurrences.size}")
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC2 + boundary: exceeding the bound aborts; hitting it exactly does not
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `near-seed SECONDLY over three days aborts with ExpansionLimitException`() {
        // 3 days = 259,200 potential per-second occurrences, far past MAX_ITERATIONS.
        val event = event(rrule = RRule(freq = Frequency.SECONDLY), start = zdt(2026, 1, 1))
        val range = TimeRange(zdt(2026, 1, 1).toInstant(), zdt(2026, 1, 1).plusDays(3).toInstant())

        assertFailsWith<RRuleExpander.ExpansionLimitException> {
            expander.expand(event, range)
        }
    }

    @Test
    fun `exactly MAX_ITERATIONS in-window does not abort (boundary)`() {
        // COUNT is not fast-forwarded; the count-limited getDates returns exactly
        // MAX_ITERATIONS, which is <= the limit, so no abort.
        val count = RRuleExpander.MAX_ITERATIONS
        val event = event(rrule = RRule(freq = Frequency.SECONDLY, count = count), start = zdt(2026, 1, 1))
        val range = TimeRange(zdt(2026, 1, 1).toInstant(), zdt(2026, 1, 1).plusDays(2).toInstant())

        val occurrences = expander.expand(event, range)

        assertEquals(count, occurrences.size, "exactly MAX_ITERATIONS should be returned intact")
    }

    @Test
    fun `one past MAX_ITERATIONS in-window aborts (boundary)`() {
        val event = event(
            rrule = RRule(freq = Frequency.SECONDLY, count = RRuleExpander.MAX_ITERATIONS + 1),
            start = zdt(2026, 1, 1)
        )
        val range = TimeRange(zdt(2026, 1, 1).toInstant(), zdt(2026, 1, 1).plusDays(2).toInstant())

        assertFailsWith<RRuleExpander.ExpansionLimitException> {
            expander.expand(event, range)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC3: effective + false-positive-free, under a wall-clock bound
    // (the far-seed SECONDLY variant is added below once the fix is in place;
    //  it would hang forever against the unfixed ical4j path.)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `near-seed SECONDLY multi-day aborts fast`() {
        val event = event(rrule = RRule(freq = Frequency.SECONDLY), start = zdt(2026, 6, 1))
        val range = TimeRange(zdt(2026, 6, 1).toInstant(), zdt(2026, 6, 5).toInstant())

        assertFailsWith<RRuleExpander.ExpansionLimitException> {
            expander.expand(event, range)
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `far-seed SECONDLY from 1970 aborts fast (ical4j overflow guard)`() {
        // DTSTART decades before the window. Handed straight to ical4j 4.3.0 this
        // hangs forever (its int-multiplier seed fast-forward overflows to 0). The
        // expander fast-forwards the seed itself, then aborts at the bound.
        val event = event(rrule = RRule(freq = Frequency.SECONDLY), start = zdt(1970, 1, 1))
        val range = TimeRange(zdt(2026, 3, 2).toInstant(), zdt(2026, 3, 5).toInstant())

        assertFailsWith<RRuleExpander.ExpansionLimitException> {
            expander.expand(event, range)
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `far-seed HOURLY over one week does NOT abort (false-positive-free)`() {
        // DTSTART six years before the window; ~168 hits in a one-week window.
        val event = event(rrule = RRule(freq = Frequency.HOURLY), start = zdt(2020, 1, 1))
        val range = TimeRange(zdt(2026, 3, 2).toInstant(), zdt(2026, 3, 9).toInstant())

        val occurrences = expander.expand(event, range)

        assertTrue(occurrences.size in 167..169, "expected ~168 hourly occurrences, got ${occurrences.size}")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Finding 2 (review-plan): the fast-forward must NOT touch MONTHLY/YEARLY.
    // A plain far-seed monthly rule seeded on the 31st must keep emitting day 31
    // (skipping short months per RFC 5545), never a day-clamped 28/30. This is a
    // regression guard: it fails if the fast-forward gate is widened to MONTHS.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `far-seed plain MONTHLY on day 31 keeps day 31 (no seed day-clamp)`() {
        // Seeded 2019-01-31, queried in 2026. rangeStart is chosen so that a
        // (wrong) whole-month seed advance would land on 2026-02-28 and clamp the
        // implicit month-day to 28. The correct path leaves the seed untouched.
        val event = event(rrule = RRule(freq = Frequency.MONTHLY), start = zdt(2019, 1, 31))
        val range = TimeRange(zdt(2026, 3, 1).toInstant(), zdt(2026, 12, 31).toInstant())

        val occurrences = expander.expand(event, range)

        assertTrue(occurrences.isNotEmpty(), "a monthly-on-the-31st series should have occurrences in the window")
        occurrences.forEach { occ ->
            val day = occ.dtStart.toZonedDateTime().dayOfMonth
            assertEquals(31, day, "every occurrence must fall on day 31, got ${occ.dtStart.toICalString()}")
        }
    }

    // ══════════════════════════════ helpers ══════════════════════════════

    private fun zdt(year: Int, month: Int, day: Int, hour: Int = 10): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone)

    private fun event(
        rrule: RRule,
        start: ZonedDateTime,
        uid: String = "limit-test-${System.nanoTime()}"
    ): ICalEvent = ICalEvent(
        uid = uid,
        importId = uid,
        summary = "Limit Test",
        description = null,
        location = null,
        dtStart = ICalDateTime.fromZonedDateTime(start, isDate = false),
        dtEnd = ICalDateTime.fromZonedDateTime(start.plusHours(1), isDate = false),
        duration = null,
        isAllDay = false,
        status = EventStatus.CONFIRMED,
        sequence = 0,
        rrule = rrule,
        exdates = emptyList(),
        recurrenceId = null,
        alarms = emptyList(),
        categories = emptyList(),
        organizer = null,
        attendees = emptyList(),
        color = null,
        dtstamp = null,
        lastModified = null,
        created = null,
        transparency = Transparency.OPAQUE,
        url = null,
        rawProperties = emptyMap()
    )
}
