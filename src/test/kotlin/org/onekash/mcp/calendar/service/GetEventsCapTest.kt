package org.onekash.mcp.calendar.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.onekash.mcp.calendar.caldav.CalDavCalendar
import org.onekash.mcp.calendar.caldav.CalDavEvent
import org.onekash.mcp.calendar.caldav.CalDavResult
import org.onekash.mcp.calendar.error.SecureErrorHandler
import org.onekash.mcp.calendar.testsupport.MockCalDavClient
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.concurrent.TimeUnit

/**
 * Tests for the get_events assembled-result cap (US3) and the way the expansion
 * work-bound (US2) surfaces through the service.
 *
 * Three guards converge here:
 *  - a result of more than [CalendarService] MAX_RETURNED_EVENTS occurrences is
 *    rejected with a 413 rather than returned (US3);
 *  - a single series that trips the expander's MAX_ITERATIONS bound surfaces as a
 *    413 (US2 wiring), not an uncaught exception;
 *  - the client-side 2 MB response cap (already a 413) still passes straight
 *    through, so the two 413s never mask each other (Finding 3).
 */
class GetEventsCapTest {

    private lateinit var mockClient: MockCalDavClient
    private lateinit var service: CalendarService

    private val calendarId = "cal-1"

    @BeforeEach
    fun setup() {
        mockClient = MockCalDavClient()
        mockClient.calendars = listOf(
            CalDavCalendar(
                id = calendarId,
                href = "/caldav/cal-1/",
                url = "https://caldav.icloud.com/caldav/cal-1/",
                displayName = "Private",
                color = null,
                ctag = "ctag1",
                isReadOnly = false
            )
        )
        service = CalendarService(mockClient)
    }

    /** Serve [n] distinct non-recurring timed events, all inside the queried window. */
    private fun serveNonRecurring(n: Int) {
        mockClient.eventsResponse = (0 until n).map { i ->
            CalDavEvent(
                uid = "uid-$i",
                href = "/caldav/cal-1/uid-$i.ics",
                url = "https://caldav.icloud.com/caldav/cal-1/uid-$i.ics",
                etag = "\"etag-$i\"",
                icalData = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//EN\n" +
                    "BEGIN:VEVENT\nUID:uid-$i\nDTSTART:20260601T100000Z\nDTEND:20260601T110000Z\n" +
                    "SUMMARY:Event $i\nEND:VEVENT\nEND:VCALENDAR"
            )
        }
    }

