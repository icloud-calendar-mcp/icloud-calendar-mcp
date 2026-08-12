package org.onekash.mcp.calendar.testsupport

import org.onekash.mcp.calendar.caldav.CalDavCalendar
import org.onekash.mcp.calendar.caldav.CalDavClient
import org.onekash.mcp.calendar.caldav.CalDavEvent
import org.onekash.mcp.calendar.caldav.CalDavResult

/**
 * The single in-memory [CalDavClient] test double for the whole suite.
 *
 * This consolidates the three near-identical fakes that used to live inside
 * `CalendarServiceTest`, `CalDavClientTest` and `AdversarialTest`. It covers
 * every capability those callers relied on, so migrating a test is just
 * deleting its local copy and importing this one.
 *
 * ### How the knobs compose
 *
 * The method bodies resolve behavior by precedence, and every "override" knob
 * defaults to a no-op (null / true / 0), so a caller only pays for what it sets:
 *
 * 1. [errorToReturn] — a blanket error returned by every method (interface-contract style).
 * 2. Per-operation canned results ([listCalendarsResult], [getEventResult],
 *    [deleteEventResult]) and per-operation canned successes
 *    ([calendarsToReturn], [eventsToReturn], [createdEventToReturn],
 *    [updatedEventToReturn], [deleteSuccess]).
 * 3. The stateful default: an in-memory store ([registeredEvents]) that
 *    `createEvent` populates and `getEvent`/`updateEvent`/`deleteEvent` operate on.
 *
 * The stateful default mirrors the old `CalendarServiceTest` fake: a bare
 * `createEvent` synthesizes and stores a server event, and a bare
 * `updateEvent`/`deleteEvent` operates on that store (a `404` if the href is
 * absent). This differs from the old interface-contract fake, whose bare
 * create/update returned `500 "No event configured"`; no test asserted on those
 * unconfigured paths, so the store-backed default is the one to rely on.
 *
 * `getEvents` returns [eventsToReturn] when set, otherwise [eventsResponse] — it is
 * deliberately decoupled from the created-event store so eventual-consistency /
 * read-after-write tests can simulate a listing that lags a just-created event.
 * Its calendar-existence 404 only fires when [calendars] is non-empty, so callers
 * that never populate [calendars] (pure interface-contract tests) still succeed.
 */
class MockCalDavClient : CalDavClient {

    // ── Calendars ──────────────────────────────────────────────────────────
    /** Calendars returned by [listCalendars] and used for the [getEvents] existence check. */
    var calendars: List<CalDavCalendar> = emptyList()

    /** Canned override for [listCalendars] (e.g. to inject an auth error). */
    var listCalendarsResult: CalDavResult<List<CalDavCalendar>>? = null

    /** Canned success list for [listCalendars]; takes precedence over [calendars] when set. */
    var calendarsToReturn: List<CalDavCalendar>? = null

    // ── Events ─────────────────────────────────────────────────────────────
    /** The list [getEvents] returns (unless [eventsToReturn] is set). Independent of [registeredEvents]. */
    var eventsResponse: List<CalDavEvent> = emptyList()

    /** Canned success list for [getEvents]; takes precedence over [eventsResponse] when set. */
    var eventsToReturn: List<CalDavEvent>? = null

    /** In-memory event store, keyed by UID, that create/update/delete/getEvent operate on. */
    var registeredEvents: MutableMap<String, CalDavEvent> = mutableMapOf()

    // ── Blanket error injection ──────────────────────────────────────────────
    /** When set, every method returns this error before any other logic. */
    var errorToReturn: CalDavResult.Error? = null

    // ── Per-operation canned results ─────────────────────────────────────────
    var getEventResult: CalDavResult<CalDavEvent>? = null
    var createdEventToReturn: CalDavEvent? = null
    var updatedEventToReturn: CalDavEvent? = null
    var deleteEventResult: CalDavResult<Unit>? = null

    /** When false (and no other override applies), [deleteEvent] returns a 500. */
    var deleteSuccess: Boolean = true

    var checkConnectionResult: CalDavResult<Boolean> = CalDavResult.Success(true)

    // ── ETag-precondition (412) injection ────────────────────────────────────
    // When > 0, the next N update/delete calls return a 412 before succeeding.
    // Lets tests exercise the service's refetch-and-retry-once path.
    var fail412UpdatesRemaining: Int = 0
    var fail412DeletesRemaining: Int = 0

