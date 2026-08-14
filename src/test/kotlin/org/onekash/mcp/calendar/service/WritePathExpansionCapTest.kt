package org.onekash.mcp.calendar.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.onekash.mcp.calendar.caldav.CalDavEvent
import org.onekash.mcp.calendar.testsupport.MockCalDavClient
import org.onekash.mcp.calendar.validation.EventScope
import java.util.concurrent.TimeUnit

/**
 * A this-and-future edit or delete on a pathologically dense recurring series expands
 * the master's whole history up to the cut (IcsPatcher.occurrenceInstantsBefore), which
 * trips the expander's per-series work-bound (US2). The service must translate that
 * abort into a clean 413 rather than letting it escape update_event / delete_event as an
 * uncaught exception (INTERNAL_ERROR).
 *
 * The occurrence-liveness check (findOccurrence) runs first over a narrow window, so the
 * occurrence resolves as live; the abort comes from the full-history expansion inside the
 * split/truncate, and both bridge types are caught at the scope-dispatch boundary.
 */
class WritePathExpansionCapTest {

    private lateinit var mockClient: MockCalDavClient
    private lateinit var service: CalendarService

    @BeforeEach
    fun setup() {
        mockClient = MockCalDavClient()
        service = CalendarService(mockClient)
    }

    /**
     * Register one open-ended hourly master starting [dtStart]. Over the years to a cut
     * in 2026 this expands to tens of thousands of occurrences, past MAX_ITERATIONS.
     */
    private fun registerHourlySeries(dtStart: String = "20200101T000000Z"): CalDavEvent {
        val event = CalDavEvent(
            uid = "dense-series",
            href = "/cal/dense-series.ics",
            url = "https://caldav.icloud.com/cal/dense-series.ics",
            etag = "\"etag-dense\"",
            icalData = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//EN\n" +
                "BEGIN:VEVENT\nUID:dense-series\nDTSTART:$dtStart\nDTEND:${dtStart.dropLast(7)}010000Z\n" +
                "SUMMARY:Dense hourly series\nRRULE:FREQ=HOURLY\nEND:VEVENT\nEND:VCALENDAR"
        )
        mockClient.registeredEvents[event.uid] = event
        return event
    }

    /** A live occurrence far enough after DTSTART that the truncation expansion trips the bound. */
    private val farOccurrenceId = "20260101T000000Z"

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `this-and-future delete on a dense series surfaces as Error 413`() {
        val event = registerHourlySeries()
        val handle = EventHandle.encode(event.href, event.etag, farOccurrenceId)
        val result = service.deleteEvent(handle, scope = EventScope.THIS_AND_FUTURE)
        assertTrue(result is ServiceResult.Error, "expected error, got $result")
        assertEquals(413, (result as ServiceResult.Error).code)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `this-and-future edit on a dense series surfaces as Error 413`() {
        val event = registerHourlySeries()
        val handle = EventHandle.encode(event.href, event.etag, farOccurrenceId)
        val result = service.updateEvent(
            eventId = handle,
            summary = "Edited from here on",
            scope = EventScope.THIS_AND_FUTURE
        )
        assertTrue(result is ServiceResult.Error, "expected error, got $result")
        assertEquals(413, (result as ServiceResult.Error).code)
    }
}
