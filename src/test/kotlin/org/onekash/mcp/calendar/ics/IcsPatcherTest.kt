package org.onekash.mcp.calendar.ics

import org.onekash.icaldav.testutil.IcsText.calendar
import org.onekash.icaldav.testutil.IcsText.componentBlocks
import org.onekash.icaldav.testutil.IcsText.unfoldedLines
import kotlin.test.*

/**
 * Tests for IcsPatcher: verifies round-trip preservation when patching
 * existing ICS data and fresh generation for new events.
 *
 * Tests for IcsPatcher: VALARM/ATTENDEE/X-* preservation, SEQUENCE, round-trip fidelity.
 */
class IcsPatcherTest {

    private val patcher = IcsPatcher()
    private val parser = IcsParser()

    // ========== VALARM Preservation ==========

    @Test
    fun `patch preserves VALARM blocks`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:alarm-test@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Title
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 minutes before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "alarm-test@test",
            summary = "Updated Title"
        )

        // All 3 VALARM blocks should be preserved
        val alarmCount = patched.split("BEGIN:VALARM").size - 1
        assertEquals(3, alarmCount, "All 3 VALARM blocks should be preserved")
        assertTrue(patched.contains("TRIGGER:-PT15M"), "15 min alarm preserved")
        assertTrue(patched.contains("TRIGGER:-PT1H"), "1 hour alarm preserved")
        assertTrue(patched.contains("TRIGGER:-P1D"), "1 day alarm preserved")
        assertTrue(patched.contains("SUMMARY:Updated Title"), "Title should be updated")
    }

    // ========== ATTENDEE/ORGANIZER Preservation ==========

    @Test
    fun `patch preserves attendees and organizer`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:attendee-test@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Meeting with Attendees
            ORGANIZER;CN=John Doe:mailto:john@example.com
            ATTENDEE;CN=Jane Smith;PARTSTAT=ACCEPTED:mailto:jane@example.com
            ATTENDEE;CN=Bob Wilson;PARTSTAT=TENTATIVE:mailto:bob@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "attendee-test@test",
            summary = "Updated Meeting"
        )

        assertTrue(patched.contains("john@example.com"), "Organizer preserved")
        assertTrue(patched.contains("jane@example.com"), "Attendee Jane preserved")
        assertTrue(patched.contains("bob@example.com"), "Attendee Bob preserved")
        assertTrue(patched.contains("SUMMARY:Updated Meeting"), "Title updated")

        // Verify via parser
        val parsed = parser.parse(patched)
        assertEquals(1, parsed.size)
        assertEquals("Updated Meeting", parsed[0].summary)
        assertEquals(2, parsed[0].attendeeCount, "Should preserve 2 attendees")
        assertNotNull(parsed[0].organizer, "Should preserve organizer")
    }

    // ========== X-* Property Preservation ==========

    @Test
    fun `patch preserves X-APPLE and custom properties`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:xprop-test@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event with Apple Props
            X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
            X-APPLE-STRUCTURED-LOCATION;VALUE=URI:geo:37.33,-122.03
            X-CUSTOM-PROP:custom value
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "xprop-test@test",
            summary = "Updated Title"
        )

        assertTrue(patched.contains("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR"), "Apple travel preserved")
        assertTrue(patched.contains("X-APPLE-STRUCTURED-LOCATION"), "Apple location preserved")
        assertTrue(patched.contains("X-CUSTOM-PROP"), "Custom property preserved")
    }

    // ========== SEQUENCE Increment ==========

    @Test
    fun `patch increments SEQUENCE number`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:seq-test@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original
            SEQUENCE:5
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "seq-test@test",
            summary = "Updated"
        )

        assertTrue(patched.contains("SEQUENCE:6"), "SEQUENCE should be incremented from 5 to 6")
    }

    @Test
    fun `patch adds SEQUENCE 1 when none exists`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-seq@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:No Sequence
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "no-seq@test",
            summary = "Updated"
        )

        assertTrue(patched.contains("SEQUENCE:1"), "SEQUENCE should start at 1")
    }

    // ========== Time Updates ==========

    @Test
    fun `patch updates DTSTART and DTEND for timed events`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:time-update@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original Time
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "time-update@test",
            startTime = "2025-12-26T14:00:00Z",
            endTime = "2025-12-26T15:00:00Z"
        )

        // Verify via parser - times should be updated
        val parsed = parser.parse(patched)
        assertEquals(1, parsed.size)
        assertEquals("2025-12-26T14:00:00Z", parsed[0].startTime)
        assertEquals("2025-12-26T15:00:00Z", parsed[0].endTime)
        assertFalse(parsed[0].isAllDay)
    }

    @Test
    fun `patch updates times with a Z-suffixed datetime`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:z-update@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Z Update
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "z-update@test",
            startTime = "2026-08-06T09:30:00Z",
            endTime = "2026-08-06T10:30:00Z"
        )

        assertTrue(patched.contains("DTSTART:20260806T093000Z"), patched)
        assertTrue(patched.contains("DTEND:20260806T103000Z"), patched)
        assertFalse(patched.contains("TZID="))
    }

    @Test
    fun `patch updates times with an offset datetime storing the correct UTC instant`() {
        // Issue #14: update_event goes through IcsPatcher, not IcsBuilder.
        // 18:30+09:00 = 09:30 UTC.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:offset-update@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Offset Update
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "offset-update@test",
            startTime = "2026-08-06T18:30:00+09:00",
            endTime = "2026-08-06T19:30:00+09:00"
        )

        assertTrue(patched.contains("DTSTART:20260806T093000Z"), patched)
        assertTrue(patched.contains("DTEND:20260806T103000Z"), patched)
        assertFalse(patched.contains("TZID="))
    }

    @Test
    fun `patch offset datetime ignores the timezone parameter (offset wins)`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:offset-wins-update@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Offset Wins Update
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "offset-wins-update@test",
            startTime = "2026-08-06T18:30:00+09:00",  // = 09:30 UTC
            endTime = "2026-08-06T19:30:00+09:00",    // = 10:30 UTC
            timezone = "America/New_York"             // must be ignored
        )

        assertTrue(patched.contains("DTSTART:20260806T093000Z"), patched)
        assertTrue(patched.contains("DTEND:20260806T103000Z"), patched)
        assertFalse(patched.contains("TZID="))
    }

    @Test
    fun `patchOccurrence applies an offset start time as an absolute instant`() {
        // The offset precedence must also hold through the RECURRENCE-ID exception path.
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:series-offset@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Weekly
            RRULE:FREQ=WEEKLY
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patchOccurrence(
            existingIcs = originalIcs,
            recurrenceId = "20251225T100000Z",
            startTime = "2026-08-06T18:30:00+09:00",  // = 09:30 UTC
            endTime = "2026-08-06T19:30:00+09:00"     // = 10:30 UTC
        )

        assertTrue(patched.contains("DTSTART:20260806T093000Z"), patched)
        assertTrue(patched.contains("DTEND:20260806T103000Z"), patched)
    }

    @Test
    fun `patch updates all-day dates`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-update@test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251225
            DTEND;VALUE=DATE:20251226
            SUMMARY:Christmas Day
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "allday-update@test",
            startDate = "2025-12-31",
            endDate = "2025-12-31",
            isAllDay = true
        )

        val parsed = parser.parse(patched)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].isAllDay, "Should remain all-day")
        assertEquals("2025-12-31", parsed[0].startDate)
        assertEquals("2025-12-31", parsed[0].endDate, "End date should be inclusive")
    }

    @Test
    fun `patch updates times with timezone`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:tz-update@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Timezone Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "tz-update@test",
            startTime = "2025-12-26T10:00:00",
            endTime = "2025-12-26T11:00:00",
            timezone = "America/New_York"
        )

        assertTrue(patched.contains("TZID=America/New_York"), "Should include TZID")
        // Parse and verify the time is correct
        val parsed = parser.parse(patched)
        assertEquals(1, parsed.size)
        assertFalse(parsed[0].isAllDay)
        assertNotNull(parsed[0].startTime)
    }

    @Test
    fun `patch keeps existing times when no time params provided`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:keep-time@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Keep My Time
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "keep-time@test",
            summary = "Title Changed Only"
        )

        val parsed = parser.parse(patched)
        assertEquals(1, parsed.size)
        assertEquals("2025-12-25T10:00:00Z", parsed[0].startTime, "Start time preserved")
        assertEquals("2025-12-25T11:00:00Z", parsed[0].endTime, "End time preserved")
    }

    // ========== RFC 5545/7986 Extended Properties ==========

    @Test
    fun `patch updates STATUS`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:status-test@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            STATUS:CONFIRMED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "status-test@test",
            status = "TENTATIVE"
        )

        assertTrue(patched.contains("STATUS:TENTATIVE"), "STATUS should be updated")
        assertFalse(patched.contains("STATUS:CONFIRMED"), "Old STATUS should be removed")
    }

    @Test
    fun `patch updates URL`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:url-test@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "url-test@test",
            url = "https://example.com/meeting"
        )

        val parsed = parser.parse(patched)
        assertEquals("https://example.com/meeting", parsed[0].url)
    }

    @Test
    fun `patch updates CATEGORIES`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:cat-test@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            CATEGORIES:OLD
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "cat-test@test",
            categories = listOf("MEETING", "WORK")
        )

        val parsed = parser.parse(patched)
        assertTrue(parsed[0].categories.contains("MEETING"))
        assertTrue(parsed[0].categories.contains("WORK"))
        assertFalse(parsed[0].categories.contains("OLD"), "Old category should be replaced")
    }

    @Test
    fun `patch updates PRIORITY`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:pri-test@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            PRIORITY:5
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "pri-test@test",
            priority = 1
        )

        val parsed = parser.parse(patched)
        assertEquals(1, parsed[0].priority, "Priority should be updated to 1")
    }

    @Test
    fun `patch keeps existing extended properties when not provided`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:keep-ext@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            STATUS:CONFIRMED
            PRIORITY:3
            CATEGORIES:WORK
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Only update summary - everything else should be preserved
        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "keep-ext@test",
            summary = "Updated"
        )

        assertTrue(patched.contains("STATUS:CONFIRMED"), "STATUS preserved")
        assertTrue(patched.contains("PRIORITY:3"), "PRIORITY preserved")
        assertTrue(patched.contains("CATEGORIES:WORK"), "CATEGORIES preserved")
    }

    // ========== RRULE Updates ==========

    @Test
    fun `patch updates RRULE`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rrule-test@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Weekly
            RRULE:FREQ=WEEKLY;BYDAY=MO
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "rrule-test@test",
            rrule = "FREQ=DAILY;COUNT=10"
        )

        assertTrue(patched.contains("RRULE:FREQ=DAILY"), "RRULE should be updated")
        assertFalse(patched.contains("BYDAY=MO"), "Old RRULE should be replaced")
    }

    @Test
    fun `patch removes RRULE when set to blank`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rrule-remove@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Was Recurring
            RRULE:FREQ=WEEKLY
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "rrule-remove@test",
            rrule = ""
        )

        assertFalse(patched.contains("RRULE:"), "RRULE should be removed")
    }

    // ========== Fallback Tests ==========

    @Test
    fun `patch falls back to IcsBuilder when existingIcs is null`() {
        val result = patcher.patch(
            existingIcs = null,
            uid = "fallback-null@test",
            summary = "New Event",
            startTime = "2025-12-25T10:00:00Z",
            endTime = "2025-12-25T11:00:00Z"
        )

        assertTrue(result.contains("BEGIN:VCALENDAR"))
        assertTrue(result.contains("SUMMARY:New Event"))
        assertTrue(result.contains("UID:fallback-null@test"))
    }

    @Test
    fun `patch falls back to IcsBuilder when existingIcs is blank`() {
        val result = patcher.patch(
            existingIcs = "  ",
            uid = "fallback-blank@test",
            summary = "New Event",
            startTime = "2025-12-25T10:00:00Z",
            endTime = "2025-12-25T11:00:00Z"
        )

        assertTrue(result.contains("BEGIN:VCALENDAR"))
        assertTrue(result.contains("SUMMARY:New Event"))
    }

    @Test
    fun `patch throws on unparseable existingIcs instead of silent buildFresh fallback`() {
        // Issue #2 hardening: when existingIcs is non-blank but unparseable,
        // returning a silently-rebuilt event was hiding data corruption from
        // the caller. Now we surface the failure as a typed exception so the
        // service layer can propagate a clean 422 to the LLM client.
        val ex = assertFailsWith<IcsPatcher.UnparseableExistingIcsException> {
            patcher.patch(
                existingIcs = "not valid ical data",
                uid = "fallback-invalid@test",
                summary = "Fallback Event",
                startTime = "2025-12-25T10:00:00Z",
                endTime = "2025-12-25T11:00:00Z"
            )
        }
        // Sanity: the exception carries a fingerprint of what failed
        assertTrue(
            ex.message!!.isNotBlank(),
            "Exception should carry a non-empty diagnostic message"
        )
    }

    @Test
    fun `patch recovers original data when existing ICS uses LF-only folding`() {
        // Reported via issue #2: when existing ICS came back from iCloud with bare
        // LF instead of CRLF, the old ical4j CalendarBuilder bailed and the patcher
        // silently returned a fresh "Untitled" event — corrupting the SUMMARY on a
        // description-only update. The vendored icaldav parser is relaxed about
        // line endings and recovers the event losslessly, so a description-only
        // patch now preserves the original SUMMARY instead of destroying it. That
        // is strictly better than the interim "throw a typed exception" behavior.
        val descRaw = "DESCRIPTION:Send a short follow-up email with an attachment for a warm introduction. Frame around the pitch, not generic availability."
        val foldedDescription = descRaw.substring(0, 75) + "\r\n " + descRaw.substring(75)
        val lfOnly = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//EN",
            "BEGIN:VEVENT",
            "UID:lf-fold@test",
            "DTSTAMP:20260115T100000Z",
            "DTSTART:20260120T140000Z",
            "DTEND:20260120T150000Z",
            "SUMMARY:Original SUMMARY value",
            foldedDescription,
            "END:VEVENT",
            "END:VCALENDAR"
        ).joinToString("\r\n").replace("\r\n", "\n")  // strip CRLF, leave bare LF

        val patched = patcher.patch(
            existingIcs = lfOnly,
            uid = "lf-fold@test",
            description = "MCP TEST touch simple."  // description-only update
        )

        // The original SUMMARY survives (no "Untitled" corruption) and the new
        // description is applied.
        val event = parser.parse(patched).single()
        assertEquals("Original SUMMARY value", event.summary)
        assertEquals("MCP TEST touch simple.", event.description)
    }

    @Test
    fun `patch uses Untitled when summary is null for fallback`() {
        val result = patcher.patch(
            existingIcs = null,
            uid = "untitled@test",
            startTime = "2025-12-25T10:00:00Z",
            endTime = "2025-12-25T11:00:00Z"
        )

        assertTrue(result.contains("SUMMARY:Untitled"))
    }

    // ========== VCALENDAR Structure (RFC 5545) ==========

    @Test
    fun `patched ICS has valid VCALENDAR structure`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:structure@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "structure@test",
            summary = "Updated"
        )

        assertTrue(patched.contains("BEGIN:VCALENDAR"))
        assertTrue(patched.contains("END:VCALENDAR"))
        assertTrue(patched.contains("VERSION:2.0"))
        assertTrue(patched.contains("BEGIN:VEVENT"))
        assertTrue(patched.contains("END:VEVENT"))
    }

    @Test
    fun `patched ICS updates DTSTAMP`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:dtstamp@test
            DTSTAMP:20200101T000000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "dtstamp@test",
            summary = "Updated"
        )

        // DTSTAMP should be updated to current time (not the old 2020 value)
        assertFalse(patched.contains("DTSTAMP:20200101T000000Z"), "Old DTSTAMP should be replaced")
    }

    // ========== Round-Trip Fidelity ==========

    @Test
    fun `round trip - patch then parse preserves all fields`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:roundtrip@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original
            DESCRIPTION:A description
            LOCATION:Conference Room
            RRULE:FREQ=WEEKLY;BYDAY=TH
            STATUS:CONFIRMED
            PRIORITY:3
            CATEGORIES:MEETING,WORK
            ORGANIZER;CN=Boss:mailto:boss@example.com
            ATTENDEE;CN=Worker:mailto:worker@example.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Only update summary - everything else should round-trip
        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "roundtrip@test",
            summary = "Updated Title"
        )

        val parsed = parser.parse(patched)
        assertEquals(1, parsed.size)
        val event = parsed[0]

        assertEquals("roundtrip@test", event.uid)
        assertEquals("Updated Title", event.summary)
        assertEquals("A description", event.description)
        assertEquals("Conference Room", event.location)
        assertEquals("2025-12-25T10:00:00Z", event.startTime)
        assertEquals("2025-12-25T11:00:00Z", event.endTime)
        assertNotNull(event.rrule)
        assertTrue(event.rrule.contains("FREQ=WEEKLY"))
        assertEquals("CONFIRMED", event.status)
        assertEquals(3, event.priority)
        assertTrue(event.categories.contains("MEETING"))
        assertTrue(event.categories.contains("WORK"))
        assertNotNull(event.organizer)
        assertEquals(1, event.attendeeCount)
    }

    @Test
    fun `round trip - multiple patches preserve properties`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-patch@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:V1
            SEQUENCE:0
            ORGANIZER;CN=John:mailto:john@example.com
            ATTENDEE;CN=Jane:mailto:jane@example.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:Alarm
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // First patch: update summary
        val v2 = patcher.patch(existingIcs = originalIcs, uid = "multi-patch@test", summary = "V2")
        assertTrue(v2.contains("SEQUENCE:1"))

        // Second patch: update location
        val v3 = patcher.patch(existingIcs = v2, uid = "multi-patch@test", location = "Room 42")
        assertTrue(v3.contains("SEQUENCE:2"))

        // Third patch: update description
        val v4 = patcher.patch(existingIcs = v3, uid = "multi-patch@test", description = "New desc")
        assertTrue(v4.contains("SEQUENCE:3"))

        // Verify all changes accumulated and attendees/alarms preserved
        val parsed = parser.parse(v4)
        assertEquals(1, parsed.size)
        val event = parsed[0]
        assertEquals("V2", event.summary) // From v2 patch
        assertEquals("Room 42", event.location) // From v3 patch
        assertEquals("New desc", event.description) // From v4 patch
        assertEquals(1, event.attendeeCount, "Attendee should survive 3 patches")
        assertTrue(v4.contains("BEGIN:VALARM"), "VALARM should survive 3 patches")
    }

    // ========== Description/Location Removal ==========

    @Test
    fun `patch removes description when set to blank`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:desc-remove@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            DESCRIPTION:Old description
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "desc-remove@test",
            description = ""
        )

        assertFalse(patched.contains("DESCRIPTION:"), "DESCRIPTION should be removed")
    }

    @Test
    fun `patch removes location when set to blank`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:loc-remove@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Event
            LOCATION:Old Location
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "loc-remove@test",
            location = ""
        )

        assertFalse(patched.contains("LOCATION:"), "LOCATION should be removed")
    }

    // ========== All-Day RFC 5545 Compliance ==========

    @Test
    fun `all-day DTEND is exclusive (next day)`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-exc@test
            DTSTAMP:20251220T100000Z
            DTSTART;VALUE=DATE:20251225
            DTEND;VALUE=DATE:20251226
            SUMMARY:Christmas
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Update to Feb 18 single day
        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "allday-exc@test",
            startDate = "2026-02-18",
            endDate = "2026-02-18",
            isAllDay = true
        )

        // DTEND should be Feb 19 (exclusive, RFC 5545)
        assertTrue(patched.contains("20260219"), "DTEND should be Feb 19 (exclusive)")
        assertTrue(patched.contains("20260218"), "DTSTART should be Feb 18")
    }

    @Test
    fun `multi-day all-day event has correct exclusive DTEND`() {
        val patched = patcher.patch(
            existingIcs = null,
            uid = "multiday@test",
            summary = "3 Day Event",
            startDate = "2026-02-18",
            endDate = "2026-02-20",
            isAllDay = true
        )

        // 3-day event Feb 18-20: DTEND must be Feb 21
        assertTrue(patched.contains("20260218"), "DTSTART should be Feb 18")
        assertTrue(patched.contains("20260221"), "DTEND should be Feb 21 (exclusive)")
    }

    // ========== Comprehensive Update Test ==========

    @Test
    fun `patch updates all fields simultaneously`() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:full-update@test
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original
            DESCRIPTION:Old desc
            LOCATION:Old place
            STATUS:CONFIRMED
            PRIORITY:5
            CATEGORIES:OLD
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patch(
            existingIcs = originalIcs,
            uid = "full-update@test",
            summary = "New Title",
            startTime = "2025-12-26T14:00:00Z",
            endTime = "2025-12-26T16:00:00Z",
            description = "New description",
            location = "New Place",
            status = "TENTATIVE",
            priority = 1,
            categories = listOf("MEETING", "IMPORTANT"),
            rrule = "FREQ=DAILY;COUNT=5"
        )

        val parsed = parser.parse(patched)
        assertEquals(1, parsed.size)
        val event = parsed[0]

        assertEquals("New Title", event.summary)
        assertEquals("New description", event.description)
        assertEquals("New Place", event.location)
        assertEquals("2025-12-26T14:00:00Z", event.startTime)
        assertEquals("2025-12-26T16:00:00Z", event.endTime)
        assertEquals("TENTATIVE", event.status)
        assertEquals(1, event.priority)
        assertTrue(event.categories.contains("MEETING"))
        assertTrue(event.categories.contains("IMPORTANT"))
        assertNotNull(event.rrule)

        // VALARM should still be preserved even with full update
        assertTrue(patched.contains("BEGIN:VALARM"), "VALARM preserved through full update")
    }

    // ========== CREATED + LAST-MODIFIED (RFC 5545 §3.8.7.1, §3.8.7.3) ==========

    private val originalWithTimestamps = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:lm-test@example.com
        DTSTAMP:20240101T000000Z
        CREATED:20240101T000000Z
        LAST-MODIFIED:20240101T000000Z
        DTSTART:20260115T100000Z
        DTEND:20260115T110000Z
        SUMMARY:Original
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `patch refreshes LAST-MODIFIED but preserves CREATED`() {
        val patched = patcher.patch(
            existingIcs = originalWithTimestamps,
            uid = "lm-test@example.com",
            summary = "Edited"
        )

        // CREATED must be byte-identical (RFC §3.8.7.1: never changes after first set)
        assertTrue(
            patched.contains("CREATED:20240101T000000Z"),
            "CREATED should be preserved verbatim across patch:\n$patched"
        )
        // LAST-MODIFIED must have been refreshed (not the original 2024 value)
        assertFalse(
            patched.contains("LAST-MODIFIED:20240101T000000Z"),
            "LAST-MODIFIED should NOT be the original value:\n$patched"
        )
        assertTrue(
            patched.lineSequence().any { it.startsWith("LAST-MODIFIED:") },
            "LAST-MODIFIED line should be present after patch:\n$patched"
        )
    }

    @Test
    fun `patch preserves CREATED across two consecutive patches`() {
        val firstPatch = patcher.patch(
            existingIcs = originalWithTimestamps,
            uid = "lm-test@example.com",
            summary = "First edit"
        )
        val secondPatch = patcher.patch(
            existingIcs = firstPatch,
            uid = "lm-test@example.com",
            summary = "Second edit"
        )

        assertTrue(
            secondPatch.contains("CREATED:20240101T000000Z"),
            "CREATED should survive multiple patch cycles:\n$secondPatch"
        )
    }

    // ========== RDATE / EXDATE patch path ==========

    @Test
    fun `patch replaces existing RDATE values`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:rdate-patch@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            RRULE:FREQ=WEEKLY
            RDATE:20260214T100000Z
            SUMMARY:Recurring
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val patched = patcher.patch(
            existingIcs = original,
            uid = "rdate-patch@test",
            rdates = listOf("2026-03-14T10:00:00Z", "2026-04-14T10:00:00Z")
        )

        // Old RDATE removed, new ones present
        assertFalse(patched.contains("20260214T100000Z"), "Old RDATE should be removed:\n$patched")
        assertTrue(patched.contains("20260314T100000Z"))
        assertTrue(patched.contains("20260414T100000Z"))
    }

    @Test
    fun `patch replaces existing EXDATE values`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:exdate-patch@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            RRULE:FREQ=WEEKLY
            EXDATE:20260212T100000Z
            SUMMARY:Recurring
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val patched = patcher.patch(
            existingIcs = original,
            uid = "exdate-patch@test",
            exdates = listOf("2026-02-19T10:00:00Z")
        )

        assertFalse(patched.contains("20260212T100000Z"), "Old EXDATE should be removed:\n$patched")
        assertTrue(patched.contains("20260219T100000Z"))
    }

    @Test
    fun `patch with null rdates leaves existing RDATE untouched`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:rdate-keep@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            RRULE:FREQ=WEEKLY
            RDATE:20260214T100000Z
            SUMMARY:Recurring
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val patched = patcher.patch(
            existingIcs = original,
            uid = "rdate-keep@test",
            summary = "Edited title only"
            // rdates = null (default) — must NOT remove existing
        )

        assertTrue(patched.contains("20260214T100000Z"), "Existing RDATE must survive when rdates=null:\n$patched")
    }

    @Test
    fun `patch does not synthesize CREATED when missing`() {
        val originalWithoutCreated = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-created@example.com
            DTSTAMP:20240101T000000Z
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            SUMMARY:No CREATED here
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val patched = patcher.patch(
            existingIcs = originalWithoutCreated,
            uid = "no-created@example.com",
            summary = "Edited"
        )

        assertFalse(
            patched.lineSequence().any { it.startsWith("CREATED:") },
            "Patcher should not fabricate CREATED when source had none:\n$patched"
        )
    }

    // ========== VALARM authoring (issue #1, RFC 5545 §3.6.6) ==========

    private val originalWithTwoAlarms = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//EN
        BEGIN:VEVENT
        UID:alarm-patch@test
        DTSTAMP:20260101T000000Z
        DTSTART:20260115T100000Z
        DTEND:20260115T110000Z
        SUMMARY:With two alarms
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:-PT15M
        DESCRIPTION:Primary
        END:VALARM
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:-P1D
        DESCRIPTION:Day before
        END:VALARM
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `patch with non-null alarms replaces existing alarms`() {
        val patched = patcher.patch(
            existingIcs = originalWithTwoAlarms,
            uid = "alarm-patch@test",
            alarms = listOf(AlarmSpec(trigger = "-PT5M"))
        )

        val begins = "BEGIN:VALARM".toRegex().findAll(patched).count()
        assertEquals(1, begins, "Replaced two alarms with one:\n$patched")
        assertTrue(patched.contains("TRIGGER:-PT5M"), "New alarm trigger present:\n$patched")
        assertFalse(patched.contains("TRIGGER:-PT15M"), "Old alarm trigger gone:\n$patched")
        assertFalse(patched.contains("TRIGGER:-P1D"), "Old alarm trigger gone:\n$patched")
    }

    @Test
    fun `patch with null alarms preserves existing alarms`() {
        val patched = patcher.patch(
            existingIcs = originalWithTwoAlarms,
            uid = "alarm-patch@test",
            summary = "Edit only"
        )
        val begins = "BEGIN:VALARM".toRegex().findAll(patched).count()
        assertEquals(2, begins, "null alarms must preserve existing:\n$patched")
    }

    @Test
    fun `patch with empty alarms list clears all existing alarms`() {
        val patched = patcher.patch(
            existingIcs = originalWithTwoAlarms,
            uid = "alarm-patch@test",
            alarms = emptyList()
        )
        assertFalse(patched.contains("BEGIN:VALARM"), "Empty list clears all:\n$patched")
    }

    // ========== Whole-series patch keeps edited occurrences ==========

    private val seriesMaster = """
        BEGIN:VEVENT
        UID:series@test
        DTSTAMP:20260101T000000Z
        DTSTART:20260105T090000Z
        DTEND:20260105T100000Z
        RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=6
        SUMMARY:Weekly sync
        END:VEVENT
    """.trimIndent()

    private val overrideFeb2 = """
        BEGIN:VEVENT
        UID:series@test
        DTSTAMP:20260101T000000Z
        RECURRENCE-ID:20260202T090000Z
        DTSTART:20260202T093000Z
        DTEND:20260202T103000Z
        SUMMARY:Moved sync
        END:VEVENT
    """.trimIndent()

    private val overrideJan19 = """
        BEGIN:VEVENT
        UID:series@test
        DTSTAMP:20260101T000000Z
        RECURRENCE-ID:20260119T090000Z
        DTSTART:20260119T090000Z
        DTEND:20260119T100000Z
        SUMMARY:Planning edition
        END:VEVENT
    """.trimIndent()

    private fun prop(vevent: List<String>, name: String): String? =
        vevent.firstOrNull { it.startsWith("$name:") || it.startsWith("$name;") }?.substringAfter(":")

    private fun assertOverrideIntact(patched: String, recurrenceId: String, start: String, summary: String) {
        val match = componentBlocks(patched).filter { prop(it, "RECURRENCE-ID") == recurrenceId }
        assertEquals(1, match.size, "override $recurrenceId written once:\n$patched")
        assertEquals(start, prop(match[0], "DTSTART"), "override $recurrenceId keeps its DTSTART")
        assertEquals(summary, prop(match[0], "SUMMARY"), "override $recurrenceId keeps its SUMMARY")
        assertEquals("series@test", prop(match[0], "UID"))
        assertNull(prop(match[0], "RRULE"), "an override carries no RRULE")
    }

    /** RRULE parts as a set: the generator writes them in its own order. */
    private fun ruleParts(rrule: String?): Set<String>? = rrule?.split(';')?.toSet()

    private fun masterOf(patched: String): List<String> =
        componentBlocks(patched).single { prop(it, "RECURRENCE-ID") == null }

    @Test
    fun `whole-series patch keeps every override when the master is listed first`() {
        val patched = patcher.patch(
            existingIcs = calendar(seriesMaster, overrideJan19, overrideFeb2),
            uid = "series@test",
            summary = "Renamed sync"
        )
        assertEquals(3, veventCount(patched), "master + both overrides:\n$patched")
        assertEquals("Renamed sync", prop(masterOf(patched), "SUMMARY"))
        assertEquals(ruleParts("FREQ=WEEKLY;BYDAY=MO;COUNT=6"), ruleParts(prop(masterOf(patched), "RRULE")))
        assertOverrideIntact(patched, "20260119T090000Z", "20260119T090000Z", "Planning edition")
        assertOverrideIntact(patched, "20260202T090000Z", "20260202T093000Z", "Moved sync")
    }

    @Test
    fun `whole-series patch finds the master when an override is listed first and writes the master first`() {
        val patched = patcher.patch(
            existingIcs = calendar(overrideFeb2, seriesMaster),
            uid = "series@test",
            summary = "Renamed sync"
        )
        assertEquals(2, veventCount(patched), "master + override:\n$patched")
        val first = componentBlocks(patched).first()
        assertNull(prop(first, "RECURRENCE-ID"), "the master is written first:\n$patched")
        assertEquals("Renamed sync", prop(first, "SUMMARY"))
        assertEquals(ruleParts("FREQ=WEEKLY;BYDAY=MO;COUNT=6"), ruleParts(prop(first, "RRULE")), "the series keeps its RRULE")
        assertOverrideIntact(patched, "20260202T090000Z", "20260202T093000Z", "Moved sync")
        // The response the service builds from parse(...)[0] describes the master.
        assertEquals("Renamed sync", parser.parse(patched)[0].summary)
    }

    @Test
    fun `a resource with only overrides patches its first VEVENT and keeps the others`() {
        val patched = patcher.patch(
            existingIcs = calendar(overrideJan19, overrideFeb2),
            uid = "series@test",
            summary = "Renamed"
        )
        assertEquals(2, veventCount(patched), patched)
        val byRecid = componentBlocks(patched).associateBy { prop(it, "RECURRENCE-ID") }
        assertEquals("Renamed", prop(byRecid.getValue("20260119T090000Z"), "SUMMARY"), "first VEVENT patched")
        assertOverrideIntact(patched, "20260202T090000Z", "20260202T093000Z", "Moved sync")
    }

    @Test
    fun `moving the series time or changing its RRULE writes the overrides back unchanged`() {
        val moved = patcher.patch(
            existingIcs = calendar(seriesMaster, overrideFeb2),
            uid = "series@test",
            startTime = "2026-01-05T10:00:00Z",
            endTime = "2026-01-05T11:00:00Z"
        )
        assertEquals("20260105T100000Z", prop(masterOf(moved), "DTSTART"))
        assertOverrideIntact(moved, "20260202T090000Z", "20260202T093000Z", "Moved sync")

        val newRule = patcher.patch(
            existingIcs = calendar(seriesMaster, overrideFeb2),
            uid = "series@test",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10"
        )
        assertEquals(ruleParts("FREQ=WEEKLY;BYDAY=MO;COUNT=10"), ruleParts(prop(masterOf(newRule), "RRULE")))
        assertOverrideIntact(newRule, "20260202T090000Z", "20260202T093000Z", "Moved sync")
    }

    @Test
    fun `an override orphaned by a time move is stored but not emitted by get_events`() {
        val moved = patcher.patch(
            existingIcs = calendar(seriesMaster, overrideFeb2),
            uid = "series@test",
            startTime = "2026-01-05T10:00:00Z",
            endTime = "2026-01-05T11:00:00Z"
        )
        assertOverrideIntact(moved, "20260202T090000Z", "20260202T093000Z", "Moved sync")
        val occurrences = parser.parseOccurrences(
            moved, java.time.Instant.parse("2026-02-02T00:00:00Z"), java.time.Instant.parse("2026-02-03T00:00:00Z")
        )
        assertEquals(listOf("Weekly sync"), occurrences.map { it.summary }, "only the moved series occurrence")
        assertEquals(listOf("2026-02-02T10:00:00Z"), occurrences.map { it.startTime })
    }

    @Test
    fun `clearing the RRULE drops overrides unless RDATEs keep the series recurring`() {
        val single = patcher.patch(
            existingIcs = calendar(seriesMaster, overrideFeb2),
            uid = "series@test",
            rrule = ""
        )
        assertEquals(1, veventCount(single), "a now single event keeps no overrides:\n$single")
        assertNull(prop(masterOf(single), "RRULE"))

        val withRdate = calendar(
            seriesMaster.replace("RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=6", "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=6\nRDATE:20260202T090000Z"),
            overrideFeb2
        )
        val stillRecurring = patcher.patch(existingIcs = withRdate, uid = "series@test", rrule = "")
        assertEquals(2, veventCount(stillRecurring), "RDATE keeps the series recurring:\n$stillRecurring")
        assertOverrideIntact(stillRecurring, "20260202T090000Z", "20260202T093000Z", "Moved sync")
    }

    // ========== Single-occurrence: patchOccurrence / exdateOccurrence ==========

    private fun veventCount(ics: String) = ics.split("BEGIN:VEVENT").size - 1

    private val weeklyTimedSeries = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:series@test
        DTSTAMP:20260101T000000Z
        DTSTART:20260105T090000Z
        DTEND:20260105T100000Z
        RRULE:FREQ=WEEKLY;BYDAY=MO
        SUMMARY:Weekly sync
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test
    fun `patchOccurrence adds an exception VEVENT with the RECURRENCE-ID and patched summary`() {
        val patched = patcher.patchOccurrence(
            existingIcs = weeklyTimedSeries,
            recurrenceId = "20260202T090000Z",
            summary = "Moved sync"
        )
        assertEquals(2, veventCount(patched), "master + exception:\n$patched")
        assertTrue(patched.contains("RECURRENCE-ID:20260202T090000Z"), "exception carries the RECURRENCE-ID:\n$patched")
        assertTrue(patched.contains("SUMMARY:Moved sync"))
        // Master untouched: its DTSTART and RRULE survive, and RRULE appears once (only on the master).
        assertTrue(patched.contains("DTSTART:20260105T090000Z"), "master DTSTART unchanged")
        assertTrue(patched.contains("RRULE:FREQ=WEEKLY;BYDAY=MO"), "master RRULE unchanged")
        assertEquals(1, patched.split("RRULE:").size - 1, "only the master carries an RRULE")
    }

    @Test
    fun `patchOccurrence with only a summary keeps the exception DTSTART at the occurrence instant`() {
        val patched = patcher.patchOccurrence(
            existingIcs = weeklyTimedSeries,
            recurrenceId = "20260202T090000Z",
            summary = "Renamed"
        )
        // The exception's DTSTART is the occurrence's own start, NOT the master's 2026-01-05.
        assertTrue(patched.contains("DTSTART:20260202T090000Z"), "exception DTSTART = occurrence instant:\n$patched")
    }

    @Test
    fun `patchOccurrence moving the time updates the exception DTSTART but not its identity`() {
        val patched = patcher.patchOccurrence(
            existingIcs = weeklyTimedSeries,
            recurrenceId = "20260202T090000Z",
            startTime = "2026-02-02T15:00:00Z",
            endTime = "2026-02-02T16:00:00Z"
        )
        assertTrue(patched.contains("RECURRENCE-ID:20260202T090000Z"), "identity stays the original instant")
        assertTrue(patched.contains("DTSTART:20260202T150000Z"), "start moves to 15:00:\n$patched")
    }

    @Test
    fun `patchOccurrence updates an existing exception in place without duplicating it`() {
        val withException = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:series@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly sync
            END:VEVENT
            BEGIN:VEVENT
            UID:series@test
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260202T090000Z
            DTSTART:20260202T093000Z
            DTEND:20260202T103000Z
            SUMMARY:Old override
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patchOccurrence(
            existingIcs = withException,
            recurrenceId = "20260202T090000Z",
            summary = "New override"
        )
        assertEquals(2, veventCount(patched), "still master + one exception (no duplicate):\n$patched")
        assertTrue(patched.contains("SUMMARY:New override"))
        assertFalse(patched.contains("SUMMARY:Old override"))
    }

    @Test
    fun `exdateOccurrence adds an EXDATE on the master and leaves the series intact`() {
        val patched = patcher.exdateOccurrence(
            existingIcs = weeklyTimedSeries,
            recurrenceId = "20260202T090000Z"
        )
        assertEquals(1, veventCount(patched), "only the master remains:\n$patched")
        assertTrue(patched.contains("EXDATE:20260202T090000Z"), "EXDATE added for the cancelled instant:\n$patched")
        assertTrue(patched.contains("DTSTART:20260105T090000Z"), "master DTSTART unchanged")
        assertTrue(patched.contains("RRULE:FREQ=WEEKLY;BYDAY=MO"), "master RRULE unchanged")
    }

    @Test
    fun `exdateOccurrence drops a matching exception VEVENT`() {
        val withException = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:series@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly sync
            END:VEVENT
            BEGIN:VEVENT
            UID:series@test
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260202T090000Z
            DTSTART:20260202T093000Z
            DTEND:20260202T103000Z
            SUMMARY:Edited instance
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.exdateOccurrence(withException, "20260202T090000Z")
        assertEquals(1, veventCount(patched), "the exception is removed, master remains:\n$patched")
        assertFalse(patched.contains("SUMMARY:Edited instance"), "the edited instance is gone")
        assertTrue(patched.contains("EXDATE:20260202T090000Z"))
    }

    @Test
    fun `patchOccurrence works for an all-day series with a DATE RECURRENCE-ID`() {
        val yearlyAllDay = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday@test
            DTSTAMP:20230101T000000Z
            DTSTART;VALUE=DATE:20230517
            DTEND;VALUE=DATE:20230518
            RRULE:FREQ=YEARLY
            SUMMARY:Anniversary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val patched = patcher.patchOccurrence(yearlyAllDay, "20260517", summary = "Special anniversary")
        assertEquals(2, veventCount(patched))
        assertTrue(patched.contains("RECURRENCE-ID;VALUE=DATE:20260517"), "DATE-form RECURRENCE-ID:\n$patched")
        assertTrue(patched.contains("DTSTART;VALUE=DATE:20260517"), "exception is the 2026 occurrence day")
        assertTrue(patched.contains("SUMMARY:Special anniversary"))

        val exdated = patcher.exdateOccurrence(yearlyAllDay, "20260517")
        assertTrue(exdated.contains("EXDATE;VALUE=DATE:20260517"), "DATE-form EXDATE:\n$exdated")
    }

    @Test
    fun `patchOccurrence rejects a non-recurring event`() {
        val standalone = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:single@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            SUMMARY:One-off
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        assertFailsWith<IcsPatcher.NotARecurringSeriesException> {
            patcher.patchOccurrence(standalone, "20260105T090000Z", summary = "nope")
        }
        assertFailsWith<IcsPatcher.NotARecurringSeriesException> {
            patcher.exdateOccurrence(standalone, "20260105T090000Z")
        }
    }

    // ========== this-and-future: truncateSeries / splitSeries ==========

    private val dailyCountSeries = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:series@test
        DTSTAMP:20260101T000000Z
        DTSTART:20260105T090000Z
        DTEND:20260105T100000Z
        RRULE:FREQ=DAILY;COUNT=5
        SUMMARY:Daily standup
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test
    fun `truncateSeries caps the master RRULE with UNTIL at the last kept occurrence`() {
        // Weekly Mondays from 2026-01-05. Truncate at the 3rd (2026-01-19): keep 01-05 and 01-12.
        val truncated = patcher.truncateSeries(weeklyTimedSeries, "20260119T090000Z")
        assertEquals(1, veventCount(truncated), "only the master remains:\n$truncated")
        assertTrue(truncated.contains("DTSTART:20260105T090000Z"), "master DTSTART unchanged")
        assertTrue(truncated.contains("FREQ=WEEKLY"), "RRULE frequency preserved:\n$truncated")
        assertTrue(truncated.contains("BYDAY=MO"), "RRULE byday preserved")
        assertTrue(truncated.contains("UNTIL=20260112T090000Z"), "UNTIL = last kept occurrence (2nd Monday):\n$truncated")
    }

    @Test
    fun `truncateSeries drops exception VEVENTs at or after the cut`() {
        val withException = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:series@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            RRULE:FREQ=WEEKLY;BYDAY=MO
            SUMMARY:Weekly sync
            END:VEVENT
            BEGIN:VEVENT
            UID:series@test
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260126T090000Z
            DTSTART:20260126T093000Z
            DTEND:20260126T103000Z
            SUMMARY:Future override
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val truncated = patcher.truncateSeries(withException, "20260119T090000Z")
        assertEquals(1, veventCount(truncated), "the future override is dropped with the cut tail:\n$truncated")
        assertFalse(truncated.contains("SUMMARY:Future override"), "override past the cut is gone")
    }

    @Test
    fun `truncateSeries emits a DATE-form UNTIL for an all-day series`() {
        val yearlyAllDay = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday@test
            DTSTAMP:20230101T000000Z
            DTSTART;VALUE=DATE:20230517
            DTEND;VALUE=DATE:20230518
            RRULE:FREQ=YEARLY
            SUMMARY:Anniversary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val truncated = patcher.truncateSeries(yearlyAllDay, "20260517")
        assertTrue(truncated.contains("UNTIL=20250517"), "DATE-form UNTIL at the last kept year:\n$truncated")
        assertFalse(truncated.contains("UNTIL=20250517T"), "UNTIL is a bare DATE, not a DATE-TIME")
    }

    @Test
    fun `truncateSeries at the first occurrence throws FirstOccurrenceException`() {
        assertFailsWith<IcsPatcher.FirstOccurrenceException> {
            patcher.truncateSeries(weeklyTimedSeries, "20260105T090000Z")
        }
    }

    @Test
    fun `truncateSeries rejects a non-recurring event`() {
        val standalone = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:single@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            SUMMARY:One-off
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertFailsWith<IcsPatcher.NotARecurringSeriesException> {
            patcher.truncateSeries(standalone, "20260105T090000Z")
        }
    }

    @Test
    fun `splitSeries truncates the master and returns a fresh series from the occurrence`() {
        val split = patcher.splitSeries(
            existingIcs = weeklyTimedSeries,
            recurrenceId = "20260119T090000Z",
            summary = "Renamed from here"
        )

        // Master side: capped, original UID and summary intact.
        assertEquals(1, veventCount(split.truncatedMaster), "master side is one VEVENT:\n${split.truncatedMaster}")
        assertTrue(split.truncatedMaster.contains("UID:series@test"), "master keeps its UID")
        assertTrue(split.truncatedMaster.contains("SUMMARY:Weekly sync"), "master summary untouched")
        assertTrue(split.truncatedMaster.contains("UNTIL=20260112T090000Z"), "master is capped:\n${split.truncatedMaster}")

        // New series side: fresh UID, patched summary, starts at the occurrence, no exception marker.
        assertEquals(1, veventCount(split.newSeries), "new series is one VEVENT:\n${split.newSeries}")
        assertFalse(split.newSeries.contains("UID:series@test"), "new series gets a fresh UID:\n${split.newSeries}")
        assertTrue(split.newSeries.contains("UID:"), "new series has a UID")
        assertTrue(split.newSeries.contains("SUMMARY:Renamed from here"), "patch applied to the new series")
        assertTrue(split.newSeries.contains("DTSTART:20260119T090000Z"), "new series starts at the occurrence:\n${split.newSeries}")
        assertTrue(split.newSeries.contains("FREQ=WEEKLY"), "new series carries the recurrence rule")
        assertFalse(split.newSeries.contains("RECURRENCE-ID"), "new series is a master, not an exception")
        assertFalse(split.newSeries.contains("UNTIL="), "an open-ended series stays open-ended:\n${split.newSeries}")
    }

    @Test
    fun `splitSeries reduces COUNT on the new series and caps the master`() {
        // Daily COUNT=5 from 2026-01-05; split at day 3 (2026-01-07): master keeps 2, new series gets 3.
        val split = patcher.splitSeries(
            existingIcs = dailyCountSeries,
            recurrenceId = "20260107T090000Z",
            summary = "Reworked standup"
        )
        assertTrue(split.truncatedMaster.contains("UNTIL=20260106T090000Z"), "master capped at day 2:\n${split.truncatedMaster}")
        assertFalse(split.truncatedMaster.contains("COUNT="), "COUNT converted to UNTIL on the master")
        assertTrue(split.newSeries.contains("COUNT=3"), "new series keeps the remaining 3 occurrences:\n${split.newSeries}")
        assertTrue(split.newSeries.contains("DTSTART:20260107T090000Z"), "new series starts at the split point")
    }

    @Test
    fun `splitSeries moves the occurrence time when the patch changes it`() {
        val split = patcher.splitSeries(
            existingIcs = weeklyTimedSeries,
            recurrenceId = "20260119T090000Z",
            startTime = "2026-01-19T14:00:00Z",
            endTime = "2026-01-19T15:00:00Z"
        )
        assertTrue(split.newSeries.contains("DTSTART:20260119T140000Z"), "new series start moved to 14:00:\n${split.newSeries}")
    }

    @Test
    fun `splitSeries at the first occurrence throws FirstOccurrenceException`() {
        assertFailsWith<IcsPatcher.FirstOccurrenceException> {
            patcher.splitSeries(weeklyTimedSeries, "20260105T090000Z", summary = "whole series really")
        }
    }

    @Test
    fun `splitSeries rejects a non-recurring event`() {
        // Parallels the patchOccurrence/exdateOccurrence/truncateSeries rejections: the
        // this-and-future EDIT path also refuses a master with no RRULE/RDATE, so a
        // stale occurrence handle onto a de-recurred resource fails loud, not silently.
        val standalone = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:single@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260105T090000Z
            DTEND:20260105T100000Z
            SUMMARY:One-off
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertFailsWith<IcsPatcher.NotARecurringSeriesException> {
            patcher.splitSeries(standalone, "20260105T090000Z", summary = "nope")
        }
    }

    /** Weekly series with a modified instance (moved + relocated) at 2026-02-02, well after 01-19. */
    private val weeklySeriesWithLaterException = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:series@test
        DTSTAMP:20260101T000000Z
        DTSTART:20260105T090000Z
        DTEND:20260105T100000Z
        RRULE:FREQ=WEEKLY;BYDAY=MO
        SUMMARY:Weekly sync
        END:VEVENT
        BEGIN:VEVENT
        UID:series@test
        DTSTAMP:20260101T000000Z
        RECURRENCE-ID:20260202T090000Z
        DTSTART:20260202T093000Z
        DTEND:20260202T103000Z
        SUMMARY:Weekly sync (moved)
        LOCATION:Room 5
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test
    fun `splitSeries carries a modified instance after the cut onto the new series`() {
        // Cut at 01-19; the 02-02 exception falls after it, so it must move to the new series
        // (re-based to the fresh UID, keeping its RECURRENCE-ID and customization), not vanish.
        val split = patcher.splitSeries(
            existingIcs = weeklySeriesWithLaterException,
            recurrenceId = "20260119T090000Z",
            summary = "Renamed from here"
        )

        // The exception is gone from the truncated master (it is past the cut).
        assertEquals(1, veventCount(split.truncatedMaster), "master side drops the post-cut exception:\n${split.truncatedMaster}")

        // The new series carries the master plus the re-based exception.
        assertEquals(2, veventCount(split.newSeries), "new series = master + carried exception:\n${split.newSeries}")
        assertTrue(split.newSeries.contains("RECURRENCE-ID:20260202T090000Z"), "carried exception keeps its RECURRENCE-ID:\n${split.newSeries}")
        assertTrue(split.newSeries.contains("LOCATION:Room 5"), "carried exception keeps its customization:\n${split.newSeries}")
        assertFalse(split.newSeries.contains("UID:series@test"), "carried exception re-based off the old UID:\n${split.newSeries}")
        // Master and the carried exception share ONE fresh UID.
        val uids = Regex("UID:([^\r\n]+)").findAll(split.newSeries).map { it.groupValues[1] }.toSet()
        assertEquals(1, uids.size, "the new series and its exception share a single UID: $uids")
    }

    @Test
    fun `splitSeries drops the exception exactly at the cut so the patch defines it`() {
        // Cut AT the 02-02 exception: that instance becomes the new series' first occurrence,
        // defined by the this-and-future patch, so the old override must not be carried.
        val split = patcher.splitSeries(
            existingIcs = weeklySeriesWithLaterException,
            recurrenceId = "20260202T090000Z",
            summary = "Renamed from the moved one",
            location = "Room 9"
        )

        assertFalse(split.newSeries.contains("RECURRENCE-ID"), "no leftover exception at the cut:\n${split.newSeries}")
        assertEquals(1, veventCount(split.newSeries), "new series is just the patched master:\n${split.newSeries}")
        assertTrue(split.newSeries.contains("LOCATION:Room 9"), "the patch, not the old override, defines the instance:\n${split.newSeries}")
        assertFalse(split.newSeries.contains("Room 5"), "old override location dropped:\n${split.newSeries}")
    }

    // ========== exdates input forms and UNTIL moving with the series ==========

    private val nyDaily = calendar("""
        BEGIN:VEVENT
        UID:ny@test
        DTSTAMP:20260101T000000Z
        DTSTART;TZID=America/New_York:20260301T090000
        DTEND;TZID=America/New_York:20260301T100000
        RRULE:FREQ=DAILY;UNTIL=20260315T130000Z
        SUMMARY:NY daily
        END:VEVENT
    """.trimIndent())

    @Test
    fun `patch turns a date exdate on a one-per-day timed series into that day's occurrence`() {
        val patched = patcher.patch(existingIcs = nyDaily, uid = "ny@test", exdates = listOf("2026-03-04"))
        assertTrue(patched.contains("EXDATE;TZID=America/New_York:20260304T090000"), patched)
    }

    @Test
    fun `patch rejects a date exdate on a multi-per-day series`() {
        val twice = nyDaily.replace("RRULE:FREQ=DAILY;UNTIL=20260315T130000Z", "RRULE:FREQ=DAILY;BYHOUR=9,17;COUNT=6")
        assertFailsWith<IcsBuilder.AmbiguousRecurrenceDateException> {
            patcher.patch(existingIcs = twice, uid = "ny@test", exdates = listOf("2026-03-04"))
        }
    }

    @Test
    fun `patch reads a naive date-time exdate in the series zone`() {
        val patched = patcher.patch(existingIcs = nyDaily, uid = "ny@test", exdates = listOf("2026-03-04T09:00:00"))
        assertTrue(patched.contains("EXDATE;TZID=America/New_York:20260304T090000"), patched)
    }

    private fun until(ics: String): String? =
        masterOf(ics).let { prop(it, "RRULE") }?.split(';')?.firstOrNull { it.startsWith("UNTIL=") }?.removePrefix("UNTIL=")

    @Test
    fun `moving a UTC series later or earlier shifts UNTIL by the same amount`() {
        val utcSeries = calendar("""
            BEGIN:VEVENT
            UID:utc@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260101T090000Z
            DTEND:20260101T100000Z
            RRULE:FREQ=DAILY;UNTIL=20260110T090000Z
            SUMMARY:UTC daily
            END:VEVENT
        """.trimIndent())
        val later = patcher.patch(utcSeries, "utc@test", startTime = "2026-01-01T10:00:00Z", endTime = "2026-01-01T11:00:00Z")
        assertEquals("20260110T100000Z", until(later))
        val earlier = patcher.patch(utcSeries, "utc@test", startTime = "2026-01-01T08:00:00Z", endTime = "2026-01-01T09:00:00Z")
        assertEquals("20260110T080000Z", until(earlier))
    }

    @Test
    fun `moving a New York series across DST keeps its last local occurrence`() {
        // UNTIL is Mar 15 09:00 EDT (13:00Z). Moved to 10:00 local, the last occurrence is Mar 15 10:00 EDT.
        val moved = patcher.patch(nyDaily, "ny@test", startTime = "2026-03-01T10:00:00", endTime = "2026-03-01T11:00:00",
            timezone = "America/New_York")
        assertEquals("20260315T140000Z", until(moved))
        val occ = parser.parseOccurrences(moved, java.time.Instant.parse("2026-03-14T00:00:00Z"),
            java.time.Instant.parse("2026-03-17T00:00:00Z")).map { it.startTime }
        assertEquals(listOf("2026-03-14T14:00:00Z", "2026-03-15T14:00:00Z"), occ)
    }

    @Test
    fun `moving a TZID series to a UTC start keeps the same last occurrence in local terms`() {
        // Old: 09:00 New York (14:00Z on Mar 1), UNTIL Mar 15 09:00 EDT. New start 15:00Z: a 6 hour local shift.
        val moved = patcher.patch(nyDaily, "ny@test", startTime = "2026-03-01T15:00:00Z", endTime = "2026-03-01T16:00:00Z")
        assertEquals("20260315T150000Z", until(moved))
    }

    @Test
    fun `a supplied rrule is used unchanged when the series moves`() {
        val moved = patcher.patch(nyDaily, "ny@test", startTime = "2026-03-01T10:00:00", endTime = "2026-03-01T11:00:00",
            timezone = "America/New_York", rrule = "FREQ=DAILY;UNTIL=20260320T000000Z")
        assertEquals("20260320T000000Z", until(moved))
    }

    @Test
    fun `moving an all-day series shifts a DATE UNTIL by the same number of days`() {
        val allDay = calendar("""
            BEGIN:VEVENT
            UID:allday@test
            DTSTAMP:20260101T000000Z
            DTSTART;VALUE=DATE:20260301
            DTEND;VALUE=DATE:20260302
            RRULE:FREQ=DAILY;UNTIL=20260310
            SUMMARY:All-day daily
            END:VEVENT
        """.trimIndent())
        val moved = patcher.patch(allDay, "allday@test", startDate = "2026-03-02", endDate = "2026-03-02", isAllDay = true)
        assertEquals("20260311", until(moved))
    }

    private val utcUntilSeries = calendar("""
        BEGIN:VEVENT
        UID:split@test
        DTSTAMP:20250101T000000Z
        DTSTART:20250115T090000Z
        DTEND:20250115T091500Z
        RRULE:FREQ=DAILY;UNTIL=20250130T090000Z
        SUMMARY:Split daily
        END:VEVENT
    """.trimIndent())

    @Test
    fun `a truncated series moved later or earlier keeps its last occurrence and no split-day duplicate`() {
        val split = patcher.splitSeries(utcUntilSeries, recurrenceId = "20250120T090000Z", summary = "Continuing")
        for ((start, end, hour) in listOf(Triple("10:00", "10:15", "10"), Triple("08:00", "08:15", "08"))) {
            val moved = patcher.patch(split.truncatedMaster, "split@test",
                startTime = "2025-01-15T$start:00Z", endTime = "2025-01-15T$end:00Z")
            val occ = parser.parseOccurrences(moved, java.time.Instant.parse("2025-01-15T00:00:00Z"),
                java.time.Instant.parse("2025-02-01T00:00:00Z")).map { it.startTime!! }
            assertEquals((15..19).map { "2025-01-${it}T$hour:00:00Z" }, occ, "moved to $start")
        }
    }

    @Test
    fun `the continuing series of a split that moves the time shifts its UNTIL too`() {
        val split = patcher.splitSeries(utcUntilSeries, recurrenceId = "20250120T090000Z",
            startTime = "2025-01-20T10:00:00Z", endTime = "2025-01-20T10:15:00Z")
        val occ = parser.parseOccurrences(split.newSeries, java.time.Instant.parse("2025-01-20T00:00:00Z"),
            java.time.Instant.parse("2025-02-01T00:00:00Z")).map { it.startTime!! }
        assertEquals((20..30).map { "2025-01-${it}T10:00:00Z" }, occ, "continuing series still ends Jan 30")
    }

    // ========== Unknown properties kept as their original lines ==========

    private val structuredLocation = "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS=\"1 Infinite Loop\\nCupertino CA 95014\\nUnited States\";X-APPLE-RADIUS=70;X-TITLE=\"Apple Park\":geo:37.33,-122.01"
    private val unknownLines = listOf(structuredLocation, "X-REPEATED:one", "X-REPEATED:two", "COMMENT:a\\, b\\; c")

    private fun withUnknownLines(rrule: String? = null) = calendar((listOf(
        "BEGIN:VEVENT", "UID:unknown@test", "DTSTAMP:20260101T000000Z",
        "DTSTART:20260105T090000Z", "DTEND:20260105T100000Z", "SUMMARY:Original", "LOCATION:Apple Park"
    ) + listOfNotNull(rrule?.let { "RRULE:$it" }) + unknownLines + "END:VEVENT").joinToString("\n"))


    private fun unknownIn(vevent: List<String>) = vevent.filter { line -> unknownLines.any { it == line } }

    @Test
    fun `a summary edit writes every unknown line back byte for byte and in order`() {
        val patched = patcher.patch(withUnknownLines(), "unknown@test", summary = "Renamed")
        val lines = unfoldedLines(patched)
        assertEquals(unknownLines, lines.filter { it in unknownLines }, patched)
        assertTrue(lines.contains("SUMMARY:Renamed"), patched)
        assertTrue(lines.contains("LOCATION:Apple Park"), patched)
    }

    @Test
    fun `a this_and_future split gives the continuing series the original's unknown lines`() {
        val split = patcher.splitSeries(withUnknownLines("FREQ=DAILY;COUNT=5"), recurrenceId = "20260107T090000Z",
            summary = "Continuing")
        assertEquals(unknownLines, unfoldedLines(split.newSeries).filter { it in unknownLines }, split.newSeries)
        assertEquals(unknownLines, unfoldedLines(split.truncatedMaster).filter { it in unknownLines }, split.truncatedMaster)
    }

    @Test
    fun `a this_occurrence edit gives the new override the master's unknown lines`() {
        val patched = patcher.patchOccurrence(withUnknownLines("FREQ=DAILY;COUNT=5"), recurrenceId = "20260107T090000Z",
            summary = "One-off")
        val blocks = componentBlocks(patched)
        assertEquals(2, blocks.size, patched)
        for (block in blocks) assertEquals(unknownLines, unknownIn(block), "each VEVENT keeps the lines:\n$patched")
    }

    @Test
    fun `a LOCATION edit leaves X-APPLE-STRUCTURED-LOCATION as it was`() {
        val patched = patcher.patch(withUnknownLines(), "unknown@test", location = "Somewhere else")
        val lines = unfoldedLines(patched)
        assertTrue(lines.contains("LOCATION:Somewhere else"), patched)
        assertTrue(lines.contains(structuredLocation), "the structured location is not updated (stale):\n$patched")
    }

    // ========== Review fix: split with a UTC-form RECURRENCE-ID ==========

    @Test
    fun `a split at a UTC-form RECURRENCE-ID on a TZID series keeps the continuing series' last occurrence`() {
        val ny = calendar("""
            BEGIN:VEVENT
            UID:ny-split@test
            DTSTAMP:20260101T000000Z
            DTSTART;TZID=America/New_York:20260101T090000
            DTEND;TZID=America/New_York:20260101T093000
            RRULE:FREQ=DAILY;UNTIL=20260630T130000Z
            SUMMARY:NY daily
            END:VEVENT
        """.trimIndent())
        // 2026-01-10 09:00 EST is 14:00Z; move the continuing series to 10:00 New York.
        val split = patcher.splitSeries(ny, recurrenceId = "20260110T140000Z",
            startTime = "2026-01-10T10:00:00", endTime = "2026-01-10T10:30:00", timezone = "America/New_York")
        val last = parser.parseOccurrences(split.newSeries, java.time.Instant.parse("2026-06-29T00:00:00Z"),
            java.time.Instant.parse("2026-07-02T00:00:00Z")).map { it.startTime }
        assertEquals(listOf("2026-06-29T14:00:00Z", "2026-06-30T14:00:00Z"), last, "the series still ends on Jun 30 at 10:00 EDT")
    }

    @Test
    fun `patch turns a date rdate on a one-per-day timed series into that day's occurrence`() {
        val patched = patcher.patch(existingIcs = nyDaily, uid = "ny@test", rdates = listOf("2026-03-20"))
        assertTrue(patched.contains("RDATE;TZID=America/New_York:20260320T090000"), patched)
    }

    @Test
    fun `a truncated New York series moved across DST or to UTC keeps its last occurrence`() {
        // Split at Mar 12 (after the Mar 8 DST change): the original keeps Mar 1 to Mar 11.
        val split = patcher.splitSeries(nyDaily, recurrenceId = "20260312T090000", summary = "Continuing")
        val window = java.time.Instant.parse("2026-02-28T00:00:00Z") to java.time.Instant.parse("2026-03-20T00:00:00Z")
        fun starts(ics: String) = parser.parseOccurrences(ics, window.first, window.second).map { it.startTime!! }

        val later = patcher.patch(split.truncatedMaster, "ny@test", startTime = "2026-03-01T10:00:00",
            endTime = "2026-03-01T10:30:00", timezone = "America/New_York")
        val laterStarts = starts(later)
        assertEquals(11, laterStarts.size, "Mar 1 to Mar 11: $laterStarts")
        assertEquals("2026-03-01T15:00:00Z", laterStarts.first(), "10:00 EST")
        assertEquals("2026-03-11T14:00:00Z", laterStarts.last(), "10:00 EDT after the DST change")

        val toUtc = patcher.patch(split.truncatedMaster, "ny@test", startTime = "2026-03-01T15:00:00Z",
            endTime = "2026-03-01T15:30:00Z")
        val utcStarts = starts(toUtc)
        assertEquals(11, utcStarts.size, "Mar 1 to Mar 11: $utcStarts")
        assertEquals("2026-03-11T15:00:00Z", utcStarts.last())
    }

    // ========== Mutation-audit gaps: series cuts carry or drop each exception ==========

    private fun override(day: String, summary: String) = listOf(
        "BEGIN:VEVENT", "UID:cut@test", "DTSTAMP:20260101T000000Z", "RECURRENCE-ID:${day}T090000Z",
        "DTSTART:${day}T110000Z", "DTEND:${day}T113000Z", "SUMMARY:$summary", "END:VEVENT"
    ).joinToString("\r\n")

    /** Daily at 09:00 UTC from 03-01, with an EXDATE, an RDATE and an override on each side of 03-06. */
    private val cutSeries = calendar(
        listOf(
            "BEGIN:VEVENT", "UID:cut@test", "DTSTAMP:20260101T000000Z", "SEQUENCE:2",
            "DTSTART:20260301T090000Z", "DTEND:20260301T093000Z", "RRULE:FREQ=DAILY;COUNT=10",
            "EXDATE:20260303T090000Z", "EXDATE:20260308T090000Z",
            "RDATE:20260304T150000Z", "RDATE:20260309T150000Z", "SUMMARY:Daily", "END:VEVENT"
        ).joinToString("\r\n"),
        override("20260302", "Early edit"),
        override("20260307", "Late edit"),
    )

    private fun List<String>.values(name: String) =
        filter { it.startsWith("$name:") || it.startsWith("$name;") }.flatMap { it.substringAfter(':').split(',') }

    private fun assertTruncatedMaster(ics: String) {
        val blocks = componentBlocks(ics)
        val master = blocks.single { b -> b.none { it.startsWith("RECURRENCE-ID") } }
        assertEquals(2, blocks.size, "master plus the earlier override:\n$ics")
        assertTrue(master.contains("SEQUENCE:3"), "SEQUENCE goes up by one:\n$ics")
        assertTrue(master.values("RRULE").single().contains("UNTIL=20260305T090000Z"), ics)
        assertEquals(listOf("20260303T090000Z"), master.values("EXDATE"), "only the earlier EXDATE stays")
        assertEquals(listOf("20260304T150000Z"), master.values("RDATE"), "only the earlier RDATE stays")
        val kept = blocks.single { b -> b.any { it.startsWith("RECURRENCE-ID") } }
        assertTrue(kept.contains("SUMMARY:Early edit"), ics)
    }

    @Test
    fun `truncateSeries keeps the exceptions before the cut and drops the later ones`() {
        assertTruncatedMaster(patcher.truncateSeries(cutSeries, "20260306T090000Z"))
    }

    @Test
    fun `splitSeries carries the later exceptions onto the new series`() {
        val split = patcher.splitSeries(cutSeries, "20260306T090000Z", summary = "Renamed")
        assertTruncatedMaster(split.truncatedMaster)

        val blocks = componentBlocks(split.newSeries)
        assertEquals(2, blocks.size, "new master plus the later override:\n${split.newSeries}")
        val master = blocks.single { b -> b.none { it.startsWith("RECURRENCE-ID") } }
        assertTrue(master.contains("DTSTART:20260306T090000Z"), split.newSeries)
        assertTrue(master.contains("DTEND:20260306T093000Z"), "DTEND keeps the master's duration")
        assertTrue(master.contains("SUMMARY:Renamed"))
        assertEquals(listOf("20260308T090000Z"), master.values("EXDATE"), "the later EXDATE moves over")
        assertEquals(listOf("20260309T150000Z"), master.values("RDATE"), "the later RDATE moves over")
        val newUid = master.single { it.startsWith("UID:") }
        assertNotEquals("UID:cut@test", newUid)
        val carried = blocks.single { b -> b.any { it.startsWith("RECURRENCE-ID") } }
        assertTrue(carried.contains("SUMMARY:Late edit"))
        assertTrue(carried.contains(newUid), "the carried override takes the new UID")
    }

    @Test
    fun `exdateOccurrence bumps SEQUENCE and does not repeat an EXDATE`() {
        val once = patcher.exdateOccurrence(cutSeries, "20260305T090000Z")
        val master = componentBlocks(once).single { b -> b.none { it.startsWith("RECURRENCE-ID") } }
        assertTrue(master.contains("SEQUENCE:3"), once)
        val twice = patcher.exdateOccurrence(once, "20260305T090000Z")
        val again = componentBlocks(twice).single { b -> b.none { it.startsWith("RECURRENCE-ID") } }
        assertEquals(1, again.values("EXDATE").count { it == "20260305T090000Z" }, twice)
    }

    @Test
    fun `patchOccurrence with only a summary ends the exception after the master's duration`() {
        val patched = patcher.patchOccurrence(weeklyTimedSeries, "20260202T090000Z", summary = "Renamed")
        val exception = componentBlocks(patched).single { b -> b.any { it.startsWith("RECURRENCE-ID") } }
        assertTrue(exception.contains("DTEND:20260202T100000Z"), patched)
    }
}
