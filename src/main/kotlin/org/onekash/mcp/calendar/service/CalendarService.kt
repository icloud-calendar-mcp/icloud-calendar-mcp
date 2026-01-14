package org.onekash.mcp.calendar.service

import org.onekash.mcp.calendar.caldav.*
import org.onekash.mcp.calendar.ics.IcsBuilder
import org.onekash.mcp.calendar.ics.IcsParser
import org.onekash.mcp.calendar.ics.ParsedEvent
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
    val readOnly: Boolean
)

/**
 * Event info for MCP responses.
 * Combines parsed event data with CalDAV metadata.
 */
data class EventInfo(
    val uid: String,
    val href: String,
    val etag: String?,
    val summary: String,
    val description: String?,
    val location: String?,
    val isAllDay: Boolean,
    val startTime: String?,
    val endTime: String?,
    val startDate: String?,
    val endDate: String?,
    val rrule: String?
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
    private val cacheTtlMs: Long = 5 * 60 * 1000L,  // 5 minutes default
    private val maxCacheSize: Int = 1000
) {
    // Thread-safe cache with TTL support
    private val eventCache = ConcurrentHashMap<String, CachedEvent>()

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
        return when (val result = client.listCalendars()) {
            is CalDavResult.Success -> {
                val calendars = result.data.map { cal ->
                    CalendarInfo(
                        id = cal.id,
                        name = cal.displayName,
                        color = cal.color,
                        readOnly = cal.isReadOnly
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
                val events = result.data.flatMap { caldavEvent ->
                    // Cache event for future lookup (with TTL)
                    addToCache(caldavEvent.uid, caldavEvent)

                    // Parse ICS content
                    val parsed = parser.parse(caldavEvent.icalData)
                    parsed.map { p ->
                        toEventInfo(p, caldavEvent)
                    }
                }
                ServiceResult.Success(events)
            }
            is CalDavResult.Error -> {
                ServiceResult.Error(result.code, result.message)
            }
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
        rrule: String? = null
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

        // Build ICS content
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
            rrule = rrule
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
        rrule: String? = null
    ): ServiceResult<EventInfo> {
        // Find existing event (checks TTL)
        val existing = getFromCache(eventId)
            ?: return ServiceResult.Error(404, "Event not found: $eventId")

        // Parse existing event to get current values
        val parsed = parser.parse(existing.icalData)
        if (parsed.isEmpty()) {
            return ServiceResult.Error(500, "Could not parse existing event")
        }

        val current = parsed[0]

        // Build updated ICS, merging new values with existing
        val effectiveIsAllDay = isAllDay ?: current.isAllDay
        val ics = builder.build(
            uid = eventId,
            summary = summary ?: current.summary,
            startTime = if (!effectiveIsAllDay) (startTime ?: current.startTime) else null,
            endTime = if (!effectiveIsAllDay) (endTime ?: current.endTime) else null,
            startDate = if (effectiveIsAllDay) (startDate ?: current.startDate) else null,
            endDate = if (effectiveIsAllDay) (endDate ?: current.endDate) else null,
            isAllDay = effectiveIsAllDay,
            description = description ?: current.description,
            location = location ?: current.location,
            timezone = timezone,
            rrule = rrule ?: current.rrule
        )

        // Update via CalDAV
        return when (val result = client.updateEvent(existing.href, ics, existing.etag)) {
            is CalDavResult.Success -> {
                val updated = result.data
                addToCache(updated.uid, updated)

                // Parse back
                val parsedUpdated = parser.parse(updated.icalData)
                if (parsedUpdated.isNotEmpty()) {
                    ServiceResult.Success(toEventInfo(parsedUpdated[0], updated))
                } else {
                    ServiceResult.Success(EventInfo(
                        uid = updated.uid,
                        href = updated.href,
                        etag = updated.etag,
                        summary = summary ?: current.summary,
                        description = description ?: current.description,
                        location = location ?: current.location,
                        isAllDay = effectiveIsAllDay,
                        startTime = if (!effectiveIsAllDay) (startTime ?: current.startTime) else null,
                        endTime = if (!effectiveIsAllDay) (endTime ?: current.endTime) else null,
                        startDate = if (effectiveIsAllDay) (startDate ?: current.startDate) else null,
                        endDate = if (effectiveIsAllDay) (endDate ?: current.endDate) else null,
                        rrule = rrule ?: current.rrule
                    ))
                }
            }
            is CalDavResult.Error -> {
                ServiceResult.Error(result.code, result.message)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DELETE EVENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Delete an event by ID.
     */
    fun deleteEvent(eventId: String): ServiceResult<Unit> {
        val existing = getFromCache(eventId)
            ?: return ServiceResult.Error(404, "Event not found: $eventId")

        return when (val result = client.deleteEvent(existing.href, existing.etag)) {
            is CalDavResult.Success -> {
                removeFromCache(eventId)
                ServiceResult.Success(Unit)
            }
            is CalDavResult.Error -> {
                ServiceResult.Error(result.code, result.message)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun toEventInfo(parsed: ParsedEvent, caldav: CalDavEvent): EventInfo {
        return EventInfo(
            uid = parsed.uid,
            href = caldav.href,
            etag = caldav.etag,
            summary = parsed.summary,
            description = parsed.description,
            location = parsed.location,
            isAllDay = parsed.isAllDay,
            startTime = parsed.startTime,
            endTime = parsed.endTime,
            startDate = parsed.startDate,
            endDate = parsed.endDate,
            rrule = parsed.rrule
        )
    }
}
