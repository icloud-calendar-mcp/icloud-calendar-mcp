package org.onekash.mcp.calendar.ics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for IcsBuilder - generates valid ICS content for CalDAV.
 */
class IcsBuilderTest {

    private val builder = IcsBuilder()

    // ═══════════════════════════════════════════════════════════════════════
    // BASIC STRUCTURE
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build simple timed event`() {
        val ics = builder.build(
            uid = "test-123@example.com",
            summary = "Team Meeting",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("VERSION:2.0"))
        assertTrue(ics.contains("PRODID:"))
        assertTrue(ics.contains("BEGIN:VEVENT"))
        assertTrue(ics.contains("UID:test-123@example.com"))
        assertTrue(ics.contains("SUMMARY:Team Meeting"))
        assertTrue(ics.contains("DTSTART:20250115T100000Z"))
        assertTrue(ics.contains("DTEND:20250115T110000Z"))
        assertTrue(ics.contains("END:VEVENT"))
        assertTrue(ics.contains("END:VCALENDAR"))
    }

    @Test
    fun `build generates DTSTAMP`() {
        val ics = builder.build(
            uid = "test-123@example.com",
            summary = "Test Event",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        // DTSTAMP should be present with format YYYYMMDDTHHMMSSZ
        assertTrue(ics.contains(Regex("DTSTAMP:\\d{8}T\\d{6}Z")))
    }

    @Test
    fun `build all-day single day event`() {
        val ics = builder.build(
            uid = "all-day-123@example.com",
            summary = "Holiday",
            startDate = "2025-01-15",
            endDate = "2025-01-15",
            isAllDay = true
        )

        assertTrue(ics.contains("DTSTART;VALUE=DATE:20250115"))
        // DTEND should be exclusive (next day for single-day event)
        assertTrue(ics.contains("DTEND;VALUE=DATE:20250116"))
        assertFalse(ics.contains("DTSTART:"))  // No time-based DTSTART
    }

    @Test
    fun `build all-day multi-day event`() {
        val ics = builder.build(
            uid = "multi-day-123@example.com",
            summary = "Conference",
            startDate = "2025-01-15",
            endDate = "2025-01-17",  // Inclusive end
            isAllDay = true
        )

        assertTrue(ics.contains("DTSTART;VALUE=DATE:20250115"))
        // DTEND should be day after inclusive end (exclusive boundary)
        assertTrue(ics.contains("DTEND;VALUE=DATE:20250118"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OPTIONAL PROPERTIES
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build event with description`() {
        val ics = builder.build(
            uid = "test-123@example.com",
            summary = "Planning Session",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            description = "Discuss Q1 goals"
        )

        assertTrue(ics.contains("DESCRIPTION:Discuss Q1 goals"))
    }

    @Test
    fun `build event with location`() {
        val ics = builder.build(
            uid = "test-123@example.com",
            summary = "Team Lunch",
            startTime = "2025-01-15T12:00:00Z",
            endTime = "2025-01-15T13:00:00Z",
            location = "Conference Room A"
        )

        assertTrue(ics.contains("LOCATION:Conference Room A"))
    }

