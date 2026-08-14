package org.onekash.mcp.calendar.ics

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [IcsParser.parseOccurrences] — recurrence expansion on the read path.
 *
 * [IcsParser.parse] maps each VEVENT verbatim, so a recurring series reports the
 * master's DTSTART regardless of which range was queried. These tests pin the
 * expanded behavior: the caller gets the occurrence that falls in the range.
 */
class IcsParserOccurrenceTest {

    private val parser = IcsParser()

    /** Mirrors the window CalendarService builds for a single-day query. */
    private fun day(date: String) = Pair(
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant(),
        LocalDate.parse(date).atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC)
    )

    // ═══════════════════════════════════════════════════════════════════
    // THE REPORTED BUG
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `yearly all-day event reports the queried year, not the master's`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:yearly-allday@example.com
            DTSTART;VALUE=DATE:20230517
            DTEND;VALUE=DATE:20230518
            RRULE:FREQ=YEARLY
            SUMMARY:Recurring yearly event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = day("2026-05-17")
        val events = parser.parseOccurrences(ics, start, end)

        assertEquals(1, events.size, "the 2026 occurrence should be returned")
        val e = events[0]
        assertTrue(e.isAllDay)
        assertEquals("2026-05-17", e.startDate, "must report the occurrence date, not the 2023 master")
        assertEquals("2026-05-17", e.endDate)
    }

    @Test
    fun `parse (unexpanded) still reports the master date - documents the difference`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:yearly-allday@example.com
            DTSTART;VALUE=DATE:20230517
            DTEND;VALUE=DATE:20230518
            RRULE:FREQ=YEARLY
            SUMMARY:Recurring yearly event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        assertEquals("2023-05-17", parser.parse(ics).single().startDate)
    }

    @Test
    fun `weekly timed event reports the occurrence in range`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:weekly-timed@example.com
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly sync
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = day("2026-02-02") // a later Monday
        val events = parser.parseOccurrences(ics, start, end)

        assertEquals(1, events.size)
        assertNotNull(events[0].startTime)
        assertTrue(
            events[0].startTime!!.startsWith("2026-02-02"),
            "expected the 2026-02-02 occurrence, got ${events[0].startTime}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // RECURRENCE SEMANTICS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `EXDATE suppresses the excluded occurrence`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:daily-excluded@example.com
            DTSTART;VALUE=DATE:20260101
            DTEND;VALUE=DATE:20260102
            RRULE:FREQ=DAILY
            EXDATE;VALUE=DATE:20260103
            SUMMARY:Daily with a gap
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (s1, e1) = day("2026-01-03")
        assertTrue(parser.parseOccurrences(ics, s1, e1).isEmpty(), "EXDATE'd day must be empty")

        val (s2, e2) = day("2026-01-04")
        assertEquals(1, parser.parseOccurrences(ics, s2, e2).size, "neighboring day still occurs")
    }

    @Test
    fun `RECURRENCE-ID override replaces that occurrence`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:series@example.com
            DTSTART;VALUE=DATE:20260101
            DTEND;VALUE=DATE:20260102
            RRULE:FREQ=DAILY
            SUMMARY:Original title
            END:VEVENT
            BEGIN:VEVENT
            UID:series@example.com
            RECURRENCE-ID;VALUE=DATE:20260103
            DTSTART;VALUE=DATE:20260103
            DTEND;VALUE=DATE:20260104
            SUMMARY:Overridden title
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = day("2026-01-03")
        val events = parser.parseOccurrences(ics, start, end)

        assertEquals(1, events.size)
        assertEquals("Overridden title", events[0].summary)
    }

    @Test
    fun `multi-day range returns every occurrence in it`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:daily@example.com
            DTSTART;VALUE=DATE:20260101
            DTEND;VALUE=DATE:20260102
            RRULE:FREQ=DAILY
            SUMMARY:Daily standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val start = LocalDate.parse("2026-03-01").atStartOfDay(ZoneOffset.UTC).toInstant()
        val end = LocalDate.parse("2026-03-05").atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC)

        val dates = parser.parseOccurrences(ics, start, end).mapNotNull { it.startDate }.sorted()
        assertEquals(
            listOf("2026-03-01", "2026-03-02", "2026-03-03", "2026-03-04", "2026-03-05"),
            dates
        )
    }

    @Test
    fun `COUNT-limited series stops producing occurrences past its end`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:limited@example.com
            DTSTART;VALUE=DATE:20260101
            DTEND;VALUE=DATE:20260102
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Three days only
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (sIn, eIn) = day("2026-01-03")
        assertEquals(1, parser.parseOccurrences(ics, sIn, eIn).size, "3rd occurrence exists")

        val (sOut, eOut) = day("2026-01-04")
        assertTrue(parser.parseOccurrences(ics, sOut, eOut).isEmpty(), "4th must not exist")
    }

    // ═══════════════════════════════════════════════════════════════════
    // PASS-THROUGH / EDGE CASES
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `non-recurring events pass through unchanged`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:single@example.com
            DTSTART;VALUE=DATE:20260725
            DTEND;VALUE=DATE:20260727
            SUMMARY:Two-day all-day event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = day("2026-07-25")
        val events = parser.parseOccurrences(ics, start, end)

        assertEquals(1, events.size)
        assertEquals("2026-07-25", events[0].startDate)
        assertEquals("2026-07-26", events[0].endDate, "DTEND is exclusive; inclusive end is 7/26")
        assertNull(events[0].rrule)
    }

    @Test
    fun `blank content yields no events`() {
        val (start, end) = day("2026-01-01")
        assertTrue(parser.parseOccurrences("", start, end).isEmpty())
        assertTrue(parser.parseOccurrences("   ", start, end).isEmpty())
    }

    @Test
    fun `unparseable content yields no events rather than throwing`() {
        val (start, end) = day("2026-01-01")
        assertTrue(parser.parseOccurrences("not an ics at all", start, end).isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // OCCURRENCE IDENTITY (recurrenceId + retained rrule)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `a timed occurrence carries its own RECURRENCE-ID and retains the series rrule`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:weekly-timed@example.com
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly sync
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = day("2026-02-02") // a later Monday
        val e = parser.parseOccurrences(ics, start, end).single()

        assertEquals("20260202T090000Z", e.recurrenceId, "recurrence identifier is the occurrence instant in iCal form")
        assertNotNull(e.rrule, "an occurrence of a series retains the rrule so it is distinguishable from standalone")
        assertTrue(e.rrule!!.contains("FREQ=WEEKLY"), e.rrule!!)
    }

    @Test
    fun `an all-day occurrence carries a DATE-form RECURRENCE-ID`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:yearly-allday@example.com
            DTSTART;VALUE=DATE:20230517
            DTEND;VALUE=DATE:20230518
            RRULE:FREQ=YEARLY
            SUMMARY:Recurring yearly event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = day("2026-05-17")
        val e = parser.parseOccurrences(ics, start, end).single()

        assertEquals("20260517", e.recurrenceId, "all-day occurrence uses the DATE value form")
        assertNotNull(e.rrule)
    }

    @Test
    fun `a standalone non-recurring event carries no recurrenceId`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:single@example.com
            DTSTART;VALUE=DATE:20260725
            DTEND;VALUE=DATE:20260727
            SUMMARY:Two-day all-day event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = day("2026-07-25")
        val e = parser.parseOccurrences(ics, start, end).single()

        assertNull(e.recurrenceId, "a standalone event is not a series instance")
        assertNull(e.rrule)
    }

    @Test
    fun `two occurrences of the same series carry distinct recurrenceIds`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:daily@example.com
            DTSTART;VALUE=DATE:20260101
            DTEND;VALUE=DATE:20260102
            RRULE:FREQ=DAILY
            SUMMARY:Daily standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val start = LocalDate.parse("2026-03-01").atStartOfDay(ZoneOffset.UTC).toInstant()
        val end = LocalDate.parse("2026-03-03").atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC)

        val recids = parser.parseOccurrences(ics, start, end).map { it.recurrenceId!! }
        assertEquals(listOf("20260301", "20260302", "20260303"), recids.sorted())
        assertEquals(recids.size, recids.toSet().size, "recurrenceIds must be distinct per occurrence")
    }

    @Test
    fun `an edited occurrence carries the original RECURRENCE-ID, not its moved start`() {
        // The override moves 2026-01-07 from 09:00 to 15:00; its identity stays the
        // original 09:00 instant (RFC 5545 §3.8.4.4), while its start reflects 15:00.
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:series@example.com
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily
            END:VEVENT
            BEGIN:VEVENT
            UID:series@example.com
            RECURRENCE-ID:20260107T090000Z
            DTSTART:20260107T150000Z
            DTEND:20260107T160000Z
            SUMMARY:Moved to the afternoon
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = day("2026-01-07")
        val e = parser.parseOccurrences(ics, start, end).single()

        assertEquals("Moved to the afternoon", e.summary)
        assertEquals("20260107T090000Z", e.recurrenceId, "identity stays the original instant")
        assertTrue(e.startTime!!.startsWith("2026-01-07T15:00"), "start reflects the moved time: ${e.startTime}")
    }

    @Test
    fun `an occurrence moved to a different day appears once on the new day and leaves no phantom on the original`() {
        // The 2026-01-07 09:00 instance is moved to 2026-01-08 15:00. Its RECURRENCE-ID
        // stays the original 09:00 instant (RFC 5545 §3.8.4.4), so the override merges
        // over the master's 01-07 slot: the original day is vacated (no phantom) and the
        // instance surfaces on 01-08, alongside that day's own natural 09:00 occurrence.
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:series@example.com
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily
            END:VEVENT
            BEGIN:VEVENT
            UID:series@example.com
            RECURRENCE-ID:20260107T090000Z
            DTSTART:20260108T150000Z
            DTEND:20260108T160000Z
            SUMMARY:Moved to the next day
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val start = LocalDate.parse("2026-01-06").atStartOfDay(ZoneOffset.UTC).toInstant()
        val end = LocalDate.parse("2026-01-09").atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC)
        val events = parser.parseOccurrences(ics, start, end)

        val starts = events.mapNotNull { it.startTime }
        // Jan 6 (natural), Jan 8 natural 09:00, Jan 8 moved-in 15:00, Jan 9 (natural).
        assertEquals(4, events.size, "01-07 is vacated; 01-08 carries two: $starts")
        assertTrue(starts.none { it.startsWith("2026-01-07") }, "no phantom on the original day: $starts")
        assertEquals(2, starts.count { it.startsWith("2026-01-08") }, "01-08 has its natural and the moved-in instance: $starts")

        val moved = events.single { it.recurrenceId == "20260107T090000Z" }
        assertEquals("Moved to the next day", moved.summary)
        assertTrue(moved.startTime!!.startsWith("2026-01-08T15:00"), "moved instance lands on 01-08 15:00: ${moved.startTime}")
    }

    @Test
    fun `orphaned override without its master is still mapped`() {
        // A response holding only a modified instance has no series to expand,
        // so it should surface rather than vanish.
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:orphan@example.com
            RECURRENCE-ID;VALUE=DATE:20260103
            DTSTART;VALUE=DATE:20260103
            DTEND;VALUE=DATE:20260104
            SUMMARY:Orphaned override
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = day("2026-01-03")
        val events = parser.parseOccurrences(ics, start, end)

        assertEquals(1, events.size)
        assertEquals("Orphaned override", events[0].summary)
    }
}
