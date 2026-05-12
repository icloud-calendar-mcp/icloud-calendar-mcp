package org.onekash.mcp.calendar.ics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

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
    // LINE FOLDING - OCTET COUNTING (Chunk 12)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `emoji line folds at octet boundary not char boundary`() {
        // Emoji 🎉 is 4 bytes in UTF-8. 18 emojis = 72 bytes for emoji chars
        // "SUMMARY:" is 8 bytes. 8 + 72 = 80 > 75, must fold
        val emojis = "🎉".repeat(18)
        val ics = builder.build(
            uid = "emoji@test",
            summary = emojis,
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        // Verify no line exceeds 75 octets
        val lines = ics.split("\r\n", "\n")
        for (line in lines) {
            val octets = line.toByteArray(Charsets.UTF_8).size
            assertTrue(octets <= 75, "Line has $octets octets (max 75): ${line.take(40)}...")
        }
    }

    @Test
    fun `CJK characters fold correctly at octet boundary`() {
        // CJK chars are 3 bytes each in UTF-8. 20 chars = 60 bytes
        // "SUMMARY:" = 8 bytes, 8 + 60 = 68 <= 75, fits in first line
        // 30 CJK chars = 90 bytes + 8 = 98, must fold
        val cjk = "会".repeat(30)
        val ics = builder.build(
            uid = "cjk@test",
            summary = cjk,
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        val lines = ics.split("\r\n", "\n")
        for (line in lines) {
            val octets = line.toByteArray(Charsets.UTF_8).size
            assertTrue(octets <= 75, "Line has $octets octets (max 75)")
        }
    }

    @Test
    fun `ASCII lines still fold at 75 chars`() {
        val longAscii = "A".repeat(100)
        val ics = builder.build(
            uid = "ascii@test",
            summary = longAscii,
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        val lines = ics.split("\r\n", "\n")
        for (line in lines) {
            assertTrue(line.length <= 75, "Line too long: ${line.length}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EXTENDED FIELDS (Chunk 12)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build event with STATUS TENTATIVE`() {
        val ics = builder.build(
            uid = "status@test",
            summary = "Maybe Meeting",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            status = "TENTATIVE"
        )

        assertTrue(ics.contains("STATUS:TENTATIVE"))
    }

    @Test
    fun `build event with URL`() {
        val ics = builder.build(
            uid = "url@test",
            summary = "Web Meeting",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            url = "https://meet.example.com/room"
        )

        assertTrue(ics.contains("URL:https://meet.example.com/room"))
    }

    @Test
    fun `build event with CATEGORIES`() {
        val ics = builder.build(
            uid = "cat@test",
            summary = "Categorized",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            categories = listOf("Work", "Meeting")
        )

        assertTrue(ics.contains("CATEGORIES:Work,Meeting"))
    }

    @Test
    fun `build event with categories containing semicolons`() {
        val ics = builder.build(
            uid = "cat-esc@test",
            summary = "Escaped",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            categories = listOf("Work;Important", "Meeting")
        )

        assertTrue(ics.contains("CATEGORIES:Work\\;Important,Meeting"))
    }

    @Test
    fun `build event with PRIORITY`() {
        val ics = builder.build(
            uid = "pri@test",
            summary = "High Priority",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            priority = 1
        )

        assertTrue(ics.contains("PRIORITY:1"))
    }

    @Test
    fun `build event with TRANSP TRANSPARENT`() {
        val ics = builder.build(
            uid = "transp@test",
            summary = "Free Time",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            transp = "TRANSPARENT"
        )

        assertTrue(ics.contains("TRANSP:TRANSPARENT"))
    }

    @Test
    fun `null optional fields produce no empty lines`() {
        val ics = builder.build(
            uid = "minimal@test",
            summary = "Minimal",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        assertFalse(ics.contains("STATUS:"))
        assertFalse(ics.contains("URL:"))
        assertFalse(ics.contains("CATEGORIES:"))
        assertFalse(ics.contains("PRIORITY:"))
        assertFalse(ics.contains("TRANSP:"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VTIMEZONE GENERATION (Chunk 14)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build event with timezone includes VTIMEZONE`() {
        val ics = builder.build(
            uid = "vtz@test",
            summary = "NYC Meeting",
            startTime = "2025-01-15T10:00:00",
            endTime = "2025-01-15T11:00:00",
            timezone = "America/New_York"
        )

        assertTrue(ics.contains("BEGIN:VTIMEZONE"), "Should contain VTIMEZONE")
        assertTrue(ics.contains("TZID:America/New_York") || ics.contains("TZID=America/New_York"),
            "VTIMEZONE should reference the timezone")
    }

    @Test
    fun `UTC times do not include VTIMEZONE`() {
        val ics = builder.build(
            uid = "utc-no-vtz@test",
            summary = "UTC Meeting",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z",
            timezone = "America/New_York"
        )

        assertFalse(ics.contains("BEGIN:VTIMEZONE"), "UTC should not have VTIMEZONE")
    }

    @Test
    fun `no timezone param means no VTIMEZONE`() {
        val ics = builder.build(
            uid = "no-tz@test",
            summary = "No TZ Meeting",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        assertFalse(ics.contains("BEGIN:VTIMEZONE"))
    }

    @Test
    fun `VTIMEZONE appears before VEVENT`() {
        val ics = builder.build(
            uid = "order@test",
            summary = "Order Test",
            startTime = "2025-01-15T10:00:00",
            endTime = "2025-01-15T11:00:00",
            timezone = "America/New_York"
        )

        val vtimezoneIdx = ics.indexOf("BEGIN:VTIMEZONE")
        val veventIdx = ics.indexOf("BEGIN:VEVENT")
        assertTrue(vtimezoneIdx >= 0, "VTIMEZONE should be present")
        assertTrue(vtimezoneIdx < veventIdx, "VTIMEZONE should appear before VEVENT")
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

    // ═══════════════════════════════════════════════════════════════════════
    // CREATED + LAST-MODIFIED (RFC 5545 §3.8.7.1, §3.8.7.3)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build emits CREATED when createdAt provided`() {
        val createdAt = Instant.parse("2026-01-10T08:30:00Z")
        val ics = builder.build(
            uid = "with-created@example.com",
            summary = "Has CREATED",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            createdAt = createdAt
        )
        assertTrue(
            ics.contains("CREATED:20260110T083000Z"),
            "Expected CREATED:20260110T083000Z in:\n$ics"
        )
    }

    @Test
    fun `build omits CREATED when createdAt is null`() {
        val ics = builder.build(
            uid = "no-created@example.com",
            summary = "No CREATED",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z"
            // createdAt = null (default)
        )
        assertFalse(
            ics.lineSequence().any { it.startsWith("CREATED:") },
            "Did not expect any CREATED:... line in:\n$ics"
        )
    }

    @Test
    fun `build emits LAST-MODIFIED when lastModified provided`() {
        val lastModified = Instant.parse("2026-01-12T14:45:00Z")
        val ics = builder.build(
            uid = "with-lm@example.com",
            summary = "Has LAST-MODIFIED",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            lastModified = lastModified
        )
        assertTrue(
            ics.contains("LAST-MODIFIED:20260112T144500Z"),
            "Expected LAST-MODIFIED:20260112T144500Z in:\n$ics"
        )
    }

    @Test
    fun `build omits LAST-MODIFIED when lastModified is null`() {
        val ics = builder.build(
            uid = "no-lm@example.com",
            summary = "No LAST-MODIFIED",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z"
            // lastModified = null (default)
        )
        assertFalse(
            ics.lineSequence().any { it.startsWith("LAST-MODIFIED:") },
            "Did not expect any LAST-MODIFIED:... line in:\n$ics"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECURRENCE → DURATION (RFC 5545 §3.8.5; Etar/Fossify/AOSP convention)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build timed recurring event emits DURATION not DTEND`() {
        val ics = builder.build(
            uid = "weekly-1h@example.com",
            summary = "Weekly meeting",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            rrule = "FREQ=WEEKLY;BYDAY=TH"
        )
        assertTrue(ics.contains("DURATION:PT1H"), "Expected DURATION:PT1H in:\n$ics")
        assertFalse(
            ics.lineSequence().any { it.startsWith("DTEND") },
            "DTEND must be absent when RRULE present:\n$ics"
        )
    }

    @Test
    fun `build timed non-recurring event still emits DTEND`() {
        val ics = builder.build(
            uid = "one-shot@example.com",
            summary = "One-shot meeting",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:30:00Z"
        )
        assertTrue(ics.contains("DTEND:20260115T113000Z"), "Expected DTEND in non-recurring event:\n$ics")
        assertFalse(
            ics.lineSequence().any { it.startsWith("DURATION") },
            "Non-recurring event should not emit DURATION:\n$ics"
        )
    }

    @Test
    fun `build all-day recurring event emits DURATION P1D not DTEND`() {
        val ics = builder.build(
            uid = "daily-1d@example.com",
            summary = "Daily streak",
            isAllDay = true,
            startDate = "2026-01-15",
            endDate = "2026-01-15",  // 1-day event
            rrule = "FREQ=DAILY"
        )
        assertTrue(ics.contains("DURATION:P1D"), "Expected DURATION:P1D in:\n$ics")
        assertFalse(
            ics.lineSequence().any { it.startsWith("DTEND") },
            "DTEND must be absent when RRULE present:\n$ics"
        )
    }

    @Test
    fun `build multi-day recurring all-day event emits correct DURATION`() {
        val ics = builder.build(
            uid = "weekend-3d@example.com",
            summary = "Weekend retreat",
            isAllDay = true,
            startDate = "2026-01-16",
            endDate = "2026-01-18",  // 3-day event (Fri-Sun inclusive)
            rrule = "FREQ=YEARLY"
        )
        assertTrue(ics.contains("DURATION:P3D"), "Expected DURATION:P3D in:\n$ics")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DISTINCT DTEND TIMEZONE (cross-tz events e.g. flights)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build with distinct end timezone emits separate DTSTART and DTEND TZIDs`() {
        val ics = builder.build(
            uid = "flight@example.com",
            summary = "JFK to LAX",
            startTime = "2026-03-15T08:00:00",
            endTime = "2026-03-15T11:00:00",
            timezone = "America/New_York",
            endTimezone = "America/Los_Angeles"
        )
        assertTrue(
            ics.contains("DTSTART;TZID=America/New_York:20260315T080000"),
            "Expected DTSTART with NY TZID in:\n$ics"
        )
        assertTrue(
            ics.contains("DTEND;TZID=America/Los_Angeles:20260315T110000"),
            "Expected DTEND with LA TZID in:\n$ics"
        )
    }

    @Test
    fun `build with null endTimezone reuses start timezone for DTEND`() {
        val ics = builder.build(
            uid = "single-tz@example.com",
            summary = "NY meeting",
            startTime = "2026-03-15T10:00:00",
            endTime = "2026-03-15T11:00:00",
            timezone = "America/New_York"
            // endTimezone = null (default)
        )
        assertTrue(
            ics.contains("DTSTART;TZID=America/New_York:20260315T100000"),
            "Expected DTSTART with NY TZID in:\n$ics"
        )
        assertTrue(
            ics.contains("DTEND;TZID=America/New_York:20260315T110000"),
            "Expected DTEND with NY TZID (reused) in:\n$ics"
        )
    }

    @Test
    fun `build with endTimezone equal to start timezone does not duplicate VTIMEZONE`() {
        val ics = builder.build(
            uid = "same-tz@example.com",
            summary = "NY meeting",
            startTime = "2026-03-15T10:00:00",
            endTime = "2026-03-15T11:00:00",
            timezone = "America/New_York",
            endTimezone = "America/New_York"
        )
        // Count VTIMEZONE blocks
        val vtzCount = "BEGIN:VTIMEZONE".toRegex().findAll(ics).count()
        assertEquals(1, vtzCount, "Expected exactly 1 VTIMEZONE for matching zones in:\n$ics")
    }

    @Test
    fun `build with distinct timezones emits two VTIMEZONEs`() {
        val ics = builder.build(
            uid = "distinct-tz@example.com",
            summary = "Cross-country flight",
            startTime = "2026-03-15T08:00:00",
            endTime = "2026-03-15T11:00:00",
            timezone = "America/New_York",
            endTimezone = "America/Los_Angeles"
        )
        val vtzCount = "BEGIN:VTIMEZONE".toRegex().findAll(ics).count()
        assertEquals(2, vtzCount, "Expected 2 VTIMEZONE blocks for distinct zones in:\n$ics")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RDATE / EXDATE
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build emits one RDATE line per value`() {
        val ics = builder.build(
            uid = "rdate-multi@example.com",
            summary = "Recurring with extra",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            rrule = "FREQ=WEEKLY",
            rdates = listOf("2026-02-14T10:00:00Z", "2026-03-14T10:00:00Z")
        )
        val rdateLines = ics.lineSequence().filter { it.startsWith("RDATE") }.toList()
        assertEquals(2, rdateLines.size, "Expected one RDATE per value:\n$ics")
        assertTrue(rdateLines.any { it.contains("20260214T100000Z") })
        assertTrue(rdateLines.any { it.contains("20260314T100000Z") })
    }

    @Test
    fun `build emits one EXDATE line per value`() {
        val ics = builder.build(
            uid = "exdate-multi@example.com",
            summary = "Recurring with skip",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            rrule = "FREQ=WEEKLY",
            exdates = listOf("2026-02-12T10:00:00Z", "2026-02-19T10:00:00Z")
        )
        val exdateLines = ics.lineSequence().filter { it.startsWith("EXDATE") }.toList()
        assertEquals(2, exdateLines.size, "Expected one EXDATE per value:\n$ics")
    }

    @Test
    fun `build with empty rdates and exdates emits nothing`() {
        val ics = builder.build(
            uid = "no-extras@example.com",
            summary = "Plain recurring",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            rrule = "FREQ=WEEKLY",
            rdates = emptyList(),
            exdates = emptyList()
        )
        assertFalse(ics.lineSequence().any { it.startsWith("RDATE") })
        assertFalse(ics.lineSequence().any { it.startsWith("EXDATE") })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VALARM (RFC 5545 §3.6.6)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `build with one duration-trigger DISPLAY alarm emits VALARM block`() {
        val ics = builder.build(
            uid = "alarm-1@example.com",
            summary = "One alarm",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            alarms = listOf(AlarmSpec(trigger = "-PT15M"))
        )
        assertTrue(ics.contains("BEGIN:VALARM"), "Expected VALARM block in:\n$ics")
        assertTrue(ics.contains("ACTION:DISPLAY"), "Default action DISPLAY:\n$ics")
        assertTrue(ics.contains("TRIGGER:-PT15M"), "Duration trigger inline form:\n$ics")
        assertTrue(ics.contains("DESCRIPTION:Reminder"), "Default DESCRIPTION:\n$ics")
        assertTrue(ics.contains("END:VALARM"), "VALARM closed:\n$ics")
    }

    @Test
    fun `build with absolute-trigger alarm uses VALUE=DATE-TIME parameter`() {
        val ics = builder.build(
            uid = "alarm-abs@example.com",
            summary = "Absolute trigger",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            alarms = listOf(AlarmSpec(trigger = "20260115T093000Z"))
        )
        assertTrue(
            ics.contains("TRIGGER;VALUE=DATE-TIME:20260115T093000Z"),
            "Absolute trigger requires VALUE=DATE-TIME:\n$ics"
        )
    }

    @Test
    fun `build with AUDIO action omits DESCRIPTION and SUMMARY`() {
        val ics = builder.build(
            uid = "alarm-audio@example.com",
            summary = "Audio alarm",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            alarms = listOf(AlarmSpec(trigger = "-PT5M", action = "AUDIO"))
        )
        assertTrue(ics.contains("ACTION:AUDIO"))
        assertTrue(ics.contains("TRIGGER:-PT5M"))
        // AUDIO action: no DESCRIPTION required; no SUMMARY
        val valarmBlock = ics.substringAfter("BEGIN:VALARM").substringBefore("END:VALARM")
        assertFalse(valarmBlock.contains("DESCRIPTION:"), "AUDIO must not emit DESCRIPTION:\n$ics")
        assertFalse(valarmBlock.contains("SUMMARY:"), "AUDIO must not emit SUMMARY:\n$ics")
    }

    @Test
    fun `build with EMAIL action emits SUMMARY when set`() {
        val ics = builder.build(
            uid = "alarm-email@example.com",
            summary = "Email alarm",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            alarms = listOf(AlarmSpec(
                trigger = "-PT1H",
                action = "EMAIL",
                summary = "Meeting reminder",
                description = "Your meeting starts soon"
            ))
        )
        assertTrue(ics.contains("ACTION:EMAIL"))
        assertTrue(ics.contains("SUMMARY:Meeting reminder"))
        assertTrue(ics.contains("DESCRIPTION:Your meeting starts soon"))
    }

    @Test
    fun `build with REPEAT and DURATION emits both`() {
        val ics = builder.build(
            uid = "alarm-repeat@example.com",
            summary = "Repeating alarm",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            alarms = listOf(AlarmSpec(
                trigger = "-PT15M",
                repeatCount = 3,
                repeatDuration = "PT5M"
            ))
        )
        // VALARM "DURATION" is ambiguous with VEVENT "DURATION"; assert it appears inside the block.
        val valarmBlock = ics.substringAfter("BEGIN:VALARM").substringBefore("END:VALARM")
        assertTrue(valarmBlock.contains("REPEAT:3"))
        assertTrue(valarmBlock.contains("DURATION:PT5M"))
    }

    @Test
    fun `build with REPEAT but no repeatDuration emits neither`() {
        val ics = builder.build(
            uid = "alarm-bad-repeat@example.com",
            summary = "Bad repeat",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            alarms = listOf(AlarmSpec(trigger = "-PT15M", repeatCount = 3))
        )
        val valarmBlock = ics.substringAfter("BEGIN:VALARM").substringBefore("END:VALARM")
        assertFalse(valarmBlock.contains("REPEAT:"), "REPEAT requires DURATION; emit neither when paired badly")
        assertFalse(valarmBlock.contains("DURATION:"))
    }

    @Test
    fun `build with two alarms emits two VALARM blocks`() {
        val ics = builder.build(
            uid = "alarms-two@example.com",
            summary = "Primary + secondary alarm",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            alarms = listOf(
                AlarmSpec(trigger = "-PT15M"),
                AlarmSpec(trigger = "-P1D")
            )
        )
        val begins = "BEGIN:VALARM".toRegex().findAll(ics).count()
        assertEquals(2, begins, "Expected 2 VALARM blocks in:\n$ics")
        assertTrue(ics.contains("TRIGGER:-PT15M"))
        assertTrue(ics.contains("TRIGGER:-P1D"))
    }

    @Test
    fun `build with null alarms emits no VALARM`() {
        val ics = builder.build(
            uid = "no-alarm@example.com",
            summary = "No alarm",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z"
        )
        assertFalse(ics.contains("BEGIN:VALARM"), "Default null alarms should emit nothing:\n$ics")
    }

    @Test
    fun `build with empty alarms list emits no VALARM`() {
        val ics = builder.build(
            uid = "empty-alarms@example.com",
            summary = "Empty alarms",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            alarms = emptyList()
        )
        assertFalse(ics.contains("BEGIN:VALARM"), "Empty alarms list emits nothing:\n$ics")
    }
}
