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

    // ── Handle-based resolution (cache-independent) ─────────────────────────

    @Test
    fun `update event resolves by handle from a COLD cache without a 404`() {
        // A fresh service that has never fetched this event — empty cache. The bare
        // UID path would 404; the handle carries the href so getEvent resolves it.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:cold-001
            SUMMARY:Cold Title
            DTSTART:20250115T100000Z
            DTEND:20250115T110000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val event = CalDavEvent(
            uid = "cold-001",
            href = "/cal/cold-001.ics",
            url = "https://test.com/cal/cold-001.ics",
            etag = "\"etag-cold\"",
            icalData = ics
        )
        // Server can resolve it via getEvent(href); cache stays empty.
        mockClient.registeredEvents["cold-001"] = event

        val handle = EventHandle.encode(event.href, event.etag)
        val result = service.updateEvent(eventId = handle, summary = "New Cold Title")

        assertTrue(result is ServiceResult.Success, "handle update should succeed from cold cache: $result")
        assertTrue(mockClient.getEventCallCount >= 1, "cold resolution must fetch via getEvent")
        assertNotNull(mockClient.lastUpdatedIcs)
        assertTrue(mockClient.lastUpdatedIcs!!.contains("SUMMARY:New Cold Title"))
    }

    @Test
    fun `delete event resolves by handle from a COLD cache without a 404`() {
        val event = CalDavEvent(
            uid = "cold-del-001",
            href = "/cal/cold-del-001.ics",
            url = "https://test.com/cal/cold-del-001.ics",
            etag = "\"etag-cd\"",
            icalData = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:cold-del-001\nEND:VEVENT\nEND:VCALENDAR"
        )
        mockClient.registeredEvents["cold-del-001"] = event

        val handle = EventHandle.encode(event.href, event.etag)
        val result = service.deleteEvent(handle)

        assertTrue(result is ServiceResult.Success, "handle delete should succeed from cold cache: $result")
        assertEquals("/cal/cold-del-001.ics", mockClient.lastDeletedHref)
        // Delete uses the etag straight from the handle — no getEvent needed on the happy path.
        assertEquals("\"etag-cd\"", mockClient.deleteEtagsSeen.first())
    }

    @Test
    fun `handle update sends the handle's etag as If-Match, not the refetched one`() {
        // Optimistic concurrency: the handle's etag is the version the caller last
        // saw. Even though resolveForWrite fetches the current body to patch onto,
        // the first PUT must carry the HANDLE's etag so a concurrent edit trips a
        // 412 — otherwise the caller silently overwrites a change they never saw.
        val serverEvent = CalDavEvent(
            uid = "oc-001",
            href = "/cal/oc-001.ics",
            url = "https://test.com/cal/oc-001.ics",
            etag = "\"server-now\"", // the CURRENT server etag (advanced since minting)
            icalData = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:oc-001
                SUMMARY:Original
                DTSTART:20250115T100000Z
                DTEND:20250115T110000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()
        )
        mockClient.registeredEvents["oc-001"] = serverEvent

        // Handle was minted earlier, carrying the etag the caller last observed.
        val handle = EventHandle.encode(serverEvent.href, "\"caller-saw\"")
        val result = service.updateEvent(eventId = handle, summary = "Edited")

        assertTrue(result is ServiceResult.Success, "update should succeed: $result")
        assertEquals("\"caller-saw\"", mockClient.updateEtagsSeen.first(),
            "first PUT must carry the handle's etag, not the refetched server etag")
    }

    @Test
    fun `handle update falls back to the fetched etag when the handle carried none`() {
        val serverEvent = CalDavEvent(
            uid = "oc-002",
            href = "/cal/oc-002.ics",
            url = "https://test.com/cal/oc-002.ics",
            etag = "\"server-etag\"",
            icalData = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:oc-002\n" +
                "SUMMARY:S\nDTSTART:20250115T100000Z\nDTEND:20250115T110000Z\nEND:VEVENT\nEND:VCALENDAR"
        )
        mockClient.registeredEvents["oc-002"] = serverEvent

        val handle = EventHandle.encode(serverEvent.href, null) // no etag in the handle
        val result = service.updateEvent(eventId = handle, summary = "Edited")

        assertTrue(result is ServiceResult.Success, "update should succeed: $result")
        assertEquals("\"server-etag\"", mockClient.updateEtagsSeen.first(),
            "with no handle etag, the first PUT uses the freshly fetched etag")
    }

    // ── 412 refetch-and-retry-once ──────────────────────────────────────────

    @Test
    fun `update recovers from a stale-etag 412 by refetching and retrying once`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:retry-001
            SUMMARY:Retry Title
            DTSTART:20250115T100000Z
            DTEND:20250115T110000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val staleEvent = CalDavEvent(
            uid = "retry-001",
            href = "/cal/retry-001.ics",
            url = "https://test.com/cal/retry-001.ics",
            etag = "\"stale-etag\"",
            icalData = ics
        )
        // Warm the cache with the STALE etag via the legacy uid path. (The handle
        // path refetches before the first PUT, so only the uid/cache path can carry
        // a stale etag into the first attempt — which is exactly the reported bug.)
        mockClient.eventsResponse = listOf(staleEvent)
        mockClient.registeredEvents["retry-001"] = staleEvent
        service.getEvents("cal-1", "2025-01-15", "2025-01-15")

        // getEvent (the refetch on 412) returns the CURRENT server state, fresh etag.
        val freshEvent = staleEvent.copy(etag = "\"fresh-etag\"")
        mockClient.getEventResult = CalDavResult.Success(freshEvent)

        // First PUT (with stale cached etag) 412s; the retry (after refetch) succeeds.
        mockClient.fail412UpdatesRemaining = 1

        val result = service.updateEvent(eventId = "retry-001", summary = "Reconciled")

        assertTrue(result is ServiceResult.Success, "should recover from 412: $result")
        assertEquals(2, mockClient.updateEventCallCount, "exactly one retry after the 412")
        // First attempt carries the stale cached etag; retry carries the refetched one.
        assertEquals("\"stale-etag\"", mockClient.updateEtagsSeen[0])
        assertEquals("\"fresh-etag\"", mockClient.updateEtagsSeen[1])
    }

    @Test
    fun `update does not retry more than once on repeated 412`() {
        val event = CalDavEvent(
            uid = "retry-002",
            href = "/cal/retry-002.ics",
            url = "https://test.com/cal/retry-002.ics",
            etag = "\"e\"",
            icalData = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:retry-002\nDTSTART:20250115T100000Z\nDTEND:20250115T110000Z\nEND:VEVENT\nEND:VCALENDAR"
        )
        mockClient.registeredEvents["retry-002"] = event
        mockClient.getEventResult = CalDavResult.Success(event)
        // Both the first PUT and the single retry 412 — must surface the conflict, not loop.
        mockClient.fail412UpdatesRemaining = 5
        val handle = EventHandle.encode(event.href, event.etag)

        val result = service.updateEvent(eventId = handle, summary = "X")

        assertTrue(result is ServiceResult.Error)
        assertEquals(412, (result as ServiceResult.Error).code)
        assertEquals(2, mockClient.updateEventCallCount, "one initial + exactly one retry, then give up")
    }

    @Test
    fun `delete recovers from a stale-etag 412 by refetching and retrying once`() {
        val event = CalDavEvent(
            uid = "retry-del-001",
            href = "/cal/retry-del-001.ics",
            url = "https://test.com/cal/retry-del-001.ics",
            etag = "\"stale\"",
            icalData = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:retry-del-001\nEND:VEVENT\nEND:VCALENDAR"
        )
        mockClient.registeredEvents["retry-del-001"] = event
        mockClient.getEventResult = CalDavResult.Success(event.copy(etag = "\"fresh\""))
        mockClient.fail412DeletesRemaining = 1
        val handle = EventHandle.encode(event.href, "\"stale\"")

        val result = service.deleteEvent(handle)

        assertTrue(result is ServiceResult.Success, "delete should recover from 412: $result")
        assertEquals(2, mockClient.deleteEventCallCount, "exactly one retry after the 412")
        assertEquals("\"stale\"", mockClient.deleteEtagsSeen[0])
        assertEquals("\"fresh\"", mockClient.deleteEtagsSeen[1])
    }

    // ── Cache eviction on handle-based delete (no resurrection) ──────────────

    @Test
    fun `handle-based delete evicts the UID-keyed cache entry`() {
        // Warm the cache the normal way (keyed by UID) via getEvents.
        val event = CalDavEvent(
            uid = "evict-001",
            href = "/cal/evict-001.ics",
            url = "https://test.com/cal/evict-001.ics",
            etag = "\"e-evict\"",
            icalData = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:evict-001\nEND:VEVENT\nEND:VCALENDAR"
        )
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )
        mockClient.eventsResponse = listOf(event)
        mockClient.registeredEvents["evict-001"] = event
        service.getEvents("cal-1", "2025-01-15", "2025-01-15")
        assertEquals(1, service.cacheSize(), "getEvents should have cached the event by UID")

        // Delete by HANDLE (not the UID). Before the fix this evicted nothing because
        // removeFromCache used the handle string, leaving the stale UID entry behind.
        val handle = EventHandle.encode(event.href, event.etag)
        val result = service.deleteEvent(handle)

        assertTrue(result is ServiceResult.Success, "handle delete should succeed: $result")
        assertEquals(0, service.cacheSize(),
            "handle-based delete must evict the UID-keyed entry to prevent PUT-recreate resurrection")
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

    // ═══════════════════════════════════════════════════════════════════════
    // CONNECTION VALIDATION (Chunk 4)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `listCalendars validates connection on first call`() {
        mockClient.checkConnectionResult = CalDavResult.Error(400,
            "Server does not support CalDAV")

        val result = service.listCalendars()

        assertTrue(result is ServiceResult.Error)
        val error = result as ServiceResult.Error
        assertEquals(400, error.code)
        assertTrue(error.message.contains("CalDAV"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // E2E: PROPERTY PRESERVATION (Chunk 24 - IcsPatcher integration)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `update event preserves VALARM and ATTENDEE via IcsPatcher`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        // Event with VALARM, ATTENDEE, ORGANIZER, X-APPLE-* props
        val richIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:rich-event-001
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Team Meeting
            DESCRIPTION:Weekly sync
            LOCATION:Room A
            ORGANIZER;CN=Boss:mailto:boss@example.com
            ATTENDEE;CN=Alice:mailto:alice@example.com
            ATTENDEE;CN=Bob:mailto:bob@example.com
            X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 min reminder
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val existingEvent = CalDavEvent(
            uid = "rich-event-001",
            href = "/cal/rich-event-001.ics",
            url = "https://test.com/cal/rich-event-001.ics",
            etag = "\"etag1\"",
            icalData = richIcs
        )
        mockClient.eventsResponse = listOf(existingEvent)
        mockClient.registeredEvents["rich-event-001"] = existingEvent

        // Populate cache
        service.getEvents("cal-1", "2025-12-25", "2025-12-25")

        // Update only the summary - everything else should be preserved
        val result = service.updateEvent(
            eventId = "rich-event-001",
            summary = "Updated Meeting"
        )

        assertTrue(result is ServiceResult.Success)

        // Verify the updated ICS preserves everything
        val updatedIcs = mockClient.lastUpdatedIcs!!
        assertTrue(updatedIcs.contains("SUMMARY:Updated Meeting"), "Title updated")
        assertTrue(updatedIcs.contains("boss@example.com"), "Organizer preserved")
        assertTrue(updatedIcs.contains("alice@example.com"), "Attendee Alice preserved")
        assertTrue(updatedIcs.contains("bob@example.com"), "Attendee Bob preserved")
        assertTrue(updatedIcs.contains("X-APPLE-TRAVEL-ADVISORY"), "X-APPLE prop preserved")

        // VALARM blocks preserved
        val alarmCount = updatedIcs.split("BEGIN:VALARM").size - 1
        assertEquals(2, alarmCount, "Both VALARM blocks preserved")
        assertTrue(updatedIcs.contains("TRIGGER:-PT15M"), "15 min alarm preserved")
        assertTrue(updatedIcs.contains("TRIGGER:-PT1H"), "1 hour alarm preserved")

        // SEQUENCE incremented
        assertTrue(updatedIcs.contains("SEQUENCE:1"), "SEQUENCE incremented")
    }

    @Test
    fun `update event preserves properties through multiple updates`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:multi-update-001
            DTSTAMP:20251220T100000Z
            DTSTART:20251225T100000Z
            DTEND:20251225T110000Z
            SUMMARY:Original
            ORGANIZER;CN=John:mailto:john@example.com
            ATTENDEE;CN=Jane:mailto:jane@example.com
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val existingEvent = CalDavEvent(
            uid = "multi-update-001",
            href = "/cal/multi-update-001.ics",
            url = "https://test.com/cal/multi-update-001.ics",
            etag = "\"etag1\"",
            icalData = originalIcs
        )
        mockClient.eventsResponse = listOf(existingEvent)
        mockClient.registeredEvents["multi-update-001"] = existingEvent

        service.getEvents("cal-1", "2025-12-25", "2025-12-25")

        // First update: change title
        service.updateEvent(eventId = "multi-update-001", summary = "V2")
        val v2Ics = mockClient.lastUpdatedIcs!!
        assertTrue(v2Ics.contains("SUMMARY:V2"))
        assertTrue(v2Ics.contains("jane@example.com"), "Attendee survives 1st update")
        assertTrue(v2Ics.contains("BEGIN:VALARM"), "VALARM survives 1st update")

        // Refresh cache with v2 ICS (mock client returns what was sent)
        service.clearCache()
        mockClient.eventsResponse = listOf(
            CalDavEvent("multi-update-001", "/cal/multi-update-001.ics",
                "https://test.com/cal/multi-update-001.ics", "\"etag2\"", v2Ics)
        )
        mockClient.registeredEvents["multi-update-001"] = mockClient.eventsResponse[0]
        service.getEvents("cal-1", "2025-12-25", "2025-12-25")

        // Second update: add location
        service.updateEvent(eventId = "multi-update-001", location = "Room 42")
        val v3Ics = mockClient.lastUpdatedIcs!!
        assertTrue(v3Ics.contains("SUMMARY:V2"), "Title from v2 preserved")
        assertTrue(v3Ics.contains("LOCATION:Room 42"), "Location added")
        assertTrue(v3Ics.contains("jane@example.com"), "Attendee survives 2nd update")
        assertTrue(v3Ics.contains("BEGIN:VALARM"), "VALARM survives 2nd update")
        assertTrue(v3Ics.contains("SEQUENCE:2"), "SEQUENCE incremented twice")
    }

    @Test
    fun `full CRUD flow with extended fields`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        // 1. Create event with timezone and rrule
        val createResult = service.createEvent(
            calendarId = "cal-1",
            summary = "Weekly Standup",
            startTime = "2025-12-25T10:00:00Z",
            endTime = "2025-12-25T11:00:00Z",
            description = "Team sync",
            location = "Zoom",
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )

        assertTrue(createResult is ServiceResult.Success)
        val created = (createResult as ServiceResult.Success).data
        assertEquals("Weekly Standup", created.summary)
        assertNotNull(created.uid)

        // Verify ICS has RRULE
        val createdIcs = mockClient.lastCreatedIcs!!
        assertTrue(createdIcs.contains("RRULE:FREQ=WEEKLY"), "RRULE in created ICS")

        // 2. Get events
        val eventUid = created.uid
        val getResult = service.getEvents("cal-1", "2025-12-25", "2025-12-25")
        assertTrue(getResult is ServiceResult.Success)

        // 3. Get by ID
        val byIdResult = service.getEventById(eventUid)
        assertTrue(byIdResult is ServiceResult.Success)
        val fetched = (byIdResult as ServiceResult.Success).data
        assertEquals("Weekly Standup", fetched.summary)

        // 4. Update - only change title, preserve RRULE
        val updateResult = service.updateEvent(
            eventId = eventUid,
            summary = "Daily Standup"
        )
        assertTrue(updateResult is ServiceResult.Success)

        val updatedIcs = mockClient.lastUpdatedIcs!!
        assertTrue(updatedIcs.contains("SUMMARY:Daily Standup"), "Title updated")
        assertTrue(updatedIcs.contains("RRULE:FREQ=WEEKLY"), "RRULE preserved")
        assertTrue(updatedIcs.contains("LOCATION:Zoom"), "Location preserved")
        assertTrue(updatedIcs.contains("DESCRIPTION:Team sync"), "Description preserved")

        // 5. Delete
        service.clearCache()
        mockClient.eventsResponse = listOf(
            CalDavEvent(eventUid, "/cal/$eventUid.ics", "https://test.com/cal/$eventUid.ics",
                "\"updated-etag\"", updatedIcs)
        )
        mockClient.registeredEvents[eventUid] = mockClient.eventsResponse[0]
        service.getEvents("cal-1", "2025-12-25", "2025-12-25")

        val deleteResult = service.deleteEvent(eventUid)
        assertTrue(deleteResult is ServiceResult.Success)
        assertEquals("/cal/$eventUid.ics", mockClient.lastDeletedHref)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // READ-AFTER-WRITE / EVENTUAL CONSISTENCY
    //
    // iCloud gives no immediate-visibility guarantee: an event just created via
    // PUT may not appear in the very next calendar-query (CDN indexing lag). This
    // stateless server never treats "absent from a listing" as "deleted" — it has
    // no reconciler and no local store to prune. These tests pin that contract so a
    // future refactor can't silently reintroduce a destructive prune.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `create success is authoritative even when the event is absent from the next listing`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        // Create succeeds on the server.
        val created = service.createEvent(
            calendarId = "cal-1",
            summary = "Fresh event",
            startTime = "2025-01-15T09:00:00Z",
            endTime = "2025-01-15T10:00:00Z"
        )
        assertTrue(created is ServiceResult.Success, "create must succeed")
        val info = (created as ServiceResult.Success).data
        assertNotNull(info.handle, "create returns an authoritative handle")

        // Simulate CDN lag: the immediately-following listing does NOT include it.
        mockClient.eventsResponse = emptyList()
        val listing = service.getEvents("cal-1", "2025-01-15", "2025-01-15")
        assertTrue(listing is ServiceResult.Success)
        assertTrue((listing as ServiceResult.Success).data.isEmpty(),
            "listing lags — event not surfaced yet")

        // The write still landed: the returned handle resolves to the event with no
        // dependency on the listing. A client must NOT read the empty listing as a
        // failed/deleted create and recreate it (that would duplicate).
        val fetched = mockClient.getEvent(EventHandle.decode(info.handle!!)!!.href)
        assertTrue(fetched is CalDavResult.Success,
            "the created event is reachable by handle despite listing lag")
    }

    @Test
    fun `a lagging empty listing does not evict a cached just-created event`() {
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        val created = service.createEvent(
            calendarId = "cal-1",
            summary = "Cached event",
            startTime = "2025-01-15T09:00:00Z",
            endTime = "2025-01-15T10:00:00Z"
        )
        assertTrue(created is ServiceResult.Success)
        val sizeAfterCreate = service.cacheSize()
        assertTrue(sizeAfterCreate >= 1, "create warms the cache")

        // A lagging listing returns nothing. getEvents must not interpret absence as
        // deletion — the cache entry for the just-created event survives.
        mockClient.eventsResponse = emptyList()
        service.getEvents("cal-1", "2025-01-15", "2025-01-15")
        assertEquals(sizeAfterCreate, service.cacheSize(),
            "an empty (lagging) listing must not prune cached events")
    }

    @Test
    fun `listCalendars caches connection validation`() {
        // First call succeeds (default is Success)
        mockClient.calendars = listOf(
            CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false)
        )

        val result1 = service.listCalendars()
        assertTrue(result1 is ServiceResult.Success)

        // Change connection result to failure - should still work (cached)
        mockClient.checkConnectionResult = CalDavResult.Error(500, "Server down")

        val result2 = service.listCalendars()
        assertTrue(result2 is ServiceResult.Success) // Uses cached validation
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

    var getEventResult: CalDavResult<CalDavEvent>? = null
    var getEventCallCount: Int = 0

    override fun getEvent(href: String): CalDavResult<CalDavEvent> {
        getEventCallCount++
        getEventResult?.let { return it }
        val event = registeredEvents.values.find { it.href == href }
            ?: eventsResponse.find { it.href == href }
            ?: return CalDavResult.Error(404, "Event not found: $href")
        return CalDavResult.Success(event)
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

    // When > 0, the next N update/delete calls return a 412 before succeeding.
    // Lets tests exercise the service's refetch-and-retry-once path.
    var fail412UpdatesRemaining: Int = 0
    var fail412DeletesRemaining: Int = 0
    var updateEtagsSeen: MutableList<String?> = mutableListOf()
    var deleteEtagsSeen: MutableList<String?> = mutableListOf()
    var updateEventCallCount: Int = 0
    var deleteEventCallCount: Int = 0

    override fun updateEvent(href: String, icalData: String, etag: String?): CalDavResult<CalDavEvent> {
        updateEventCallCount++
        updateEtagsSeen.add(etag)
        lastUpdatedIcs = icalData

        if (fail412UpdatesRemaining > 0) {
            fail412UpdatesRemaining--
            return CalDavResult.Error(412, "Precondition failed")
        }

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
        deleteEventCallCount++
        deleteEtagsSeen.add(etag)
        lastDeletedHref = href

        if (fail412DeletesRemaining > 0) {
            fail412DeletesRemaining--
            return CalDavResult.Error(412, "Precondition failed")
        }

        if (deleteEventResult != null) {
            return deleteEventResult!!
        }

        val event = registeredEvents.values.find { it.href == href }
        if (event != null) {
            registeredEvents.remove(event.uid)
        }
        return CalDavResult.Success(Unit)
    }

    var checkConnectionResult: CalDavResult<Boolean> = CalDavResult.Success(true)

    override fun checkConnection(): CalDavResult<Boolean> = checkConnectionResult

    override fun fetchEtags(calendarId: String, startDate: String, endDate: String): CalDavResult<Map<String, String?>> {
        val calendar = calendars.find { it.id == calendarId }
            ?: return CalDavResult.Error(404, "Calendar not found: $calendarId")
        return CalDavResult.Success(eventsResponse.associate { it.href to it.etag })
    }
}
