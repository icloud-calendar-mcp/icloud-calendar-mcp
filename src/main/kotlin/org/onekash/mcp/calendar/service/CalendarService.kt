package org.onekash.mcp.calendar.service

import org.onekash.mcp.calendar.caldav.*
import org.onekash.mcp.calendar.ics.AlarmSpec
import org.onekash.mcp.calendar.ics.IcsBuilder
import org.onekash.mcp.calendar.ics.IcsPatcher
import org.onekash.mcp.calendar.ics.IcsParser
import org.onekash.mcp.calendar.ics.ParsedAlarm
import org.onekash.mcp.calendar.ics.ParsedEvent
import org.onekash.mcp.calendar.validation.EventScope
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap

/**
 * Result type for CalendarService operations.
 */
sealed class ServiceResult<out T> {
    data class Success<T>(val data: T) : ServiceResult<T>()
    data class Error(val code: Int, val message: String) : ServiceResult<Nothing>()
}

/**
 * Calendar info for MCP responses.
 */
data class CalendarInfo(
    val id: String,
    val name: String,
    val color: String?,
    val readOnly: Boolean,
    val supportedComponents: Set<String> = emptySet()
)

/**
 * Event info for MCP responses.
 * Combines parsed event data with CalDAV metadata.
 */
data class EventInfo(
    val uid: String,
    val href: String,
    val etag: String?,
    /**
     * Opaque, self-contained reference to this event (see [EventHandle]). Carries
     * the normalized href + etag, so any process — even one with a cold cache — can
     * update/delete the event with no cache dependency. Null only on EventInfo
     * instances built outside the service (e.g. test fixtures).
     */
    val handle: String? = null,
    val summary: String,
    val description: String?,
    val location: String?,
    val isAllDay: Boolean,
    val startTime: String?,
    val endTime: String?,
    val startDate: String?,
    val endDate: String?,
    val rrule: String?,
    /**
     * RFC 5545 §3.8.4.4 RECURRENCE-ID in iCal wire form (`20260818T140000Z` /
     * `20260818`): the instance this occurrence identifies within its series. Null
     * for a standalone (non-recurring) event. The [handle] encodes this, so a caller
     * can act on exactly this occurrence later.
     */
    val recurrenceId: String? = null,
    val status: String? = null,
    val url: String? = null,
    val categories: List<String> = emptyList(),
    val priority: Int? = null,
    val organizer: String? = null,
    val attendeeCount: Int = 0,
    val alarms: List<ParsedAlarm> = emptyList()
)

/**
 * CalendarService - Orchestration layer for calendar operations.
 *
 * Handles:
 * - CalDavClient for HTTP operations
 * - IcsParser for reading event data
 * - IcsBuilder for writing event data
 * - Event lookup by ID across calendars
 *
 * Thread-safe: All operations are synchronous and stateless.
 */
/**
 * Cached event with timestamp for TTL expiration.
 */
data class CachedEvent(
    val event: CalDavEvent,
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * The UTC window a `get_events` query covers, in every form the query needs.
 *
 * `end_date` names a day the caller wants *included*, so the window runs up to
 * the start of the day after it. Both bounds are UTC because that is what the
 * REPORT this client builds asks for.
 */
private data class DayRangeUtc(val startDay: LocalDate, val endDay: LocalDate) {
    /** First day after the window. */
    val endDayExclusive: LocalDate get() = endDay.plusDays(1)

    /** Start of the window, inclusive. */
    val startInstant: Instant get() = startDay.atStartOfDay(ZoneOffset.UTC).toInstant()

    /** End of the window, exclusive. */
    val endInstantExclusive: Instant get() = endDayExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()

    /**
     * Last instant inside the window. Recurrence expansion takes an inclusive
     * upper bound, so it gets this rather than [endInstantExclusive].
     */
    val lastInstant: Instant get() = endDay.atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC)
}

/** Builds the [DayRangeUtc] for a query. Dates must already be format-validated. */
private fun dayRangeUtc(startDate: String, endDate: String) =
    DayRangeUtc(LocalDate.parse(startDate), LocalDate.parse(endDate))

