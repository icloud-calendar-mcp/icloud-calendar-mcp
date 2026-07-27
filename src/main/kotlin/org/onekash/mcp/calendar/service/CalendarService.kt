package org.onekash.mcp.calendar.service

import org.onekash.mcp.calendar.caldav.*
import org.onekash.mcp.calendar.ics.AlarmSpec
import org.onekash.mcp.calendar.ics.IcsBuilder
import org.onekash.mcp.calendar.ics.IcsPatcher
import org.onekash.mcp.calendar.ics.IcsParser
import org.onekash.mcp.calendar.ics.ParsedAlarm
import org.onekash.mcp.calendar.ics.ParsedEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
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
     */
    fun getEvents(calendarId: String, startDate: String, endDate: String): ServiceResult<List<EventInfo>> {
        return when (val result = client.getEvents(calendarId, startDate, endDate)) {
            is CalDavResult.Success -> {
                // Already format-validated by the client call above.
                val startDay = LocalDate.parse(startDate)
                val endDay = LocalDate.parse(endDate)

                // Mirror the window the CalDAV REPORT was built with so expansion
                // yields exactly the occurrences the server matched.
                val rangeStart = startDay.atStartOfDay(ZoneOffset.UTC).toInstant()
                val rangeEnd = endDay.atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC)

                // end_date names a day the caller wants included, so the exclusive
                // bound for re-checking what came back is the day after it.
                val queryEndExclusive = endDay.plusDays(1)

                val events = result.data.flatMap { caldavEvent ->
                    // Cache event for future lookup (with TTL)
                    addToCache(caldavEvent.uid, caldavEvent)

                    // Parse ICS content
                    val parsed = parser.parseOccurrences(caldavEvent.icalData, rangeStart, rangeEnd)
                    parsed.map { p ->
                        toEventInfo(p, caldavEvent)
                    }
                }.filter { overlapsRequestedRange(it, startDay, queryEndExclusive) }
                ServiceResult.Success(events)
            }
            is CalDavResult.Error -> {
                ServiceResult.Error(result.code, result.message)
            }
        }
    }

    /**
     * True when [event] overlaps the half-open range
     * [[queryStart], [queryEndExclusive]).
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
    private fun overlapsRequestedRange(
        event: EventInfo,
        queryStart: LocalDate,
        queryEndExclusive: LocalDate
    ): Boolean {
        if (event.isAllDay) {
            val start = event.startDate?.toLocalDateOrNull() ?: return true
            // EventInfo.endDate is inclusive (IcsParser subtracts a day off the
            // exclusive DTEND), so shift it back to a half-open bound.
            val endExclusive = (event.endDate?.toLocalDateOrNull() ?: start).plusDays(1)
            return start < queryEndExclusive && endExclusive > queryStart
        }

        val start = event.startTime?.toInstantOrNull() ?: return true
        val end = event.endTime?.toInstantOrNull() ?: start
        // A zero-length event still occupies its start instant.
        val endExclusive = if (end.isAfter(start)) end else start.plusMillis(1)

        return start.isBefore(queryEndExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()) &&
            endExclusive.isAfter(queryStart.atStartOfDay(ZoneOffset.UTC).toInstant())
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
                    ServiceResult.Success(EventInfo(
                        uid = created.uid,
                        href = created.href,
                        etag = created.etag,
                        handle = EventHandle.encode(created.href, created.etag),
                        summary = summary,
                        description = description,
                        location = location,
                        isAllDay = isAllDay,
                        startTime = startTime,
                        endTime = endTime,
                        startDate = startDate,
                        endDate = endDate,
                        rrule = rrule
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
        alarms: List<AlarmSpec>? = null
    ): ServiceResult<EventInfo> {
        // Resolve the base event (href + current ICS body + etag). Handle path hits
        // the server (cache-independent); legacy uid path uses the cache.
        val baseResult = resolveForWrite(eventId)
        if (baseResult is ServiceResult.Error) return baseResult
        val base = (baseResult as ServiceResult.Success).data

        // Patch + PUT, with a single 412 refetch-and-retry. `allowRetry = true` on
        // the first attempt only.
        fun patchAndPut(target: CalDavEvent, allowRetry: Boolean): ServiceResult<EventInfo> {
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
                return ServiceResult.Error(
                    422,
                    "Could not patch event: existing ICS is unparseable. " +
                        "This can happen with server-side line-ending quirks; " +
                        "try a full update (sending all fields) instead of a partial one."
                )
            }

            return when (val result = client.updateEvent(target.href, ics, target.etag)) {
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
                            ServiceResult.Success(EventInfo(
                                uid = updated.uid,
                                href = updated.href,
                                etag = updated.etag,
                                handle = EventHandle.encode(updated.href, updated.etag),
                                summary = summary ?: updated.uid,
                                description = description,
                                location = location,
                                isAllDay = isAllDay ?: false,
                                startTime = startTime,
                                endTime = endTime,
                                startDate = startDate,
                                endDate = endDate,
                                rrule = rrule
                            ))
                        }
                    }
                }
                is CalDavResult.Error -> {
                    if (result.code == 412 && allowRetry) {
                        // Stale etag: refetch the current server state and retry once.
                        when (val fresh = client.getEvent(target.href)) {
                            is CalDavResult.Success -> patchAndPut(fresh.data, allowRetry = false)
                            is CalDavResult.Error -> ServiceResult.Error(result.code, result.message)
                        }
                    } else {
                        ServiceResult.Error(result.code, result.message)
                    }
                }
            }
        }

        return patchAndPut(base, allowRetry = true)
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
    fun deleteEvent(eventId: String): ServiceResult<Unit> {
        // Resolve href + etag. Handle path is cache-independent; uid path uses cache.
        val handle = EventHandle.decode(eventId)
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
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun toEventInfo(parsed: ParsedEvent, caldav: CalDavEvent): EventInfo {
        return EventInfo(
            uid = parsed.uid,
            href = caldav.href,
            etag = caldav.etag,
            handle = EventHandle.encode(caldav.href, caldav.etag),
            summary = parsed.summary,
            description = parsed.description,
            location = parsed.location,
            isAllDay = parsed.isAllDay,
            startTime = parsed.startTime,
            endTime = parsed.endTime,
            startDate = parsed.startDate,
            endDate = parsed.endDate,
            rrule = parsed.rrule,
            status = parsed.status,
            url = parsed.url,
            categories = parsed.categories,
            priority = parsed.priority,
            organizer = parsed.organizer,
            attendeeCount = parsed.attendeeCount,
            alarms = parsed.alarms
        )
    }
}
