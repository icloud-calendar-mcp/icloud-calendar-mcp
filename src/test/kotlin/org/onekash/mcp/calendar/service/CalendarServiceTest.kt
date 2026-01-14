package org.onekash.mcp.calendar.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.onekash.mcp.calendar.caldav.*

/**
 * Tests for CalendarService using mocked CalDavClient.
 *
 * Tests the orchestration layer that:
 * - Uses CalDavClient for HTTP operations
 * - Parses ICS content to domain objects
 * - Builds ICS content for creates/updates
 */
class CalendarServiceTest {

    private lateinit var mockClient: MockCalDavClient
    private lateinit var service: CalendarService

    @BeforeEach
    fun setup() {
        mockClient = MockCalDavClient()
        service = CalendarService(mockClient)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIST CALENDARS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `list calendars returns parsed calendars`() {
        mockClient.calendars = listOf(
            CalDavCalendar(
                id = "personal-123",
                href = "/caldav/personal/",
                url = "https://caldav.icloud.com/caldav/personal/",
                displayName = "Personal",
                color = "#FF5733",
                ctag = "ctag1",
                isReadOnly = false
            ),
            CalDavCalendar(
                id = "work-456",
                href = "/caldav/work/",
                url = "https://caldav.icloud.com/caldav/work/",
                displayName = "Work",
                color = "#3366FF",
                ctag = "ctag2",
                isReadOnly = true
            )
        )

        val result = service.listCalendars()

        assertTrue(result is ServiceResult.Success)
        val calendars = (result as ServiceResult.Success).data
        assertEquals(2, calendars.size)
        assertEquals("personal-123", calendars[0].id)
        assertEquals("Personal", calendars[0].name)
        assertEquals("#FF5733", calendars[0].color)
        assertFalse(calendars[0].readOnly)
        assertTrue(calendars[1].readOnly)
    }

    @Test
    fun `list calendars returns error on client failure`() {
        mockClient.listCalendarsResult = CalDavResult.Error(401, "Authentication failed")

        val result = service.listCalendars()

        assertTrue(result is ServiceResult.Error)
        val error = result as ServiceResult.Error
        assertEquals(401, error.code)
        assertTrue(error.message.contains("Authentication"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET EVENTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `get events returns parsed events`() {
        mockClient.calendars = listOf(
            CalDavCalendar(
                id = "personal-123",
                href = "/caldav/personal/",
                url = "https://caldav.icloud.com/caldav/personal/",
                displayName = "Personal",
                color = null,
                ctag = null,
                isReadOnly = false
            )
        )

        mockClient.eventsResponse = listOf(
            CalDavEvent(
                uid = "event-001",
                href = "/caldav/personal/event-001.ics",
                url = "https://caldav.icloud.com/caldav/personal/event-001.ics",
                etag = "\"etag1\"",
                icalData = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    BEGIN:VEVENT
                    UID:event-001
                    SUMMARY:Team Meeting
                    DTSTART:20250115T100000Z
                    DTEND:20250115T110000Z
                    LOCATION:Room A
                    DESCRIPTION:Discuss Q1 goals
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()
            )
        )

        val result = service.getEvents("personal-123", "2025-01-15", "2025-01-15")

        assertTrue(result is ServiceResult.Success)
        val events = (result as ServiceResult.Success).data
        assertEquals(1, events.size)
        assertEquals("event-001", events[0].uid)
        assertEquals("Team Meeting", events[0].summary)
        assertEquals("2025-01-15T10:00:00Z", events[0].startTime)
        assertEquals("2025-01-15T11:00:00Z", events[0].endTime)
        assertEquals("Room A", events[0].location)
        assertEquals("Discuss Q1 goals", events[0].description)
    }

    @Test
    fun `get events handles all-day events`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        mockClient.eventsResponse = listOf(
            CalDavEvent(
                uid = "allday-001",
                href = "/cal/allday.ics",
                url = "https://test.com/cal/allday.ics",
                etag = "\"etag-allday\"",
                icalData = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    BEGIN:VEVENT
                    UID:allday-001
                    SUMMARY:Holiday
                    DTSTART;VALUE=DATE:20250115
                    DTEND;VALUE=DATE:20250116
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()
            )
        )

        val result = service.getEvents("cal-1", "2025-01-15", "2025-01-15")

        assertTrue(result is ServiceResult.Success)
        val events = (result as ServiceResult.Success).data
        assertEquals(1, events.size)
        assertTrue(events[0].isAllDay)
        assertEquals("2025-01-15", events[0].startDate)
        assertEquals("2025-01-15", events[0].endDate)  // Inclusive
    }

    @Test
    fun `get events filters out unparseable events`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        mockClient.eventsResponse = listOf(
            CalDavEvent(
                uid = "good-001",
                href = "/cal/good.ics",
                url = "https://test.com/cal/good.ics",
                etag = "\"etag1\"",
                icalData = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    BEGIN:VEVENT
                    UID:good-001
                    SUMMARY:Good Event
                    DTSTART:20250115T100000Z
                    DTEND:20250115T110000Z
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()
            ),
            CalDavEvent(
                uid = "bad-001",
                href = "/cal/bad.ics",
                url = "https://test.com/cal/bad.ics",
                etag = "\"etag2\"",
                icalData = "INVALID ICS CONTENT"
            )
        )

        val result = service.getEvents("cal-1", "2025-01-15", "2025-01-15")

        assertTrue(result is ServiceResult.Success)
        val events = (result as ServiceResult.Success).data
        assertEquals(1, events.size)
        assertEquals("good-001", events[0].uid)
    }

    @Test
    fun `get events returns error for unknown calendar`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        val result = service.getEvents("unknown-calendar", "2025-01-15", "2025-01-15")

        assertTrue(result is ServiceResult.Error)
        val error = result as ServiceResult.Error
        assertEquals(404, error.code)
        assertTrue(error.message.contains("not found"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CREATE EVENT
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `create event returns success with event ID`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        val result = service.createEvent(
            calendarId = "cal-1",
            summary = "New Meeting",
            startTime = "2025-01-15T14:00:00Z",
            endTime = "2025-01-15T15:00:00Z",
            location = "Room B",
            description = "Planning session"
        )

        assertTrue(result is ServiceResult.Success)
        val createdEvent = (result as ServiceResult.Success).data
        assertNotNull(createdEvent.uid)
        assertEquals("New Meeting", createdEvent.summary)

        // Verify ICS was built and sent to client
        assertNotNull(mockClient.lastCreatedIcs)
        assertTrue(mockClient.lastCreatedIcs!!.contains("SUMMARY:New Meeting"))
        assertTrue(mockClient.lastCreatedIcs!!.contains("LOCATION:Room B"))
    }

    @Test
    fun `create all-day event`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        val result = service.createEvent(
            calendarId = "cal-1",
            summary = "Holiday",
            startDate = "2025-01-15",
            endDate = "2025-01-15",
            isAllDay = true
        )

        assertTrue(result is ServiceResult.Success)
        val createdEvent = (result as ServiceResult.Success).data
        assertTrue(createdEvent.isAllDay)
        assertEquals("2025-01-15", createdEvent.startDate)

        // Verify ICS has DATE format
        assertTrue(mockClient.lastCreatedIcs!!.contains("DTSTART;VALUE=DATE:20250115"))
    }

    @Test
    fun `create event fails on read-only calendar`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, isReadOnly = true)
        )

