package org.onekash.mcp.calendar.ics

import kotlin.test.*

/**
 * Tests for IcsPatcher: verifies round-trip preservation when patching
 * existing ICS data and fresh generation for new events.
 *
 * Adapted from KashCal's IcsPatcherTest and IcsPatcherRfc5545Test patterns.
 */
class IcsPatcherTest {

    private val patcher = IcsPatcher()
    private val parser = IcsParser()

    // ========== VALARM Preservation (KashCal pattern: preserve alarms) ==========

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

    // ========== ATTENDEE/ORGANIZER Preservation (KashCal pattern) ==========

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

    // ========== X-* Property Preservation (KashCal pattern: rawProperties) ==========

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

    // ========== SEQUENCE Increment (KashCal pattern) ==========

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

    // ========== Fallback Tests (KashCal pattern) ==========

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
    fun `patch throws when existing ICS uses LF-only folding that ical4j rejects`() {
        // Reported via issue #2: when existing ICS came back from iCloud with
        // bare LF instead of CRLF, ical4j's CalendarBuilder bailed; the
        // pre-fix patcher silently returned a fresh "Untitled" event with the
        // user-supplied description-only update — corrupting the SUMMARY.
        // After this chunk, that case throws cleanly.
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

        assertFailsWith<IcsPatcher.UnparseableExistingIcsException> {
            patcher.patch(
                existingIcs = lfOnly,
                uid = "lf-fold@test",
                description = "MCP TEST touch simple."  // description-only update
            )
        }
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

    // ========== Round-Trip Fidelity (KashCal pattern) ==========

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
        assertTrue(event.rrule!!.contains("FREQ=WEEKLY"))
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
}