class CalendarService(
    private val client: CalDavClient,
    private val parser: IcsParser = IcsParser(),
    private val builder: IcsBuilder = IcsBuilder(),
    private val patcher: IcsPatcher = IcsPatcher(),
    private val cacheTtlMs: Long = 5 * 60 * 1000L,  // 5 minutes default
    private val maxCacheSize: Int = 1000
) {
    // Thread-safe cache with TTL support
    private val eventCache = ConcurrentHashMap<String, CachedEvent>()

    // Connection validation (lazy, cached)
    @Volatile
    private var connectionValidated = false

    private fun ensureConnected(): ServiceResult<Unit> {
        if (connectionValidated) return ServiceResult.Success(Unit)
        return when (val result = client.checkConnection()) {
            is CalDavResult.Success -> {
                connectionValidated = true
                ServiceResult.Success(Unit)
            }
            is CalDavResult.Error -> ServiceResult.Error(result.code, result.message)
        }
    }

    /**
     * Get event from cache if not expired.
     */
    private fun getFromCache(uid: String): CalDavEvent? {
        val cached = eventCache[uid] ?: return null
        if (System.currentTimeMillis() - cached.cachedAt > cacheTtlMs) {
            eventCache.remove(uid)
            return null
        }
        return cached.event
    }

    /**
     * Add event to cache, evicting expired entries if at capacity.
     */
    private fun addToCache(uid: String, event: CalDavEvent) {
        if (eventCache.size >= maxCacheSize) {
            clearExpiredEntries()
        }
        eventCache[uid] = CachedEvent(event)
    }

    /**
     * Remove event from cache.
     */
    private fun removeFromCache(uid: String) {
        eventCache.remove(uid)
    }

    /**
     * Remove the cached entry for a given href, regardless of which UID it is keyed
     * under. Needed on handle-based delete: the cache is keyed by UID, but a handle
     * only carries the href, so a plain [removeFromCache] with the handle string
     * evicts nothing and leaves a stale entry that a later UID-based update could
     * resolve — issuing a PUT to a deleted href, which CalDAV treats as a recreate.
     * Compares normalized hrefs so a partition difference can't hide the entry.
     */
    private fun removeFromCacheByHref(href: String) {
        val target = ICloudUrlNormalizer.normalize(href)
        eventCache.entries.removeIf { ICloudUrlNormalizer.normalize(it.value.event.href) == target }
    }

    /**
     * Clear all expired entries from cache.
     */
    private fun clearExpiredEntries() {
        val now = System.currentTimeMillis()
        eventCache.entries.removeIf { now - it.value.cachedAt > cacheTtlMs }
    }

    /**
     * Clear entire cache. Useful for testing.
     */
    fun clearCache() {
        eventCache.clear()
    }

    /**
     * Get current cache size. Useful for testing.
     */
    fun cacheSize(): Int = eventCache.size

    // ═══════════════════════════════════════════════════════════════════════
    // LIST CALENDARS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * List all accessible calendars.
     */
    fun listCalendars(): ServiceResult<List<CalendarInfo>> {
        // Validate connection on first use (lazy, cached)
        val connResult = ensureConnected()
        if (connResult is ServiceResult.Error) return connResult

        return when (val result = client.listCalendars()) {
            is CalDavResult.Success -> {
                val calendars = result.data.map { cal ->
                    CalendarInfo(
                        id = cal.id,
                        name = cal.displayName,
                        color = cal.color,
                        readOnly = cal.isReadOnly,
                        supportedComponents = cal.supportedComponents
                    )
                }
                ServiceResult.Success(calendars)
            }
            is CalDavResult.Error -> {
                ServiceResult.Error(result.code, result.message)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET EVENTS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get events from a calendar within a date range.
     *
     * Two work-bounds fire here, both as a 413 (see [MAX_RETURNED_EVENTS] and the
     * expander's per-series limit): a single series expanding past the expander's
     * bound, and the assembled in-range result exceeding [MAX_RETURNED_EVENTS]. Both
     * short-circuit rather than returning a truncated or oversize list, because a
     * downstream consumer can silently corrupt an oversize JSON response. There is no
     * pagination: the caller narrows the window.
     */
    fun getEvents(calendarId: String, startDate: String, endDate: String): ServiceResult<List<EventInfo>> {
        return when (val result = client.getEvents(calendarId, startDate, endDate)) {
            is CalDavResult.Success -> {
                // Already format-validated by the client call above. Expansion and
                // the re-check below share this window, so they agree on where the
                // requested days begin and end.
                val range = dayRangeUtc(startDate, endDate)

                val events = ArrayList<EventInfo>()
                for (caldavEvent in result.data) {
                    // Cache event for future lookup (with TTL)
                    addToCache(caldavEvent.uid, caldavEvent)

                    // Parse ICS content, mirroring the window the CalDAV REPORT was
                    // built with so expansion yields the occurrences the server matched.
                    // A pathological series can trip the expander's work-bound; surface
                    // that as a 413 rather than letting it escape get_events.
                    val parsed = try {
                        parser.parseOccurrences(caldavEvent.icalData, range.startInstant, range.lastInstant)
                    } catch (e: IcsParser.ExpansionLimitException) {
                        return expansionTooLargeError()
                    }
                    for (p in parsed) {
                        val info = toEventInfo(p, caldavEvent)
                        if (!overlapsRequestedRange(info, range)) continue
                        events.add(info)
                        // Once past the cap the response would be oversize; stop and
                        // signal instead of returning a list a consumer might truncate.
                        if (events.size > MAX_RETURNED_EVENTS) {
                            return ServiceResult.Error(413, TOO_MANY_EVENTS_MESSAGE)
                        }
                    }
                }
                ServiceResult.Success(events)
            }
            is CalDavResult.Error -> {
                ServiceResult.Error(result.code, result.message)
            }
        }
    }

    /**
     * True when [event] overlaps the half-open window [range] describes.
     *
     * A CalDAV server is expected to have filtered by the REPORT's time-range
     * already, but iCloud returns all-day events whose *exclusive* DTEND lands
     * exactly on the query start. RFC 4791 §9.9 defines the overlap test as
     * `(DTSTART < end) AND (DTEND > start)`, so a DTEND that merely touches the
     * start must not match — an event ending 7/27 belongs to 7/26, not 7/27.
     * Without this check such events surface as belonging to a day they had
     * already ended before.
     *
     * Events whose dates cannot be read are kept: dropping an event the caller
     * might need is worse than passing one through.
     */
    private fun overlapsRequestedRange(event: EventInfo, range: DayRangeUtc): Boolean {
        if (event.isAllDay) {
            val start = event.startDate?.toLocalDateOrNull() ?: return true
            // EventInfo.endDate is inclusive (IcsParser subtracts a day off the
            // exclusive DTEND), so shift it back to a half-open bound.
            val endExclusive = (event.endDate?.toLocalDateOrNull() ?: start).plusDays(1)
            return start < range.endDayExclusive && endExclusive > range.startDay
        }

        val start = event.startTime?.toInstantOrNull() ?: return true
        val end = event.endTime?.toInstantOrNull() ?: start
        // A zero-length event still occupies its start instant.
        val endExclusive = if (end.isAfter(start)) end else start.plusMillis(1)

        return start.isBefore(range.endInstantExclusive) &&
            endExclusive.isAfter(range.startInstant)
    }

    private fun String.toLocalDateOrNull(): LocalDate? {
        return try {
            LocalDate.parse(this)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun String.toInstantOrNull(): Instant? {
        return try {
            Instant.parse(this)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET EVENT BY ID
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get a single event by its UID.
     * Checks cache first (with TTL), returns error if not found.
     */
    fun getEventById(eventId: String): ServiceResult<EventInfo> {
        val cached = getFromCache(eventId)
        if (cached != null) {
            val parsed = parser.parse(cached.icalData)
            if (parsed.isNotEmpty()) {
                return ServiceResult.Success(toEventInfo(parsed[0], cached))
            }
        }
        return ServiceResult.Error(404, "Event not found: $eventId")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CREATE EVENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Create a new event in a calendar.
     */
    fun createEvent(
        calendarId: String,
        summary: String,
        startTime: String? = null,
        endTime: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        isAllDay: Boolean = false,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        rrule: String? = null,
        endTimezone: String? = null,
        rdates: List<String>? = null,
        exdates: List<String>? = null,
        alarms: List<AlarmSpec>? = null
    ): ServiceResult<EventInfo> {
        // Check if calendar exists and is writable
        val calendarsResult = client.listCalendars()
        if (calendarsResult is CalDavResult.Error) {
            return ServiceResult.Error(calendarsResult.code, calendarsResult.message)
        }

        val calendars = (calendarsResult as CalDavResult.Success).data
        val calendar = calendars.find { it.id == calendarId }
            ?: return ServiceResult.Error(404, "Calendar not found: $calendarId")

        if (calendar.isReadOnly) {
            return ServiceResult.Error(403, "Calendar is read-only: ${calendar.displayName}")
        }

        // Build ICS content. CREATED + LAST-MODIFIED set to "now" on first creation
        // (RFC 5545 §3.8.7.1/.3); subsequent edits go through IcsPatcher which refreshes
        // LAST-MODIFIED and preserves CREATED.
        val now = java.time.Instant.now()
        val ics = builder.build(
            summary = summary,
            startTime = startTime,
            endTime = endTime,
            startDate = startDate,
            endDate = endDate,
            isAllDay = isAllDay,
            description = description,
            location = location,
            timezone = timezone,
            rrule = rrule,
            createdAt = now,
            lastModified = now,
            endTimezone = endTimezone,
            rdates = rdates,
            exdates = exdates,
            alarms = alarms
        )

        // Create via CalDAV
        return when (val result = client.createEvent(calendarId, ics)) {
            is CalDavResult.Success -> {
                val created = result.data
                addToCache(created.uid, created)

                // Parse back to get full event info
                val parsed = parser.parse(created.icalData)
                if (parsed.isNotEmpty()) {
                    ServiceResult.Success(toEventInfo(parsed[0], created))
                } else {
                    // Fallback: create EventInfo from input
                    ServiceResult.Success(synthesizedEventInfo(
                        created, summary, description, location, isAllDay,
                        startTime, endTime, startDate, endDate, rrule
                    ))
                }
            }
            is CalDavResult.Error -> {
                ServiceResult.Error(result.code, result.message)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UPDATE EVENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Update an existing event.
     * Only provided fields are updated; others retain their values.
     *
     * [eventId] may be either:
     *  - a self-contained handle ([EventHandle], `evt1_…`) minted by get_events /
     *    create_event — resolved directly from the server with NO cache dependency,
     *    so a fresh process / expired-TTL / different worker can still update; or
     *  - a legacy bare UID — resolved from the in-memory cache (optimization only,
     *    kept for backward-compat).
     *
     * On a 412 (stale etag: the resource advanced out from under us — shared
     * calendar edit, iCloud housekeeping, or a concurrent write), we refetch the
     * current state, re-patch onto it, and retry the PUT exactly ONCE before
     * surfacing the conflict. No infinite loop.
     */
    fun updateEvent(
        eventId: String,
        summary: String? = null,
        startTime: String? = null,
        endTime: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        isAllDay: Boolean? = null,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        rrule: String? = null,
        endTimezone: String? = null,
        rdates: List<String>? = null,
        exdates: List<String>? = null,
        alarms: List<AlarmSpec>? = null,
        scope: EventScope? = null
    ): ServiceResult<EventInfo> {
        // Fail-loud scope matrix (see [EventScope]): decode the reference, then decide
        // whether this edit touches one occurrence or the whole series before any write.
        val handle = EventHandle.decode(eventId)
        val isOccurrenceRef = handle?.isOccurrenceRef() == true
        checkWriteScope(scope, isOccurrenceRef, rrule, rdates, exdates)?.let { return it }
        when (scope) {
            EventScope.THIS_AND_FUTURE -> return catchingExpansionLimit {
                updateThisAndFuture(
                    eventId, handle!!.recurrenceId!!,
                    summary, startTime, endTime, startDate, endDate, isAllDay,
                    description, location, timezone, endTimezone, alarms
                )
            }
            EventScope.THIS_OCCURRENCE -> return catchingExpansionLimit {
                updateSingleOccurrence(
                    eventId, handle!!.recurrenceId!!,
                    summary, startTime, endTime, startDate, endDate, isAllDay,
                    description, location, timezone, endTimezone, alarms
                )
            }
            // ALL_EVENTS, or omitted on a master/legacy reference: whole-series edit
            // exactly as before.
            EventScope.ALL_EVENTS, null -> { /* fall through to the whole-series path */ }
        }

        // Resolve the base event (href + current ICS body + etag). Handle path hits
        // the server (cache-independent); legacy uid path uses the cache.
        val baseResult = resolveForWrite(eventId)
        if (baseResult is ServiceResult.Error) return baseResult
        val base = (baseResult as ServiceResult.Success).data

        // Patch + PUT against the current server body, with a single 412 refetch-and-retry
        // (see [withStaleEtagRetry]).
        return withStaleEtagRetry(base) { target ->
            // Use IcsPatcher to preserve VALARM, ATTENDEE, ORGANIZER, X-* properties.
            // If the ICS is something ical4j can't re-parse (server quirk, weird
            // CRLF/LF mix, etc.), fail loud rather than silently rebuilding a partial
            // event.
            val ics = try {
                patcher.patch(
                    existingIcs = target.icalData,
                    uid = target.uid,
                    summary = summary,
                    startTime = startTime,
                    endTime = endTime,
                    startDate = startDate,
                    endDate = endDate,
                    isAllDay = isAllDay,
                    description = description,
                    location = location,
                    timezone = timezone,
                    rrule = rrule,
                    endTimezone = endTimezone,
                    rdates = rdates,
                    exdates = exdates,
                    alarms = alarms
                )
            } catch (e: IcsPatcher.UnparseableExistingIcsException) {
                return@withStaleEtagRetry ServiceResult.Error(
                    422,
                    "Could not patch event: existing ICS is unparseable. " +
                        "This can happen with server-side line-ending quirks; " +
                        "try a full update (sending all fields) instead of a partial one."
                )
            }

            when (val result = client.updateEvent(target.href, ics, target.etag)) {
                is CalDavResult.Success -> {
                    val updated = result.data
                    addToCache(updated.uid, updated)

                    // Parse back
                    val parsedUpdated = parser.parse(updated.icalData)
                    if (parsedUpdated.isNotEmpty()) {
                        ServiceResult.Success(toEventInfo(parsedUpdated[0], updated))
                    } else {
                        // Fallback: parse the ICS we sent
                        val sentParsed = parser.parse(ics)
                        if (sentParsed.isNotEmpty()) {
                            ServiceResult.Success(toEventInfo(sentParsed[0], updated))
                        } else {
                            ServiceResult.Success(synthesizedEventInfo(
                                updated, summary ?: updated.uid, description, location,
                                isAllDay ?: false, startTime, endTime, startDate, endDate, rrule
                            ))
                        }
                    }
                }
                is CalDavResult.Error -> ServiceResult.Error(result.code, result.message)
            }
        }
    }

    /**
     * Resolve an [eventId] (handle or legacy uid) to the current [CalDavEvent] to
     * write against. Handle path fetches fresh from the server (no cache needed);
     * uid path uses the TTL cache.
     */
    private fun resolveForWrite(eventId: String): ServiceResult<CalDavEvent> {
        val handle = EventHandle.decode(eventId)
        if (handle != null) {
            return when (val r = client.getEvent(handle.href)) {
                // Patch onto the current server body, but carry the HANDLE's etag as
                // the If-Match for the first PUT — that is the version the caller
                // last saw, so a concurrent edit since the handle was minted trips a
                // 412 (then refetch-and-retry-once), rather than being silently
                // overwritten. This mirrors deleteEvent, which sends the handle's
                // etag directly. When the handle carried no etag, fall back to the
                // freshly fetched one (best-effort, matches the create path).
                is CalDavResult.Success ->
                    ServiceResult.Success(r.data.copy(etag = handle.etag ?: r.data.etag))
                is CalDavResult.Error -> ServiceResult.Error(r.code, r.message)
            }
        }
        val cached = getFromCache(eventId)
            ?: return ServiceResult.Error(404, "Event not found: $eventId")
        return ServiceResult.Success(cached)
    }

    /**
     * Run [writeOnce] against [target]; on a 412 (stale etag: the resource advanced
     * out from under us — shared-calendar edit, iCloud housekeeping, or a concurrent
     * write) refetch the current server body ONCE and re-run [writeOnce] against it,
     * then surface that second attempt's result. [writeOnce] recomputes its payload
     * purely from the [CalDavEvent] it is handed, so the retry rebuilds against the
     * fresh body (a concurrent EXDATE/exception is not clobbered) and PUTs with the
     * current etag. A non-412 result, and a failed refetch, surface the first
     * attempt's result unchanged. No more than one retry.
     */
    private fun <T> withStaleEtagRetry(
        target: CalDavEvent,
        writeOnce: (CalDavEvent) -> ServiceResult<T>
    ): ServiceResult<T> {
        val first = writeOnce(target)
        if (first !is ServiceResult.Error || first.code != 412) return first
        return when (val fresh = client.getEvent(target.href)) {
            is CalDavResult.Success -> writeOnce(fresh.data)
            is CalDavResult.Error -> first
        }
    }

    /**
     * Build an [EventInfo] by hand from a written [CalDavEvent] when parsing the ICS
     * back yields no VEVENT ("should not happen", but never surface a null/partial
     * result). A non-null [recurrenceId] mints an occurrence (`evt2_`) handle and is
     * echoed on the result; a null one mints a master (`evt1_`) handle, byte-identical
     * to the two-arg [EventHandle.encode].
     */
    private fun synthesizedEventInfo(
        event: CalDavEvent,
        summary: String,
        description: String?,
        location: String?,
        isAllDay: Boolean,
        startTime: String?,
        endTime: String?,
        startDate: String?,
        endDate: String?,
        rrule: String?,
        recurrenceId: String? = null
    ): EventInfo = EventInfo(
        uid = event.uid,
        href = event.href,
        etag = event.etag,
        handle = EventHandle.encode(event.href, event.etag, recurrenceId),
        summary = summary,
        description = description,
        location = location,
        isAllDay = isAllDay,
        startTime = startTime,
        endTime = endTime,
        startDate = startDate,
        endDate = endDate,
        rrule = rrule,
        recurrenceId = recurrenceId
    )

    // ═══════════════════════════════════════════════════════════════════════
    // DELETE EVENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Delete an event by ID.
     *
     * [eventId] may be a self-contained handle ([EventHandle], `evt1_…`) — resolved
     * from the server with NO cache dependency — or a legacy bare UID resolved from
     * the TTL cache. On a 412 (stale etag) we refetch the current etag and retry the
     * DELETE exactly ONCE before surfacing the conflict.
     */
    fun deleteEvent(eventId: String, scope: EventScope? = null): ServiceResult<Unit> {
        // Resolve href + etag. Handle path is cache-independent; uid path uses cache.
        val handle = EventHandle.decode(eventId)

        // Fail-loud scope matrix (see [EventScope]). rrule/rdates/exdates do not apply
        // to a delete, so they are passed as null.
        val isOccurrenceRef = handle?.isOccurrenceRef() == true
        checkWriteScope(scope, isOccurrenceRef, null, null, null)?.let { return it }
        when (scope) {
            EventScope.THIS_AND_FUTURE -> return catchingExpansionLimit {
                deleteThisAndFuture(eventId, handle!!.recurrenceId!!)
            }
            EventScope.THIS_OCCURRENCE -> return catchingExpansionLimit {
                deleteSingleOccurrence(eventId, handle!!.recurrenceId!!)
            }
            // ALL_EVENTS, or omitted on a master/legacy reference: delete the whole
            // resource exactly as before.
            EventScope.ALL_EVENTS, null -> { /* fall through to the whole-resource delete */ }
        }

        val href: String
        val etag: String?
        if (handle != null) {
            href = handle.href
            etag = handle.etag
        } else {
            val existing = getFromCache(eventId)
                ?: return ServiceResult.Error(404, "Event not found: $eventId")
            href = existing.href
            etag = existing.etag
        }

        fun attempt(currentEtag: String?, allowRetry: Boolean): ServiceResult<Unit> {
            return when (val result = client.deleteEvent(href, currentEtag)) {
                is CalDavResult.Success -> {
                    // Evict by href, not by eventId: on the handle path eventId is the
                    // opaque token, but the cache is keyed by UID. Evicting by href
                    // clears the entry regardless of which reference form was passed.
                    removeFromCacheByHref(href)
                    ServiceResult.Success(Unit)
                }
                is CalDavResult.Error -> {
                    if (result.code == 412 && allowRetry) {
                        // Stale etag: refetch current etag and retry the DELETE once.
                        when (val fresh = client.getEvent(href)) {
                            is CalDavResult.Success -> attempt(fresh.data.etag, allowRetry = false)
                            is CalDavResult.Error -> ServiceResult.Error(result.code, result.message)
                        }
                    } else {
                        ServiceResult.Error(result.code, result.message)
                    }
                }
            }
        }

        return attempt(etag, allowRetry = true)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SCOPED-WRITE HELPERS (single occurrence)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The fail-loud scope matrix. Returns an [ServiceResult.Error] to abort the write,
     * or null to proceed. Rejects (before any network call):
     *  - an occurrence-level scope (this_occurrence / this_and_future) carrying a
     *    series-level field (rrule / rdates / exdates) — those edit the recurrence rule;
     *  - an occurrence-level scope against a reference that is not an occurrence handle;
     *  - an omitted scope against an occurrence handle (the caller must state intent, so
     *    a single-occurrence reference never silently widens to a whole-series change).
     *
     * ALL_EVENTS, and an omitted scope on a master/legacy reference, are permitted
     * (whole-series behavior, unchanged).
     */
    private fun checkWriteScope(
        scope: EventScope?,
        isOccurrenceRef: Boolean,
        rrule: String?,
        rdates: List<String>?,
        exdates: List<String>?
    ): ServiceResult.Error? {
        when (scope) {
            EventScope.THIS_OCCURRENCE, EventScope.THIS_AND_FUTURE -> {
                if (rrule != null || rdates != null || exdates != null) {
                    return ServiceResult.Error(
                        400,
                        "Cannot change series-level recurrence fields (rrule, rdates, exdates) with scope " +
                            "${scope.token}; that scope acts on occurrences. Use scope all_events to edit the series."
                    )
                }
                if (!isOccurrenceRef) {
                    return ServiceResult.Error(
                        400,
                        "scope ${scope.token} needs an occurrence reference (one instance of a recurring series, " +
                            "as returned by get_events); this reference is a standalone event or a whole series."
                    )
                }
            }
            null -> {
                if (isOccurrenceRef) {
                    return ServiceResult.Error(
                        400,
                        "This reference is one occurrence of a recurring series. Set scope to this_occurrence, " +
                            "this_and_future, or all_events so the change does not silently affect the whole series."
                    )
                }
            }
            EventScope.ALL_EVENTS -> { /* whole series; permitted */ }
        }
        return null
    }

    /**
     * Edit an occurrence and all later ones (scope = this_and_future): truncate the master
     * so it ends at the last occurrence before [recurrenceId] (RRULE UNTIL / reduced COUNT),
     * then create a brand-new series (fresh UID) that starts at the occurrence, carries the
     * remaining recurrence, and has the patch applied. The two live in different CalDAV
     * resources (one UID per resource), so the truncated master is PUT to its href and the
     * new series is created as a fresh resource in the same calendar.
     *
     * The continuing series is created FIRST, then the master is truncated. Ordering matters
     * for durability: if the create fails, nothing has been truncated, so the original full
     * series is intact and the caller simply retries. If the create succeeds but the master
     * truncation then fails, the future occurrences exist in BOTH bodies (a visible duplicate
     * the caller can reconcile), which is recoverable, rather than silently lost. On a 412 the
     * master is refetched and the truncation RECOMPUTED onto the fresh body before the retried
     * PUT; the already-created continuing series is not recreated.
     *
     * When [recurrenceId] is the series' first occurrence the split is a no-op, so this-and-future
     * is the whole series: delegate to the ALL_EVENTS path (single PUT, no orphan resource).
     */
    private fun updateThisAndFuture(
        eventId: String,
        recurrenceId: String,
        summary: String?,
        startTime: String?,
        endTime: String?,
        startDate: String?,
        endDate: String?,
        isAllDay: Boolean?,
        description: String?,
        location: String?,
        timezone: String?,
        endTimezone: String?,
        alarms: List<AlarmSpec>?
    ): ServiceResult<EventInfo> {
        val baseResult = resolveForWrite(eventId)
        if (baseResult is ServiceResult.Error) return baseResult
        val base = (baseResult as ServiceResult.Success).data

        if (!isLiveOccurrence(base.icalData, recurrenceId)) return staleOccurrenceError()

        val split = try {
            patcher.splitSeries(
                existingIcs = base.icalData,
                recurrenceId = recurrenceId,
                summary = summary,
                startTime = startTime,
                endTime = endTime,
                startDate = startDate,
                endDate = endDate,
                isAllDay = isAllDay,
                description = description,
                location = location,
                timezone = timezone,
                endTimezone = endTimezone,
                alarms = alarms
            )
        } catch (e: IcsPatcher.FirstOccurrenceException) {
            // The occurrence is the series' first: this-and-future == whole series.
            return updateEvent(
                eventId, summary, startTime, endTime, startDate, endDate, isAllDay,
                description, location, timezone, rrule = null, endTimezone,
                rdates = null, exdates = null, alarms, scope = EventScope.ALL_EVENTS
            )
        } catch (e: IcsPatcher.NotARecurringSeriesException) {
            return notARecurringSeriesError()
        } catch (e: IcsPatcher.UnparseableExistingIcsException) {
            return unparseableOccurrenceError()
        }

        // 1. Create the continuing series. Nothing is truncated yet, so a failure here leaves the
        //    original full series untouched.
        val created = when (val c = client.createEvent(calendarIdForHref(base.href), split.newSeries)) {
            is CalDavResult.Success -> c.data
            is CalDavResult.Error -> return ServiceResult.Error(c.code, c.message)
        }
        addToCache(created.uid, created)

        // 2. Truncate the master, with a single 412 refetch-and-recompute retry.
        fun putTruncated(target: CalDavEvent, truncatedMaster: String, allowRetry: Boolean): ServiceResult<Unit> {
            return when (val put = client.updateEvent(target.href, truncatedMaster, target.etag)) {
                is CalDavResult.Success -> {
                    addToCache(put.data.uid, put.data)
                    ServiceResult.Success(Unit)
                }
                is CalDavResult.Error -> {
                    if (put.code == 412 && allowRetry) {
                        when (val fresh = client.getEvent(target.href)) {
                            is CalDavResult.Success -> {
                                val recomputed = try {
                                    patcher.truncateSeries(fresh.data.icalData, recurrenceId)
                                } catch (e: RuntimeException) {
                                    return ServiceResult.Error(put.code, put.message)
                                }
                                putTruncated(fresh.data, recomputed, allowRetry = false)
                            }
                            is CalDavResult.Error -> ServiceResult.Error(put.code, put.message)
                        }
                    } else {
                        ServiceResult.Error(put.code, put.message)
                    }
                }
            }
        }

        val truncate = putTruncated(base, split.truncatedMaster, allowRetry = true)
        if (truncate is ServiceResult.Error) return splitTruncatePartialFailure(truncate.code, truncate.message)

        // The edited occurrence is the continuing series' first instance; report that series.
        val parsed = parser.parse(created.icalData)
        return if (parsed.isNotEmpty()) {
            ServiceResult.Success(toEventInfo(parsed[0], created))
        } else {
            ServiceResult.Success(synthesizedEventInfo(
                created, summary ?: created.uid, description, location,
                isAllDay ?: false, startTime, endTime, startDate, endDate, rrule = null
            ))
        }
    }

    /**
     * Cancel an occurrence and all later ones (scope = this_and_future): truncate the master
     * so it ends at the last occurrence before [recurrenceId] (RRULE UNTIL / reduced COUNT) and
     * PUT it back. This is a PUT of the master, not a resource DELETE, and creates nothing.
     * Same recompute-inside-retry on a 412 as [updateThisAndFuture].
     *
     * When [recurrenceId] is the series' first occurrence, cancelling this-and-future cancels
     * the whole series: delegate to the ALL_EVENTS delete (a resource DELETE).
     */
    private fun deleteThisAndFuture(eventId: String, recurrenceId: String): ServiceResult<Unit> {
        val baseResult = resolveForWrite(eventId)
        if (baseResult is ServiceResult.Error) return baseResult
        val base = (baseResult as ServiceResult.Success).data

        // Not routed through withStaleEtagRetry: the first-occurrence branch delegates to
        // deleteEvent(ALL_EVENTS), which owns its own 412 retry. Wrapping it in the generic
        // helper would let the outer helper re-drive that delegated DELETE on a 412, exceeding
        // the at-most-one-retry contract. The retry here is scoped to just the master PUT.
        fun attempt(target: CalDavEvent, allowRetry: Boolean): ServiceResult<Unit> {
            if (!isLiveOccurrence(target.icalData, recurrenceId)) return staleOccurrenceError()

            val truncated = try {
                patcher.truncateSeries(target.icalData, recurrenceId)
            } catch (e: IcsPatcher.FirstOccurrenceException) {
                return deleteEvent(eventId, scope = EventScope.ALL_EVENTS)
            } catch (e: IcsPatcher.NotARecurringSeriesException) {
                return notARecurringSeriesError()
            } catch (e: IcsPatcher.UnparseableExistingIcsException) {
                return unparseableOccurrenceError()
            }

            return when (val put = client.updateEvent(target.href, truncated, target.etag)) {
                is CalDavResult.Success -> {
                    addToCache(put.data.uid, put.data)
                    ServiceResult.Success(Unit)
                }
                is CalDavResult.Error ->
                    if (put.code == 412 && allowRetry) {
                        when (val fresh = client.getEvent(target.href)) {
                            is CalDavResult.Success -> attempt(fresh.data, allowRetry = false)
                            is CalDavResult.Error -> ServiceResult.Error(put.code, put.message)
                        }
                    } else {
                        ServiceResult.Error(put.code, put.message)
                    }
            }
        }

        return attempt(base, allowRetry = true)
    }

    private fun splitTruncatePartialFailure(code: Int, message: String): ServiceResult.Error = ServiceResult.Error(
        code,
        "The continuing series for this and later occurrences was created, but truncating the original " +
            "series failed: $message. Those occurrences now exist in both series (a duplicate). Re-list " +
            "with get_events and delete the duplicate future occurrences from the original series."
    )

    /**
     * The calendar id owning [href]. An event href is `…/<calendar-collection>/<uid>.ics`, and a
     * calendar's id is its collection segment, so the id is the collection segment of the href's
     * parent path. Reuses [extractCalendarIdFromHref] (last non-empty segment) on that parent.
     */
    private fun calendarIdForHref(href: String): String =
        extractCalendarIdFromHref(href.substringBeforeLast('/'))

    /**
     * Edit a single occurrence (scope = this_occurrence): read the master resource,
     * write it back with a RECURRENCE-ID exception carrying the patch. The master's
     * DTSTART/RRULE are never touched. Mirrors [updateEvent]'s recompute-inside-retry:
     * on a 412 the master is refetched and the exception RE-APPLIED to the fresh body
     * before the retried PUT, so a concurrently added EXDATE/exception is not clobbered.
     */
    private fun updateSingleOccurrence(
        eventId: String,
        recurrenceId: String,
        summary: String?,
        startTime: String?,
        endTime: String?,
        startDate: String?,
        endDate: String?,
        isAllDay: Boolean?,
        description: String?,
        location: String?,
        timezone: String?,
        endTimezone: String?,
        alarms: List<AlarmSpec>?
    ): ServiceResult<EventInfo> {
        val baseResult = resolveForWrite(eventId)
        if (baseResult is ServiceResult.Error) return baseResult
        val base = (baseResult as ServiceResult.Success).data

        return withStaleEtagRetry(base) { target ->
            // The RECURRENCE-ID must still name a live instance of the current series.
            if (!isLiveOccurrence(target.icalData, recurrenceId)) return@withStaleEtagRetry staleOccurrenceError()

            val ics = try {
                patcher.patchOccurrence(
                    existingIcs = target.icalData,
                    recurrenceId = recurrenceId,
                    summary = summary,
                    startTime = startTime,
                    endTime = endTime,
                    startDate = startDate,
                    endDate = endDate,
                    isAllDay = isAllDay,
                    description = description,
                    location = location,
                    timezone = timezone,
                    endTimezone = endTimezone,
                    alarms = alarms
                )
            } catch (e: IcsPatcher.NotARecurringSeriesException) {
                return@withStaleEtagRetry notARecurringSeriesError()
            } catch (e: IcsPatcher.UnparseableExistingIcsException) {
                return@withStaleEtagRetry unparseableOccurrenceError()
            }

            when (val result = client.updateEvent(target.href, ics, target.etag)) {
                is CalDavResult.Success -> {
                    val updated = result.data
                    addToCache(updated.uid, updated)
                    ServiceResult.Success(occurrenceEventInfo(updated, ics, recurrenceId))
                }
                is CalDavResult.Error -> ServiceResult.Error(result.code, result.message)
            }
        }
    }

    /**
     * Cancel a single occurrence (scope = this_occurrence): read the master resource,
     * write it back with an EXDATE for the occurrence (and any exception dropped). This
     * is a PUT of the master, not a DELETE of the resource. Same recompute-inside-retry
     * as [updateSingleOccurrence].
     */
    private fun deleteSingleOccurrence(eventId: String, recurrenceId: String): ServiceResult<Unit> {
        val baseResult = resolveForWrite(eventId)
        if (baseResult is ServiceResult.Error) return baseResult
        val base = (baseResult as ServiceResult.Success).data

        return withStaleEtagRetry(base) { target ->
            if (!isLiveOccurrence(target.icalData, recurrenceId)) return@withStaleEtagRetry staleOccurrenceError()

            val ics = try {
                patcher.exdateOccurrence(target.icalData, recurrenceId)
            } catch (e: IcsPatcher.NotARecurringSeriesException) {
                return@withStaleEtagRetry notARecurringSeriesError()
            } catch (e: IcsPatcher.UnparseableExistingIcsException) {
                return@withStaleEtagRetry unparseableOccurrenceError()
            }

            when (val result = client.updateEvent(target.href, ics, target.etag)) {
                is CalDavResult.Success -> {
                    addToCache(result.data.uid, result.data)
                    ServiceResult.Success(Unit)
                }
                is CalDavResult.Error -> ServiceResult.Error(result.code, result.message)
            }
        }
    }

    private fun staleOccurrenceError(): ServiceResult.Error = ServiceResult.Error(
        409,
        "That occurrence is no longer part of the series (already changed, cancelled, or never existed). " +
            "Re-list with get_events for a current reference."
    )

    private fun notARecurringSeriesError(): ServiceResult.Error = ServiceResult.Error(
        400,
        "This event is not a recurring series, so a single-occurrence scope does not apply. " +
            "Use scope all_events (or omit scope) to edit it."
    )

    private fun unparseableOccurrenceError(): ServiceResult.Error = ServiceResult.Error(
        422,
        "Could not edit the occurrence: the existing event data could not be parsed."
    )

    private fun expansionTooLargeError(): ServiceResult.Error =
        ServiceResult.Error(413, EXPANSION_TOO_LARGE_MESSAGE)

    /**
     * Run an occurrence-scoped write, mapping an expander work-bound abort (from the
     * occurrence-liveness check or a this-and-future split/truncate over a pathologically
     * dense series) to a clean 413 rather than letting it escape as an INTERNAL_ERROR.
     * Both bridge types are caught here so the root module never touches the ical4j-confined
     * core exception (see the ical4j confinement rule).
     */
    private inline fun <T> catchingExpansionLimit(block: () -> ServiceResult<T>): ServiceResult<T> =
        try {
            block()
        } catch (e: IcsParser.ExpansionLimitException) {
            expansionTooLargeError()
        } catch (e: IcsPatcher.ExpansionLimitException) {
            expansionTooLargeError()
        }

    /**
     * The occurrence written into [sentIcs] (server echo [updated.icalData] preferred),
     * as an [EventInfo]. Built EXPLICITLY from the occurrence matching [recurrenceId],
     * NOT from the first parsed VEVENT — that is the master, whose fields and (evt1_)
     * handle would misreport a single-occurrence edit. [toEventInfo] mints an evt2_
     * handle from the occurrence's own RECURRENCE-ID.
     */
    private fun occurrenceEventInfo(updated: CalDavEvent, sentIcs: String, recurrenceId: String): EventInfo {
        val occurrence = findOccurrence(updated.icalData, recurrenceId)
            ?: findOccurrence(sentIcs, recurrenceId)
        return if (occurrence != null) {
            toEventInfo(occurrence, updated)
        } else {
            // Should not happen (we just wrote this occurrence), but never return a
            // master-shaped result: synthesize a minimal occurrence EventInfo.
            synthesizedEventInfo(
                updated, summary = "", description = null, location = null, isAllDay = false,
                startTime = null, endTime = null, startDate = null, endDate = null,
                rrule = null, recurrenceId = recurrenceId
            )
        }
    }

    /** True if [recurrenceId] resolves to a live occurrence of the series in [ics]. */
    private fun isLiveOccurrence(ics: String, recurrenceId: String): Boolean =
        findOccurrence(ics, recurrenceId) != null

    /**
     * The expanded occurrence in [ics] whose RECURRENCE-ID equals [recurrenceId], or
     * null if that instant is not a live occurrence (past a COUNT/UNTIL bound, EXDATE'd,
     * or never in the set). Expands a narrow UTC window around the occurrence's day
     * (±1 day absorbs any host-zone shift) so the check does not depend on the caller's
     * time zone.
     */
    private fun findOccurrence(ics: String, recurrenceId: String): ParsedEvent? {
        val (start, end) = occurrenceWindow(recurrenceId) ?: return null
        return parser.parseOccurrences(ics, start, end).firstOrNull { it.recurrenceId == recurrenceId }
    }

    /** A [start, end) UTC window bracketing the day named by a RECURRENCE-ID wire string. */
    private fun occurrenceWindow(recurrenceId: String): Pair<Instant, Instant>? {
        val day = try {
            LocalDate.parse(recurrenceId.take(8), DateTimeFormatter.BASIC_ISO_DATE)
        } catch (_: DateTimeParseException) {
            return null
        }
        val start = day.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val end = day.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()
        return start to end
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun toEventInfo(parsed: ParsedEvent, caldav: CalDavEvent): EventInfo {
        return EventInfo(
            uid = parsed.uid,
            href = caldav.href,
            etag = caldav.etag,
            handle = EventHandle.encode(caldav.href, caldav.etag, parsed.recurrenceId),
            summary = parsed.summary,
            description = parsed.description,
            location = parsed.location,
            isAllDay = parsed.isAllDay,
            startTime = parsed.startTime,
            endTime = parsed.endTime,
            startDate = parsed.startDate,
            endDate = parsed.endDate,
            rrule = parsed.rrule,
            recurrenceId = parsed.recurrenceId,
            status = parsed.status,
            url = parsed.url,
            categories = parsed.categories,
            priority = parsed.priority,
            organizer = parsed.organizer,
            attendeeCount = parsed.attendeeCount,
            alarms = parsed.alarms
        )
    }

    companion object {
        /**
         * Hard cap on the number of events [getEvents] assembles into one response.
         * A larger result can overflow a downstream consumer's response size and be
         * silently truncated into invalid JSON, so the service returns a 413 instead
         * of an oversize (or truncated) list. No pagination: the caller narrows the
         * window (the expansion work-bound in the expander is the per-series analog).
         */
        const val MAX_RETURNED_EVENTS = 1000

        private const val TOO_MANY_EVENTS_MESSAGE =
            "Too many events in range (exceeds $MAX_RETURNED_EVENTS). Narrow the date range " +
                "(try a week or a month at a time)."

        private const val EXPANSION_TOO_LARGE_MESSAGE =
            "A recurring event in this range expands to too many occurrences. Narrow the date range " +
                "(try a week or a month at a time)."
    }
}