        val result = service.createEvent(
            calendarId = "cal-1",
            summary = "Test",
            startTime = "2025-01-15T10:00:00Z",
            endTime = "2025-01-15T11:00:00Z"
        )

        assertTrue(result is ServiceResult.Error)
        val error = result as ServiceResult.Error
        assertEquals(403, error.code)
        assertTrue(error.message.contains("read-only"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UPDATE EVENT
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `update event modifies existing event`() {
        // Setup: calendar and event must exist
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        val existingIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-001
            SUMMARY:Old Title
            DTSTART:20250115T100000Z
            DTEND:20250115T110000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Set event in eventsResponse so getEvents() finds it
        val existingEvent = CalDavEvent(
            uid = "event-001",
            href = "/cal/event-001.ics",
            url = "https://test.com/cal/event-001.ics",
            etag = "\"old-etag\"",
            icalData = existingIcs
        )
        mockClient.eventsResponse = listOf(existingEvent)
        mockClient.registeredEvents["event-001"] = existingEvent

        // First, fetch events to populate the cache
        service.getEvents("cal-1", "2025-01-15", "2025-01-15")

        // Now update
        val result = service.updateEvent(
            eventId = "event-001",
            summary = "New Title",
            location = "New Location"
        )

        assertTrue(result is ServiceResult.Success)

        // Verify updated ICS was sent
        assertNotNull(mockClient.lastUpdatedIcs)
        assertTrue(mockClient.lastUpdatedIcs!!.contains("SUMMARY:New Title"))
        assertTrue(mockClient.lastUpdatedIcs!!.contains("LOCATION:New Location"))
    }

    @Test
    fun `update event returns error for unknown event`() {
        val result = service.updateEvent(
            eventId = "unknown-event",
            summary = "Test"
        )

        assertTrue(result is ServiceResult.Error)
        val error = result as ServiceResult.Error
        assertEquals(404, error.code)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DELETE EVENT
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `delete event removes existing event`() {
        // Setup: calendar and event must exist
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        val existingEvent = CalDavEvent(
            uid = "event-001",
            href = "/cal/event-001.ics",
            url = "https://test.com/cal/event-001.ics",
            etag = "\"etag1\"",
            icalData = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:event-001
                SUMMARY:Test Event
                DTSTART:20250115T100000Z
                DTEND:20250115T110000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()
        )
        mockClient.eventsResponse = listOf(existingEvent)
        mockClient.registeredEvents["event-001"] = existingEvent

        // Populate cache
        service.getEvents("cal-1", "2025-01-15", "2025-01-15")

        val result = service.deleteEvent("event-001")

        assertTrue(result is ServiceResult.Success)
        assertEquals("/cal/event-001.ics", mockClient.lastDeletedHref)
    }

    @Test
    fun `delete event returns error for unknown event`() {
        val result = service.deleteEvent("unknown-event")

        assertTrue(result is ServiceResult.Error)
        val error = result as ServiceResult.Error
        assertEquals(404, error.code)
    }

    @Test
    fun `delete event handles client error`() {
        // Setup: calendar and event must exist
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        val existingEvent = CalDavEvent(
            uid = "event-001",
            href = "/cal/event-001.ics",
            url = "https://test.com/cal/event-001.ics",
            etag = "\"etag1\"",
            icalData = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:event-001
                SUMMARY:Test Event
                DTSTART:20250115T100000Z
                DTEND:20250115T110000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()
        )
        mockClient.eventsResponse = listOf(existingEvent)
        mockClient.registeredEvents["event-001"] = existingEvent

        // Populate cache
        service.getEvents("cal-1", "2025-01-15", "2025-01-15")

        mockClient.deleteEventResult = CalDavResult.Error(412, "Precondition failed")

        val result = service.deleteEvent("event-001")

        assertTrue(result is ServiceResult.Error)
        val error = result as ServiceResult.Error
        assertEquals(412, error.code)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EVENT LOOKUP
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `find event by ID after fetching events`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal-1/", "https://test.com/cal-1/", "Cal 1", null, null, false),
            CalDavCalendar("cal-2", "/cal-2/", "https://test.com/cal-2/", "Cal 2", null, null, false)
        )

        val event = CalDavEvent(
            uid = "event-in-cal2",
            href = "/cal-2/event.ics",
            url = "https://test.com/cal-2/event.ics",
            etag = "\"etag\"",
            icalData = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:event-in-cal2
                SUMMARY:Found Event
                DTSTART:20250115T100000Z
                DTEND:20250115T110000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()
        )
        mockClient.eventsResponse = listOf(event)
        mockClient.registeredEvents["event-in-cal2"] = event

        // Fetch events from cal-2 to populate the cache
        service.getEvents("cal-2", "2025-01-15", "2025-01-15")

        // Now lookup by ID
        val result = service.getEventById("event-in-cal2")

        assertTrue(result is ServiceResult.Success)
        val foundEvent = (result as ServiceResult.Success).data
        assertEquals("event-in-cal2", foundEvent.uid)
        assertEquals("Found Event", foundEvent.summary)
    }

    @Test
    fun `find event by ID returns error if not in cache`() {
        val result = service.getEventById("unknown-event")

        assertTrue(result is ServiceResult.Error)
        val error = result as ServiceResult.Error
        assertEquals(404, error.code)
    }
}

/**
 * Mock CalDavClient for testing CalendarService.
 */
class MockCalDavClient : CalDavClient {
    var calendars: List<CalDavCalendar> = emptyList()
    var listCalendarsResult: CalDavResult<List<CalDavCalendar>>? = null
    var eventsResponse: List<CalDavEvent> = emptyList()
    var registeredEvents: MutableMap<String, CalDavEvent> = mutableMapOf()

    var lastCreatedIcs: String? = null
    var lastUpdatedIcs: String? = null
    var lastDeletedHref: String? = null

    var deleteEventResult: CalDavResult<Unit>? = null

    override fun listCalendars(): CalDavResult<List<CalDavCalendar>> {
        return listCalendarsResult ?: CalDavResult.Success(calendars)
    }

    override fun getEvents(calendarId: String, startDate: String, endDate: String): CalDavResult<List<CalDavEvent>> {
        val calendar = calendars.find { it.id == calendarId }
            ?: return CalDavResult.Error(404, "Calendar not found: $calendarId")
        return CalDavResult.Success(eventsResponse)
    }

    override fun createEvent(calendarId: String, icalData: String): CalDavResult<CalDavEvent> {
        lastCreatedIcs = icalData

        // Extract UID from ICS
        val uidMatch = Regex("UID:([^\r\n]+)").find(icalData)
        val uid = uidMatch?.groupValues?.get(1) ?: "generated-uid"

        val event = CalDavEvent(
            uid = uid,
            href = "/cal/$uid.ics",
            url = "https://test.com/cal/$uid.ics",
            etag = "\"new-etag\"",
            icalData = icalData
        )
        registeredEvents[uid] = event
        return CalDavResult.Success(event)
    }

    override fun updateEvent(href: String, icalData: String, etag: String?): CalDavResult<CalDavEvent> {
        lastUpdatedIcs = icalData

        // Find existing event
        val existing = registeredEvents.values.find { it.href == href }
            ?: return CalDavResult.Error(404, "Event not found")

        val updated = existing.copy(
            icalData = icalData,
            etag = "\"updated-etag\""
        )
        registeredEvents[existing.uid] = updated
        return CalDavResult.Success(updated)
    }

    override fun deleteEvent(href: String, etag: String?): CalDavResult<Unit> {
        lastDeletedHref = href

        if (deleteEventResult != null) {
            return deleteEventResult!!
        }

        val event = registeredEvents.values.find { it.href == href }
        if (event != null) {
            registeredEvents.remove(event.uid)
        }
        return CalDavResult.Success(Unit)
    }
}
