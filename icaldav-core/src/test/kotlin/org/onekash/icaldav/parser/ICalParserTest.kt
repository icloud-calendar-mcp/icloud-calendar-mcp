package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ParseResult

class ICalParserTest {

    private val parser = ICalParser()

    @Test
    fun `parse simple event`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:test-event-123
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Test Event
            DESCRIPTION:This is a test description
            LOCATION:Test Location
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()
        assertNotNull(events)
        assertEquals(1, events!!.size)

        val event = events[0]
        assertEquals("test-event-123", event.uid)
        assertEquals("test-event-123", event.importId)
        assertEquals("Test Event", event.summary)
        assertEquals("This is a test description", event.description)
        assertEquals("Test Location", event.location)
        assertFalse(event.isAllDay)
    }

    @Test
    fun `parse all-day event`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:allday-event-123
            DTSTAMP:20231215T100000Z
            DTSTART;VALUE=DATE:20231215
            DTEND;VALUE=DATE:20231216
            SUMMARY:All Day Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        assertEquals(1, events.size)

        val event = events[0]
        assertTrue(event.isAllDay)
        assertEquals("20231215", event.dtStart.toDayCode())
    }

    @Test
    fun `parse event with RRULE`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-event-123
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            DTEND:20231215T150000Z
            SUMMARY:Weekly Meeting
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        val event = events[0]

        assertNotNull(event.rrule)
        assertEquals(Frequency.WEEKLY, event.rrule!!.freq)
        assertEquals(10, event.rrule!!.count)
        assertNotNull(event.rrule!!.byDay)
        assertEquals(3, event.rrule!!.byDay!!.size)
    }

    @Test
    fun `parse event with RECURRENCE-ID creates unique importId`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-123
            DTSTAMP:20231215T100000Z
            RECURRENCE-ID:20231208T140000Z
            DTSTART:20231209T150000Z
            DTEND:20231209T160000Z
            SUMMARY:Modified Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        val event = events[0]

        assertEquals("recurring-123", event.uid)
        // importId should include RECURRENCE-ID
        assertTrue(event.importId.contains("RECID"))
        assertTrue(event.importId.contains("20231208"))
        assertNotNull(event.recurrenceId)
    }

    @Test
    fun `parse event with EXDATE`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring-123
            DTSTAMP:20231215T100000Z
            DTSTART:20231201T140000Z
            RRULE:FREQ=DAILY;COUNT=30
            EXDATE:20231208T140000Z,20231215T140000Z
            SUMMARY:Daily Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        val event = events[0]

        assertEquals(2, event.exdates.size)
    }

    @Test
    fun `parse event with VALARM`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:event-with-alarm
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            SUMMARY:Meeting with Reminder
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Event reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        val event = events[0]

        assertEquals(1, event.alarms.size)
        val alarm = event.alarms[0]
        assertEquals(AlarmAction.DISPLAY, alarm.action)
        assertNotNull(alarm.trigger)
        assertEquals(-15, alarm.trigger!!.toMinutes())
    }

    @Test
    fun `parse multiple events in single file`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:event-1
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            SUMMARY:Event One
            END:VEVENT
            BEGIN:VEVENT
            UID:event-2
            DTSTAMP:20231215T100000Z
            DTSTART:20231216T140000Z
            SUMMARY:Event Two
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val events = result.getOrNull()!!
        assertEquals(2, events.size)
        assertEquals("event-1", events[0].uid)
        assertEquals("event-2", events[1].uid)
    }

    @Test
    fun `extractUid returns correct UID`() {
        val icalData = """
            BEGIN:VEVENT
            UID:simple-uid-123
            SUMMARY:Test
            END:VEVENT
        """.trimIndent()

        val uid = parser.extractUid(icalData)
        assertEquals("simple-uid-123", uid)
    }

    @Test
    fun `parse escaped text correctly`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:escaped-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            SUMMARY:Meeting\, Important
            DESCRIPTION:Line 1\nLine 2\nLine 3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals("Meeting, Important", event.summary)
        assertTrue(event.description!!.contains("\n"))
    }

    // ============ PRIORITY Tests ============

    @Test
    fun `parse event with PRIORITY`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:priority-event-123
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            PRIORITY:1
            SUMMARY:High Priority Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals(1, event.priority)
    }

    @Test
    fun `parse event with PRIORITY 9 (lowest)`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:low-priority-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            PRIORITY:9
            SUMMARY:Low Priority Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals(9, event.priority)
    }

    @Test
    fun `parse event without PRIORITY defaults to 0`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-priority-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            SUMMARY:No Priority Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals(0, event.priority)
    }

    @Test
    fun `parse event with invalid PRIORITY coerces to valid range`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:invalid-priority-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            PRIORITY:15
            SUMMARY:Invalid Priority Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals(9, event.priority) // Coerced to max valid value
    }

    // ============ GEO Tests ============

    @Test
    fun `parse event with GEO`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:geo-event-123
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            GEO:37.386013;-122.082932
            SUMMARY:Event at Apple Park
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals("37.386013;-122.082932", event.geo)
    }

    @Test
    fun `parse event without GEO defaults to null`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:no-geo-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            SUMMARY:No Location Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertNull(event.geo)
    }

    @Test
    fun `parse event with negative GEO coordinates`() {
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:negative-geo-event
            DTSTAMP:20231215T100000Z
            DTSTART:20231215T140000Z
            GEO:-33.8688;151.2093
            SUMMARY:Event in Sydney
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = parser.parseAllEvents(icalData)

        assertTrue(result is ParseResult.Success)
        val event = result.getOrNull()!![0]
        assertEquals("-33.8688;151.2093", event.geo)
    }

    // ========== TEXT escapes and blank UID ==========

    private fun parseOne(vevent: String): org.onekash.icaldav.model.ICalEvent {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\nBEGIN:VEVENT\r\n" +
            vevent.trimIndent().replace("\n", "\r\n") + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        return (parser.parseAllEvents(ics) as ParseResult.Success).value.single()
    }

    @Test
    fun `an uppercase N escape decodes to a newline in SUMMARY, DESCRIPTION and LOCATION`() {
        val event = parseOne("""
            UID:upper-n@test
            DTSTART:20260301T090000Z
            SUMMARY:Line one\NLine two
            DESCRIPTION:A\NB\nC
            LOCATION:Room 1\NFloor 2
        """)
        assertEquals("Line one\nLine two", event.summary)
        assertEquals("A\nB\nC", event.description)
        assertEquals("Room 1\nFloor 2", event.location)
    }

    @Test
    fun `an escaped backslash followed by N stays a backslash and N`() {
        val event = parseOne("""
            UID:path@test
            DTSTART:20260301T090000Z
            SUMMARY:Path
            DESCRIPTION:C:\\Notes\, semi\; done
        """)
        assertEquals("C:\\Notes, semi; done", event.description)
    }

    @Test
    fun `a blank UID is treated like a missing one`() {
        val event = parseOne("""
            UID:
            DTSTART:20260301T090000Z
            SUMMARY:No UID
        """)
        assertTrue(event.uid.isNotBlank(), "a blank UID gets the missing-UID fallback: '${event.uid}'")
        val spaces = parseOne("UID:   \nDTSTART:20260301T090000Z\nSUMMARY:Spaces")
        assertTrue(spaces.uid.isNotBlank(), "a whitespace UID gets the missing-UID fallback: '${spaces.uid}'")
    }

    // ========== Review fixes: uppercase N scope, shared blank UID ==========

    @Test
    fun `an uppercase N outside TEXT values is left as written`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\nBEGIN:VEVENT\r\n" +
            "UID:WIN\\Name-123\r\nDTSTART:20260301T090000Z\r\nSUMMARY:One\\NTwo\r\n" +
            "ATTENDEE;CN=CORP\\Nina:mailto:nina@example.com\r\nURL:https://example.com/a\\Nb\r\n" +
            "X-MS-PATH:C:\\Notes\\New\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val event = (parser.parseAllEvents(ics) as ParseResult.Success).value.single()
        assertEquals("One\nTwo", event.summary, "TEXT values still decode \\N")
        assertEquals("WIN\\Name-123", event.uid)
        assertEquals("CORP\\Nina", event.attendees.single().name)
        // ical4j writes the URL's backslash as %5C; the N stays uppercase, so no newline was made.
        assertEquals("https://example.com/a%5CNb", event.url)
        assertEquals(listOf("X-MS-PATH:C:\\Notes\\New"), event.unknownPropertyLines)
    }

    @Test
    fun `VEVENTs with a blank UID in one resource share one stable fallback UID`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" +
            "BEGIN:VEVENT\r\nUID:\r\nDTSTART:20260301T090000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\nSUMMARY:Series\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:\r\nRECURRENCE-ID:20260302T090000Z\r\nDTSTART:20260302T100000Z\r\nSUMMARY:Moved\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        val first = (parser.parseAllEvents(ics) as ParseResult.Success).value
        val second = (parser.parseAllEvents(ics) as ParseResult.Success).value
        assertEquals(1, first.map { it.uid }.toSet().size, "master and override share the fallback UID")
        assertTrue(first[0].uid.isNotBlank())
        assertEquals(first.map { it.uid }, second.map { it.uid }, "the fallback UID is the same on every parse")
    }
}