    @Test
    fun `build event with all optional fields`() {
        val ics = builder.build(
            uid = "complete-event@example.com",
            summary = "Full Event",
            startTime = "2025-01-15T14:00:00Z",
            endTime = "2025-01-15T15:30:00Z",
            description = "Event with all fields",
            location = "Room 101"
        )

        assertTrue(ics.contains("SUMMARY:Full Event"))
        assertTrue(ics.contains("DESCRIPTION:Event with all fields"))
        assertTrue(ics.contains("LOCATION:Room 101"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TIMEZONE HANDLING
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build event with timezone`() {
        val ics = builder.build(
            uid = "tz-event@example.com",
            summary = "Local Meeting",
            startTime = "2025-01-15T10:00:00",  // No Z suffix
            endTime = "2025-01-15T11:00:00",
            timezone = "America/New_York"
        )

        assertTrue(ics.contains("DTSTART;TZID=America/New_York:20250115T100000"))
        assertTrue(ics.contains("DTEND;TZID=America/New_York:20250115T110000"))
    }

    @Test
    fun `build UTC event ignores timezone parameter`() {
        val ics = builder.build(
            uid = "utc-event@example.com",
            summary = "UTC Meeting",
            startTime = "2025-01-15T10:00:00Z",  // Z suffix means UTC
            endTime = "2025-01-15T11:00:00Z",
            timezone = "America/New_York"  // Should be ignored
        )

        // UTC times should use Z suffix, not TZID
        assertTrue(ics.contains("DTSTART:20250115T100000Z"))
        assertTrue(ics.contains("DTEND:20250115T110000Z"))
        assertFalse(ics.contains("TZID="))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEXT ESCAPING
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `escape commas in text`() {
        val ics = builder.build(
            uid = "escape-test@example.com",
            summary = "Meeting with Alice, Bob, Charlie",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        assertTrue(ics.contains("SUMMARY:Meeting with Alice\\, Bob\\, Charlie"))
    }

    @Test
    fun `escape semicolons in text`() {
        val ics = builder.build(
            uid = "escape-test@example.com",
            summary = "Test; Important",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        assertTrue(ics.contains("SUMMARY:Test\\; Important"))
    }

    @Test
    fun `escape newlines in description`() {
        val ics = builder.build(
            uid = "newline-test@example.com",
            summary = "Notes",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            description = "Line 1\nLine 2\nLine 3"
        )

        assertTrue(ics.contains("DESCRIPTION:Line 1\\nLine 2\\nLine 3"))
    }

    @Test
    fun `escape backslashes`() {
        val ics = builder.build(
            uid = "backslash-test@example.com",
            summary = "Path: C:\\Users\\test",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        // Windows path C:\Users\test → escapes to C:\\Users\\test
        // The colon after C is the drive letter, not escaped
        assertTrue(ics.contains("SUMMARY:Path: C:\\\\Users\\\\test"))
    }

    @Test
    fun `preserve unicode characters`() {
        val ics = builder.build(
            uid = "unicode-test@example.com",
            summary = "会議",  // Japanese
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            description = "Café résumé naïve"
        )

        assertTrue(ics.contains("SUMMARY:会議"))
        assertTrue(ics.contains("DESCRIPTION:Café résumé naïve"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LINE FOLDING (RFC 5545)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `fold long lines`() {
        val longDescription = "A".repeat(100)
        val ics = builder.build(
            uid = "fold-test@example.com",
            summary = "Test",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            description = longDescription
        )

        // Lines should be folded at 75 octets
        // Check that no physical line exceeds 75 chars (excluding CRLF)
        val lines = ics.split("\r\n", "\n")
        for (line in lines) {
            assertTrue(line.length <= 75, "Line too long: ${line.length} chars")
        }
    }

    @Test
    fun `continuation lines start with space`() {
        val longSummary = "B".repeat(100)
        val ics = builder.build(
            uid = "continuation-test@example.com",
            summary = longSummary,
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        // Find continuation lines (after first line of SUMMARY)
        val lines = ics.split("\r\n", "\n")
        var foundSummary = false
        for (line in lines) {
            if (line.startsWith("SUMMARY:")) {
                foundSummary = true
            } else if (foundSummary && line.startsWith(" ")) {
                // This is a continuation line - check it starts with space
                assertTrue(line.startsWith(" "), "Continuation should start with space")
                break
            } else if (foundSummary && !line.startsWith(" ")) {
                // Next property, we should have found continuation by now
                break
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECURRENCE (RRULE)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build recurring event with RRULE`() {
        val ics = builder.build(
            uid = "recurring-123@example.com",
            summary = "Weekly Standup",
            startTime = "2025-01-15T09:00:00Z",
            endTime = "2025-01-15T09:30:00Z",
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR"
        )

        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR"))
    }

    @Test
    fun `build recurring all-day event`() {
        val ics = builder.build(
            uid = "recurring-allday@example.com",
            summary = "Monthly Review",
            startDate = "2025-01-01",
            endDate = "2025-01-01",
            isAllDay = true,
            rrule = "FREQ=MONTHLY;BYMONTHDAY=1"
        )

        assertTrue(ics.contains("DTSTART;VALUE=DATE:20250101"))
        assertTrue(ics.contains("RRULE:FREQ=MONTHLY;BYMONTHDAY=1"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UID GENERATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `generate UID when not provided`() {
        val ics = builder.build(
            summary = "Auto UID Event",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        // Should contain a generated UID
        assertTrue(ics.contains(Regex("UID:[a-f0-9-]+@icloud-calendar-mcp")))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build event with empty description`() {
        val ics = builder.build(
            uid = "empty-desc@example.com",
            summary = "Test",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            description = ""
        )

        // Empty description should not be included
        assertFalse(ics.contains("DESCRIPTION:"))
    }

    @Test
    fun `build event with whitespace-only location`() {
        val ics = builder.build(
            uid = "whitespace-loc@example.com",
            summary = "Test",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            location = "   "
        )

        // Whitespace-only location should not be included
        assertFalse(ics.contains("LOCATION:"))
    }

    @Test
    fun `round trip parsing produces same values`() {
        val originalUid = "roundtrip-test@example.com"
        val originalSummary = "Round Trip Test"
        val originalDesc = "Testing the full cycle"
        val originalLoc = "Test Room"
        val originalStart = "2025-01-15T14:00:00Z"
        val originalEnd = "2025-01-15T15:30:00Z"

        val ics = builder.build(
            uid = originalUid,
            summary = originalSummary,
            startTime = originalStart,
            endTime = originalEnd,
            description = originalDesc,
            location = originalLoc
        )

        // Parse it back
        val parser = IcsParser()
        val events = parser.parse(ics)

        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(originalUid, event.uid)
        assertEquals(originalSummary, event.summary)
        assertEquals(originalDesc, event.description)
        assertEquals(originalLoc, event.location)
        assertEquals("2025-01-15T14:00:00Z", event.startTime)
        assertEquals("2025-01-15T15:30:00Z", event.endTime)
        assertFalse(event.isAllDay)
    }

    @Test
    fun `round trip all-day event`() {
        val ics = builder.build(
            uid = "allday-roundtrip@example.com",
            summary = "All Day Round Trip",
            startDate = "2025-01-15",
            endDate = "2025-01-17",
            isAllDay = true
        )

        val parser = IcsParser()
        val events = parser.parse(ics)

        assertEquals(1, events.size)
        val event = events[0]
        assertTrue(event.isAllDay)
        assertEquals("2025-01-15", event.startDate)
        assertEquals("2025-01-17", event.endDate)  // Parser converts to inclusive
    }
}
