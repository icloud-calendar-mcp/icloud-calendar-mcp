package org.onekash.mcp.calendar.ics

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for recurring-event occurrence expansion in the read path.
 *
 * Covers the windowed parse overload:
 * - RRULE expansion into concrete in-window occurrences
 * - EXDATE exclusions
 * - RECURRENCE-ID override merging (modified and cancelled instances)
 * - Non-recurring passthrough
 * - RECURRENCE-ID surfacing on ParsedEvent
 */
class IcsParserExpansionTest {

    private val parser = IcsParser()

    private fun window(start: String, end: String): Pair<Instant, Instant> =
        Instant.parse(start) to Instant.parse(end)

    // ═══════════════════════════════════════════════════════════════════
    // RRULE EXPANSION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `yearly master expands to exactly one occurrence in a one-year window`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:birthday@test
            DTSTART:20250704T120000Z
            DTEND:20250704T130000Z
            SUMMARY:Birthday
            RRULE:FREQ=YEARLY
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = window("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        val events = parser.parse(ics, start, end)

        assertEquals(1, events.size)
        val occurrence = events[0]
        assertEquals("birthday@test", occurrence.uid)
        assertEquals("2026-07-04T12:00:00Z", occurrence.startTime)
        assertEquals("2026-07-04T13:00:00Z", occurrence.endTime)
        assertEquals("2026-07-04T12:00:00Z", occurrence.recurrenceId)
        val rrule = assertNotNull(occurrence.rrule)
        assertTrue(rrule.contains("FREQ=YEARLY"))
    }

    @Test
    fun `weekly COUNT=6 with one EXDATE yields five occurrences`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:standup@test
            DTSTART:20260105T090000Z
            DTEND:20260105T091500Z
            SUMMARY:Standup
            RRULE:FREQ=WEEKLY;COUNT=6
            EXDATE:20260119T090000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = window("2026-01-01T00:00:00Z", "2026-02-28T23:59:59Z")
        val events = parser.parse(ics, start, end)

        assertEquals(5, events.size)
        val starts = events.map { it.startTime }
        assertEquals(
            listOf(
                "2026-01-05T09:00:00Z",
                "2026-01-12T09:00:00Z",
                "2026-01-26T09:00:00Z",
                "2026-02-02T09:00:00Z",
                "2026-02-09T09:00:00Z"
            ),
            starts
        )
        // Every occurrence retains the master's rrule for context
        assertTrue(events.all { it.rrule!!.contains("FREQ=WEEKLY") })
    }

    @Test
    fun `occurrences outside the window are not emitted`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:standup@test
            DTSTART:20260105T090000Z
            DTEND:20260105T091500Z
            SUMMARY:Standup
            RRULE:FREQ=WEEKLY;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = window("2026-01-10T00:00:00Z", "2026-01-20T23:59:59Z")
        val events = parser.parse(ics, start, end)

        assertEquals(2, events.size)
        assertEquals("2026-01-12T09:00:00Z", events[0].startTime)
        assertEquals("2026-01-19T09:00:00Z", events[1].startTime)
    }

    // ═══════════════════════════════════════════════════════════════════
    // RECURRENCE-ID OVERRIDES
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `RECURRENCE-ID override replaces its occurrence`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:standup@test
            DTSTART:20260105T090000Z
            DTEND:20260105T091500Z
            SUMMARY:Standup
            RRULE:FREQ=WEEKLY;COUNT=3
            END:VEVENT
            BEGIN:VEVENT
            UID:standup@test
            RECURRENCE-ID:20260112T090000Z
            DTSTART:20260112T140000Z
            DTEND:20260112T141500Z
            SUMMARY:Standup (moved)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = window("2026-01-01T00:00:00Z", "2026-01-31T23:59:59Z")
        val events = parser.parse(ics, start, end)

        assertEquals(3, events.size)
        val overridden = events.single { it.summary == "Standup (moved)" }
        assertEquals("2026-01-12T14:00:00Z", overridden.startTime)
        assertEquals("2026-01-12T14:15:00Z", overridden.endTime)
        assertEquals("2026-01-12T09:00:00Z", overridden.recurrenceId)
        // The unmodified occurrences keep the master's summary and times
        val regular = events.filter { it.summary == "Standup" }
        assertEquals(listOf("2026-01-05T09:00:00Z", "2026-01-19T09:00:00Z"), regular.map { it.startTime })
    }

    @Test
    fun `CANCELLED override removes its occurrence`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:standup@test
            DTSTART:20260105T090000Z
            DTEND:20260105T091500Z
            SUMMARY:Standup
            RRULE:FREQ=WEEKLY;COUNT=3
            END:VEVENT
            BEGIN:VEVENT
            UID:standup@test
            RECURRENCE-ID:20260112T090000Z
            DTSTART:20260112T090000Z
            DTEND:20260112T091500Z
            SUMMARY:Standup
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = window("2026-01-01T00:00:00Z", "2026-01-31T23:59:59Z")
        val events = parser.parse(ics, start, end)

        assertEquals(2, events.size)
        assertEquals(listOf("2026-01-05T09:00:00Z", "2026-01-19T09:00:00Z"), events.map { it.startTime })
    }

    // ═══════════════════════════════════════════════════════════════════
    // NON-RECURRING PASSTHROUGH
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `non-recurring event passes through unchanged`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:oneoff@test
            DTSTART:20260115T090000Z
            DTEND:20260115T100000Z
            SUMMARY:One-off Meeting
            LOCATION:Room A
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val (start, end) = window("2026-01-01T00:00:00Z", "2026-01-31T23:59:59Z")
        val windowed = parser.parse(ics, start, end)
        val plain = parser.parse(ics)

        assertEquals(plain, windowed)
        assertEquals(1, windowed.size)
        assertEquals("2026-01-15T09:00:00Z", windowed[0].startTime)
        assertNull(windowed[0].recurrenceId)
        assertNull(windowed[0].rrule)
    }

    // ═══════════════════════════════════════════════════════════════════
    // RECURRENCE-ID PARSING (non-windowed path)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `plain parse surfaces RECURRENCE-ID on modified instances`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:standup@test
            RECURRENCE-ID:20260112T090000Z
            DTSTART:20260112T140000Z
            DTEND:20260112T141500Z
            SUMMARY:Standup (moved)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)

        assertEquals(1, events.size)
        assertEquals("2026-01-12T09:00:00Z", events[0].recurrenceId)
    }
}
