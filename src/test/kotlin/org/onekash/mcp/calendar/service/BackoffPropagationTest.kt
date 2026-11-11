package org.onekash.mcp.calendar.service

import org.onekash.mcp.calendar.caldav.*
import org.onekash.mcp.calendar.testsupport.MockCalDavClient
import org.onekash.mcp.calendar.validation.EventScope
import java.time.Instant
import kotlin.test.*

class BackoffPropagationTest {
    private val deadline = Instant.parse("2026-11-10T12:26:35Z")
    private val throttle = CalDavResult.Error(429, "Wait until $deadline", true, deadline)
    private val event = CalDavEvent("series", "/cal/series.ics", "https://test.com/cal/series.ics", "etag", """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:series
        SUMMARY:Series
        DTSTART:20250115T100000Z
        DTEND:20250115T110000Z
        RRULE:FREQ=DAILY;COUNT=5
        END:VEVENT
        END:VCALENDAR
    """.trimIndent())

    @Test
    fun `update and delete carry a throttle from the single conflict refetch`() {
        for (update in listOf(true, false)) {
            val client = MockCalDavClient()
            client.registeredEvents[event.uid] = event
            client.eventsResponse = listOf(event)
            val service = CalendarService(client)
            assertIs<ServiceResult.Success<*>>(service.getEvents("cal", "2025-01-15", "2025-01-19"))
            client.fail412UpdatesRemaining = 1
            client.fail412DeletesRemaining = 1
            client.getEventResult = throttle
            val result = assertIs<ServiceResult.Error>(if (update)
                service.updateEvent(event.uid, summary = "Changed") else service.deleteEvent(event.uid))
            assertEquals(429, result.code)
            assertEquals(deadline, result.retryAt)
            assertEquals(1, client.getEventCallCount)
            assertEquals(1, if (update) client.updateEventCallCount else client.deleteEventCallCount)
        }
    }

    @Test
    fun `series split and truncate carry a throttle from the single conflict refetch`() {
        for (update in listOf(true, false)) {
            val backing = MockCalDavClient()
            backing.registeredEvents[event.uid] = event
            backing.fail412UpdatesRemaining = 1
            var reads = 0
            val client = object : CalDavClient by backing {
                override fun getEvent(href: String): CalDavResult<CalDavEvent> =
                    if (++reads == 1) CalDavResult.Success(event) else throttle
            }
            val service = CalendarService(client)
            val handle = EventHandle.encode(event.href, event.etag, "20250117T100000Z")
            val result = assertIs<ServiceResult.Error>(if (update)
                service.updateEvent(handle, summary = "Changed", scope = EventScope.THIS_AND_FUTURE)
                else service.deleteEvent(handle, EventScope.THIS_AND_FUTURE))
            assertEquals(429, result.code)
            assertEquals(deadline, result.retryAt)
            assertEquals(2, reads)
            assertEquals(1, backing.updateEventCallCount)
        }
    }
}
