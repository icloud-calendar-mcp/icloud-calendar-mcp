package org.onekash.mcp.calendar.ics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Tests for ICS parsing with ical4j.
 *
 * Covers:
 * - All-day event handling (UTC date strings, not epoch)
 * - Timezone conversion
 * - Line unfolding
 * - Text unescaping
 * - Recurring event RRULE
 */
class IcsParserTest {

    private val parser = IcsParser()

    // ═══════════════════════════════════════════════════════════════════
    // BASIC EVENT PARSING
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parse simple event with all fields`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test-event-123@example.com
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Team Meeting
            DESCRIPTION:Weekly sync meeting
            LOCATION:Conference Room A
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)

        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("test-event-123@example.com", event.uid)
        assertEquals("Team Meeting", event.summary)
        assertEquals("Weekly sync meeting", event.description)
        assertEquals("Conference Room A", event.location)
        assertFalse(event.isAllDay)
        // UTC times
        assertEquals("2025-01-15T09:00:00Z", event.startTime)
        assertEquals("2025-01-15T10:00:00Z", event.endTime)
    }

    @Test
    fun `parse event with missing optional fields`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:minimal@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Minimal Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)

        assertEquals(1, events.size)
        assertNull(events[0].description)
        assertNull(events[0].location)
    }

    @Test
    fun `parse multiple events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event1@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Event 1
            END:VEVENT
            BEGIN:VEVENT
            UID:event2@test
            DTSTART:20250115T100000Z
            DTEND:20250115T110000Z
            SUMMARY:Event 2
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)

        assertEquals(2, events.size)
        assertEquals("Event 1", events[0].summary)
        assertEquals("Event 2", events[1].summary)
    }

    @Test
    fun `parse returns empty list for invalid ICS`() {
        val events = parser.parse("not valid ics")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `parse returns empty list for empty string`() {
        val events = parser.parse("")
        assertTrue(events.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // ALL-DAY EVENT HANDLING (returns UTC date strings, not epoch)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parse all-day event single day`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:allday@test
            DTSTART;VALUE=DATE:20250115
            DTEND;VALUE=DATE:20250116
            SUMMARY:All Day Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)

        assertEquals(1, events.size)
        val event = events[0]
        assertTrue(event.isAllDay)
        // Dates as strings, not epoch (prevents timezone shift)
        assertEquals("2025-01-15", event.startDate)
        assertEquals("2025-01-15", event.endDate)  // RFC 5545: DTEND is exclusive, so subtract 1 day
    }

    @Test
    fun `parse all-day multi-day event`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:multiday@test
            DTSTART;VALUE=DATE:20250115
            DTEND;VALUE=DATE:20250118
            SUMMARY:Three Day Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)

        assertEquals(1, events.size)
        val event = events[0]
        assertTrue(event.isAllDay)
        assertEquals("2025-01-15", event.startDate)
        assertEquals("2025-01-17", event.endDate)  // 15, 16, 17 = 3 days
    }

    @Test
    fun `all-day event year boundary Dec 31 to Jan 1`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:yearend@test
            DTSTART;VALUE=DATE:20241231
            DTEND;VALUE=DATE:20250102
            SUMMARY:New Year
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)

        assertEquals(1, events.size)
        assertEquals("2024-12-31", events[0].startDate)
        assertEquals("2025-01-01", events[0].endDate)  // 2 days
    }

    // ═══════════════════════════════════════════════════════════════════
    // TIMEZONE HANDLING
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parse UTC datetime`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:utc@test
            DTSTART:20250115T140000Z
            DTEND:20250115T150000Z
            SUMMARY:UTC Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertEquals("2025-01-15T14:00:00Z", events[0].startTime)
    }

    @Test
    fun `parse event with TZID converts to UTC`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:tz@test
            DTSTART;TZID=America/New_York:20250115T090000
            DTEND;TZID=America/New_York:20250115T100000
            SUMMARY:NYC Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        // 9 AM EST = 14:00 UTC (EST is UTC-5)
        assertEquals("2025-01-15T14:00:00Z", events[0].startTime)
    }

    @Test
    fun `parse event with Pacific timezone`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:pacific@test
            DTSTART;TZID=America/Los_Angeles:20250115T090000
            DTEND;TZID=America/Los_Angeles:20250115T100000
            SUMMARY:LA Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        // 9 AM PST = 17:00 UTC (PST is UTC-8)
        assertEquals("2025-01-15T17:00:00Z", events[0].startTime)
    }

    // ═══════════════════════════════════════════════════════════════════
    // LINE UNFOLDING (RFC 5545)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parse event with folded lines`() {
        // RFC 5545: Lines longer than 75 chars folded with CRLF + space
        // Note: ical4j automatically unfolds lines during parsing
        val ics = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:folded@test\r\n" +
            "DTSTART:20250115T090000Z\r\n" +
            "DTEND:20250115T100000Z\r\n" +
            "SUMMARY:This is a very long event title that spans multiple lines becau\r\n" +
            " se it exceeds 75 characters\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR"

        val events = parser.parse(ics)
        assertEquals(1, events.size)
        assertEquals(
            "This is a very long event title that spans multiple lines because it exceeds 75 characters",
            events[0].summary
        )
    }

    @Test
    fun `parse description with folded lines`() {
        val ics = "BEGIN:VCALENDAR\r\n" +
            "VERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:desc@test\r\n" +
            "DTSTART:20250115T090000Z\r\n" +
            "DTEND:20250115T100000Z\r\n" +
            "SUMMARY:Meeting\r\n" +
            "DESCRIPTION:This is a long description that needs to be folded across m\r\n" +
            " ultiple lines according to RFC 5545 specification\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR"

        val events = parser.parse(ics)
        assertEquals(1, events.size)
        assertTrue(events[0].description?.contains("multiple lines") == true)
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEXT UNESCAPING
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parse unescapes newlines`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:escape@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Event
            DESCRIPTION:Line 1\nLine 2\nLine 3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertTrue(events[0].description?.contains("\n") == true)
    }

    @Test
    fun `parse unescapes commas and semicolons`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:comma@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Meeting with A\, B\, and C
            LOCATION:Room 1\; Building A
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertEquals("Meeting with A, B, and C", events[0].summary)
        assertEquals("Room 1; Building A", events[0].location)
    }

    @Test
    fun `parse unescapes backslashes`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:backslash@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Path: C:\\Users\\Name
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertEquals("Path: C:\\Users\\Name", events[0].summary)
    }

    // ═══════════════════════════════════════════════════════════════════
    // RECURRING EVENTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parse recurring event with RRULE`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:recurring@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;BYDAY=WE;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)

        assertEquals(1, events.size)
        assertNotNull(events[0].rrule)
        // Order of RRULE parts may vary, check for key components
        val rrule = events[0].rrule!!
        assertTrue(rrule.contains("FREQ=WEEKLY"))
        assertTrue(rrule.contains("BYDAY=WE"))
        assertTrue(rrule.contains("COUNT=10"))
    }

    @Test
    fun `parse recurring daily event`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:daily@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Daily Standup
            RRULE:FREQ=DAILY;INTERVAL=1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertTrue(events[0].rrule?.contains("FREQ=DAILY") == true)
    }

    @Test
    fun `parse recurring monthly event`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:monthly@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Monthly Review
            RRULE:FREQ=MONTHLY;BYMONTHDAY=15
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertTrue(events[0].rrule?.contains("FREQ=MONTHLY") == true)
    }

    // ═══════════════════════════════════════════════════════════════════
    // SPECIAL CHARACTERS (International)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parse event with Japanese characters`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:japanese@test
            DTSTART;VALUE=DATE:20250101
            DTEND;VALUE=DATE:20250102
            SUMMARY:元日
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertEquals("元日", events[0].summary)
    }

    @Test
    fun `parse event with German umlauts`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:german@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Bürobesprechung
            LOCATION:Düsseldorf
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertEquals("Bürobesprechung", events[0].summary)
        assertEquals("Düsseldorf", events[0].location)
    }

    // ═══════════════════════════════════════════════════════════════════
    // DURATION HANDLING
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parse event with DURATION instead of DTEND`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:duration@test
            DTSTART:20250115T090000Z
            DURATION:PT2H30M
            SUMMARY:Long Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        // 9:00 + 2h30m = 11:30
        assertEquals("2025-01-15T11:30:00Z", events[0].endTime)
    }

    @Test
    fun `parse event without DTEND or DURATION defaults to 1 hour`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:noend@test
            DTSTART:20250115T090000Z
            SUMMARY:Quick Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        // Default: DTSTART + 1 hour
        assertEquals("2025-01-15T10:00:00Z", events[0].endTime)
    }

    // ═══════════════════════════════════════════════════════════════════
    // SKIPPED EVENTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `skip cancelled events`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:cancelled@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Cancelled Meeting
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `skip events without summary`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:nosummary@test
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertTrue(events.isEmpty())
    }
}
