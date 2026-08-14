package org.onekash.mcp.calendar.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.onekash.mcp.calendar.caldav.CalDavCalendar
import org.onekash.mcp.calendar.caldav.CalDavEvent
import org.onekash.mcp.calendar.testsupport.MockCalDavClient

/**
 * Tests that `get_events` re-checks what the server sent against the requested
 * range.
 *
 * A CalDAV server is supposed to apply the REPORT's time-range itself, but
 * iCloud returns all-day events whose *exclusive* DTEND lands exactly on the
 * query start — so an event that ended yesterday shows up as today's. RFC 4791
 * §9.9 defines overlap as `(DTSTART < end) AND (DTEND > start)`; a touching
 * DTEND must not match.
 *
 * The mock client returns whatever it is handed regardless of the requested
 * dates, which is exactly the misbehavior being defended against.
 */
class GetEventsRangeFilterTest {

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

    private fun serveIcs(vararg vevents: String) {
        mockClient.eventsResponse = vevents.mapIndexed { i, body ->
            CalDavEvent(
                uid = "uid-$i",
                href = "/caldav/cal-1/uid-$i.ics",
                url = "https://caldav.icloud.com/caldav/cal-1/uid-$i.ics",
                etag = "\"etag-$i\"",
                icalData = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//EN\n$body\nEND:VCALENDAR"
            )
        }
    }

    private fun getEvents(start: String, end: String = start): List<EventInfo> {
        val result = service.getEvents(calendarId, start, end)
        assertTrue(result is ServiceResult.Success, "expected success, got $result")
        return (result as ServiceResult.Success).data
    }

    // ═══════════════════════════════════════════════════════════════════════
    // THE REPORTED BUG
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `all-day event whose exclusive DTEND touches the query start is excluded`() {
        // Occupies 7/25 and 7/26. DTEND 20260727 is exclusive, so 7/27 is not part
        // of it — but iCloud returns it for a 7/27 query anyway.
        serveIcs(
            """
            BEGIN:VEVENT
            UID:boundary@example.com
            DTSTART;VALUE=DATE:20260725
            DTEND;VALUE=DATE:20260727
            SUMMARY:Two-day all-day event
            END:VEVENT
            """.trimIndent()
        )

        assertTrue(
            getEvents("2026-07-27").isEmpty(),
            "the event ended on 7/26 and must not be reported for 7/27"
        )
    }