    // ── Call/argument capture ────────────────────────────────────────────────
    var lastCreatedIcs: String? = null
    var lastUpdatedIcs: String? = null
    var lastDeletedHref: String? = null

    var lastCalendarId: String? = null
    var lastStartDate: String? = null
    var lastEndDate: String? = null

    var updateEtagsSeen: MutableList<String?> = mutableListOf()
    var deleteEtagsSeen: MutableList<String?> = mutableListOf()

    var listCalendarsCalled: Int = 0
    var getEventsCalled: Int = 0
    var createEventCalled: Int = 0
    var updateEventCalled: Int = 0
    var deleteEventCalled: Int = 0
    // Legacy names kept for callers that already asserted on them.
    var getEventCallCount: Int = 0
    var updateEventCallCount: Int = 0
    var deleteEventCallCount: Int = 0

    override fun listCalendars(): CalDavResult<List<CalDavCalendar>> {
        listCalendarsCalled++
        errorToReturn?.let { return it }
        listCalendarsResult?.let { return it }
        return CalDavResult.Success(calendarsToReturn ?: calendars)
    }

    override fun getEvents(calendarId: String, startDate: String, endDate: String): CalDavResult<List<CalDavEvent>> {
        getEventsCalled++
        lastCalendarId = calendarId
        lastStartDate = startDate
        lastEndDate = endDate
        errorToReturn?.let { return it }
        // Existence check only when calendars are configured, so pure
        // interface-contract callers (empty `calendars`) still get a Success.
        if (calendars.isNotEmpty() && calendars.none { it.id == calendarId }) {
            return CalDavResult.Error(404, "Calendar not found: $calendarId")
        }
        return CalDavResult.Success(eventsToReturn ?: eventsResponse)
    }

    override fun getEvent(href: String): CalDavResult<CalDavEvent> {
        getEventCallCount++
        errorToReturn?.let { return it }
        getEventResult?.let { return it }
        val found = registeredEvents.values.find { it.href == href }
            ?: eventsResponse.find { it.href == href }
            ?: eventsToReturn?.find { it.href == href }
        return found?.let { CalDavResult.Success(it) }
            ?: CalDavResult.Error(404, "Event not found: $href")
    }

    override fun createEvent(calendarId: String, icalData: String): CalDavResult<CalDavEvent> {
        createEventCalled++
        lastCreatedIcs = icalData
        errorToReturn?.let { return it }
        createdEventToReturn?.let { return CalDavResult.Success(it) }

        // Default: extract the UID from the ICS, store, and echo back a server event.
        val uid = Regex("UID:([^\r\n]+)").find(icalData)?.groupValues?.get(1) ?: "generated-uid"
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
        updateEventCalled++
        updateEventCallCount++
        updateEtagsSeen.add(etag)
        lastUpdatedIcs = icalData
        errorToReturn?.let { return it }
        if (fail412UpdatesRemaining > 0) {
            fail412UpdatesRemaining--
            return CalDavResult.Error(412, "Precondition failed")
        }
        updatedEventToReturn?.let { return CalDavResult.Success(it) }

        val existing = registeredEvents.values.find { it.href == href }
            ?: return CalDavResult.Error(404, "Event not found")
        val updated = existing.copy(icalData = icalData, etag = "\"updated-etag\"")
        registeredEvents[existing.uid] = updated
        return CalDavResult.Success(updated)
    }

    override fun deleteEvent(href: String, etag: String?): CalDavResult<Unit> {
        deleteEventCalled++
        deleteEventCallCount++
        deleteEtagsSeen.add(etag)
        lastDeletedHref = href
        errorToReturn?.let { return it }
        if (fail412DeletesRemaining > 0) {
            fail412DeletesRemaining--
            return CalDavResult.Error(412, "Precondition failed")
        }
        deleteEventResult?.let { return it }
        if (!deleteSuccess) return CalDavResult.Error(500, "Delete failed")

        val event = registeredEvents.values.find { it.href == href }
        if (event != null) registeredEvents.remove(event.uid)
        return CalDavResult.Success(Unit)
    }

    override fun checkConnection(): CalDavResult<Boolean> = checkConnectionResult

    override fun fetchEtags(calendarId: String, startDate: String, endDate: String): CalDavResult<Map<String, String?>> {
        errorToReturn?.let { return it }
        if (calendars.isNotEmpty() && calendars.none { it.id == calendarId }) {
            return CalDavResult.Error(404, "Calendar not found: $calendarId")
        }
        return CalDavResult.Success((eventsToReturn ?: eventsResponse).associate { it.href to it.etag })
    }
}