    /** Serve a single recurring master with [rrule], starting inside the window. */
    private fun serveSeries(rrule: String, dtStart: String = "20260601T000000Z") {
        mockClient.eventsResponse = listOf(
            CalDavEvent(
                uid = "series-1",
                href = "/caldav/cal-1/series-1.ics",
                url = "https://caldav.icloud.com/caldav/cal-1/series-1.ics",
                etag = "\"etag-s1\"",
                icalData = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//EN\n" +
                    "BEGIN:VEVENT\nUID:series-1\nDTSTART:$dtStart\nDTEND:${dtStart.dropLast(7)}010000Z\n" +
                    "SUMMARY:Dense series\nRRULE:$rrule\nEND:VEVENT\nEND:VCALENDAR"
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // US3: assembled-result count cap
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `exactly 1000 events returns Success with all of them (boundary)`() {
        serveNonRecurring(1000)
        val result = service.getEvents(calendarId, "2026-06-01", "2026-06-01")
        assertTrue(result is ServiceResult.Success, "expected success, got $result")
        assertEquals(1000, (result as ServiceResult.Success).data.size)
    }

    @Test
    fun `1001 events returns Error 413 (boundary)`() {
        serveNonRecurring(1001)
        val result = service.getEvents(calendarId, "2026-06-01", "2026-06-01")
        assertTrue(result is ServiceResult.Error, "expected error, got $result")
        assertEquals(413, (result as ServiceResult.Error).code)
    }

    @Test
    fun `empty result returns Success with an empty list, not an over-count error`() {
        serveNonRecurring(0)
        val result = service.getEvents(calendarId, "2026-06-01", "2026-06-01")
        assertTrue(result is ServiceResult.Success, "expected success, got $result")
        assertTrue((result as ServiceResult.Success).data.isEmpty())
    }

    @Test
    fun `one dense series over the count cap (under the expansion bound) returns Error 413`() {
        // FREQ=MINUTELY over one day = ~1,440 occurrences: over MAX_RETURNED_EVENTS
        // (1,000) but well under MAX_ITERATIONS (10,000), so the count cap fires,
        // not the expansion bound.
        serveSeries("FREQ=MINUTELY")
        val result = service.getEvents(calendarId, "2026-06-01", "2026-06-02")
        assertTrue(result is ServiceResult.Error, "expected error, got $result")
        assertEquals(413, (result as ServiceResult.Error).code)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // US2 wiring: an expander abort surfaces as a 413, not an uncaught throw
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `a series that trips the expansion bound surfaces as Error 413`() {
        // FREQ=SECONDLY over two days = ~172,800 potential occurrences, past
        // MAX_ITERATIONS. The expander aborts; the service must translate that into
        // a 413 rather than letting the exception escape get_events.
        serveSeries("FREQ=SECONDLY")
        val result = service.getEvents(calendarId, "2026-06-01", "2026-06-03")
        assertTrue(result is ServiceResult.Error, "expected error, got $result")
        assertEquals(413, (result as ServiceResult.Error).code)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Finding 3: the client-side 2 MB 413 passes straight through, unchanged
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `client-side 2MB payload-too-large 413 passes straight through get_events`() {
        // The 2 MB body cap returns CalDavResult.Error(413) before any parsing, so it
        // arrives as a CalDavResult.Error and never reaches the count loop. The two
        // 413s (inbound size cap vs outbound count cap) do not mask each other.
        mockClient.errorToReturn = CalDavResult.Error.payloadTooLargeError(
            "Response too large (exceeds 2MB limit)"
        )
        val result = service.getEvents(calendarId, "2026-06-01", "2026-06-02")
        assertTrue(result is ServiceResult.Error, "expected error, got $result")
        val error = result as ServiceResult.Error
        assertEquals(413, error.code)
        assertTrue(error.message.contains("2MB"), "should preserve the client-side message: ${error.message}")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // US3 AC3: 413 error-shape parity with payloadTooLargeError
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `over-count 413 maps to the same PAYLOAD_TOO_LARGE MCP shape as the payload-too-large 413`() {
        // Both service errors reach MCP through serviceError(413, ...), which maps 413 to
        // the PAYLOAD_TOO_LARGE code. So the two 413s (outbound count/expansion cap vs the
        // inbound 2MB cap) share one machine-readable code: same isError, same error code,
        // no structuredContent, regardless of the (different) messages.
        val overCount = SecureErrorHandler.serviceError(
            413, "Too many events in range (exceeds 1000). Narrow the date range."
        )
        val payloadTooLarge = SecureErrorHandler.serviceError(
            413, "Response too large (exceeds 2MB limit)"
        )

        assertEquals(payloadTooLarge.isError, overCount.isError)
        assertTrue(overCount.isError == true)
        assertNull(overCount.structuredContent)
        assertNull(payloadTooLarge.structuredContent)
        assertEquals("PAYLOAD_TOO_LARGE", errorCodeOf(overCount))
        assertEquals(errorCodeOf(payloadTooLarge), errorCodeOf(overCount))
    }

    /** Pull the `"code": "..."` value out of a serviceError CallToolResult's JSON text. */
    private fun errorCodeOf(result: CallToolResult): String {
        val text = (result.content.first() as TextContent).text
        return Regex(""""code":\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("no error code in $text")
    }
}