    @Test
    fun `the same event is still returned on the days it actually occupies`() {
        serveIcs(
            """
            BEGIN:VEVENT
            UID:boundary@example.com
            DTSTART;VALUE=DATE:20260725
            DTEND;VALUE=DATE:20260727
            SUMMARY:Two-day all-day event
            END:VEVENT
            """.trimIndent()
        )

        assertEquals(1, getEvents("2026-07-25").size, "7/25 is occupied")
        assertEquals(1, getEvents("2026-07-26").size, "7/26 is occupied")
        assertTrue(getEvents("2026-07-24").isEmpty(), "the day before is not")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REGRESSION GUARD — recurring events must survive the filter
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `recurring event occurring in range is kept despite an old master DTSTART`() {
        // Filtering on the master's DTSTART (2023) would drop an event that
        // genuinely occurs on the queried day — a missing-event bug, which is
        // worse than the wrong-date one. Expansion has to run first.
        serveIcs(
            """
            BEGIN:VEVENT
            UID:yearly@example.com
            DTSTART;VALUE=DATE:20230517
            DTEND;VALUE=DATE:20230518
            RRULE:FREQ=YEARLY
            SUMMARY:Recurring yearly event
            END:VEVENT
            """.trimIndent()
        )

        val events = getEvents("2026-05-17")

        assertEquals(1, events.size, "the 2026 occurrence must survive the range filter")
        assertEquals("2026-05-17", events[0].startDate, "and be dated to the occurrence")
    }

    @Test
    fun `recurring event not occurring in range is excluded`() {
        serveIcs(
            """
            BEGIN:VEVENT
            UID:yearly@example.com
            DTSTART;VALUE=DATE:20230517
            DTEND;VALUE=DATE:20230518
            RRULE:FREQ=YEARLY
            SUMMARY:Recurring yearly event
            END:VEVENT
            """.trimIndent()
        )

        assertTrue(getEvents("2026-05-18").isEmpty(), "no occurrence on 5/18")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OVERLAP, NOT START-IN-WINDOW — a recurring occurrence that started before
    // the queried day but is still running during it must reach the output.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `daily overnight recurring occurrence spanning into the queried day reaches the output`() {
        // 22:00–06:00 daily. Querying 2026-06-10 must surface both the occurrence that
        // began 22:00 the prior evening (running to 06:00) and that evening's occurrence,
        // through the full service path (expansion widening + overlapsRequestedRange).
        serveIcs(
            """
            BEGIN:VEVENT
            UID:overnight@example.com
            DTSTART:20260601T220000Z
            DTEND:20260602T060000Z
            RRULE:FREQ=DAILY
            SUMMARY:Overnight shift
            END:VEVENT
            """.trimIndent()
        )

        val starts = getEvents("2026-06-10").mapNotNull { it.startTime }.sorted()
        assertEquals(2, starts.size, "prior-evening + query-evening occurrences: $starts")
        assertTrue(starts.any { it.startsWith("2026-06-09T22:00") }, "the prior-evening occurrence must reach the output: $starts")
        assertTrue(starts.any { it.startsWith("2026-06-10T22:00") }, "the query-day evening occurrence too: $starts")
    }

    @Test
    fun `widened expansion of a full-year daily series does not spuriously 413`() {
        // A DAILY series with 1-hour occurrences over a wide (but within-span-cap) range.
        // Widening adds at most one leading occurrence; it must not trip MAX_ITERATIONS or
        // the MAX_RETURNED_EVENTS count cap — the request succeeds with one event per day.
        serveIcs(
            """
            BEGIN:VEVENT
            UID:year-daily@example.com
            DTSTART:20260101T090000Z
            DTEND:20260101T100000Z
            RRULE:FREQ=DAILY
            SUMMARY:Daily standup
            END:VEVENT
            """.trimIndent()
        )

        val result = service.getEvents(calendarId, "2026-01-01", "2026-06-30")
        assertTrue(result is ServiceResult.Success, "wide daily range must succeed, not 413: $result")
        val events = (result as ServiceResult.Success).data
        // Jan 1 through Jun 30 2026 inclusive = 181 days, one occurrence each.
        assertEquals(181, events.size, "one occurrence per day, no leading double-count")
    }

    @Test
    fun `pathological sub-daily series with a near-year duration returns a clean 413, not a crash`() {
        // A MINUTELY series whose occurrences each last most of a year is degenerate:
        // hundreds of thousands of occurrences overlap any window, far past
        // MAX_RETURNED_EVENTS, so a 413 is the correct answer. Widening cannot turn this
        // into a wrong result or an uncaught exception — it must be a structured 413.
        serveIcs(
            """
            BEGIN:VEVENT
            UID:pathological@example.com
            DTSTART:20260101T000000Z
            DTEND:20261215T000000Z
            RRULE:FREQ=MINUTELY
            SUMMARY:Degenerate overlapping series
            END:VEVENT
            """.trimIndent()
        )

        val result = service.getEvents(calendarId, "2026-06-01", "2026-06-01")
        assertTrue(result is ServiceResult.Error, "expected a structured error, got $result")
        assertEquals(413, (result as ServiceResult.Error).code, "must be a clean 413, not a crash")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ORDINARY CASES MUST NOT REGRESS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `single-day all-day event on the queried day is kept`() {
        serveIcs(
            """
            BEGIN:VEVENT
            UID:single@example.com
            DTSTART;VALUE=DATE:20260727
            DTEND;VALUE=DATE:20260728
            SUMMARY:One-day all-day event
            END:VEVENT
            """.trimIndent()
        )

        assertEquals(1, getEvents("2026-07-27").size)
    }

    @Test
    fun `multi-day all-day event spanning the queried day is kept`() {
        serveIcs(
            """
            BEGIN:VEVENT
            UID:spanning@example.com
            DTSTART;VALUE=DATE:20260726
            DTEND;VALUE=DATE:20260730
            SUMMARY:Spanning all-day event
            END:VEVENT
            """.trimIndent()
        )

        assertEquals(1, getEvents("2026-07-27").size)
    }

    @Test
    fun `timed event inside the queried day is kept`() {
        serveIcs(
            """
            BEGIN:VEVENT
            UID:timed@example.com
            DTSTART:20260727T090000Z
            DTEND:20260727T100000Z
            SUMMARY:Morning meeting
            END:VEVENT
            """.trimIndent()
        )

        assertEquals(1, getEvents("2026-07-27").size)
    }

    @Test
    fun `timed event starting exactly at the next day's midnight is excluded`() {
        serveIcs(
            """
            BEGIN:VEVENT
            UID:next-midnight@example.com
            DTSTART:20260728T000000Z
            DTEND:20260728T010000Z
            SUMMARY:Just after the window
            END:VEVENT
            """.trimIndent()
        )

        assertTrue(getEvents("2026-07-27").isEmpty())
    }

    @Test
    fun `timed event ending exactly at the queried day's midnight is excluded`() {
        // Ends at 7/27 00:00, i.e. belongs to 7/26 — the timed analogue of the
        // all-day boundary case.
        serveIcs(
            """
            BEGIN:VEVENT
            UID:prev-midnight@example.com
            DTSTART:20260726T230000Z
            DTEND:20260727T000000Z
            SUMMARY:Ends as the window opens
            END:VEVENT
            """.trimIndent()
        )

        assertTrue(getEvents("2026-07-27").isEmpty())
    }

    @Test
    fun `multi-day query keeps every event within it`() {
        serveIcs(
            """
            BEGIN:VEVENT
            UID:a@example.com
            DTSTART;VALUE=DATE:20260727
            DTEND;VALUE=DATE:20260728
            SUMMARY:Day one
            END:VEVENT
            """.trimIndent(),
            """
            BEGIN:VEVENT
            UID:b@example.com
            DTSTART;VALUE=DATE:20260729
            DTEND;VALUE=DATE:20260730
            SUMMARY:Day three
            END:VEVENT
            """.trimIndent(),
            """
            BEGIN:VEVENT
            UID:c@example.com
            DTSTART;VALUE=DATE:20260801
            DTEND;VALUE=DATE:20260802
            SUMMARY:Outside
            END:VEVENT
            """.trimIndent()
        )

        val summaries = getEvents("2026-07-27", "2026-07-30").map { it.summary }.sorted()
        assertEquals(listOf("Day one", "Day three"), summaries)
    }

    @Test
    fun `end_date is inclusive`() {
        serveIcs(
            """
            BEGIN:VEVENT
            UID:on-end@example.com
            DTSTART;VALUE=DATE:20260730
            DTEND;VALUE=DATE:20260731
            SUMMARY:On the last requested day
            END:VEVENT
            """.trimIndent()
        )

        assertEquals(1, getEvents("2026-07-27", "2026-07-30").size)
    }
}
