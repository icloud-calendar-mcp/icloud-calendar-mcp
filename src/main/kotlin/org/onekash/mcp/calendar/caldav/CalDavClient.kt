package org.onekash.mcp.calendar.caldav

/**
 * Credentials for CalDAV authentication.
 *
 * For iCloud, this is the Apple ID and an app-specific password.
 */
data class CalDavCredentials(
    val username: String,
    val password: String
) {
    /** True if credentials are valid (non-blank) */
    val isValid: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    /** Masks password in toString for logging safety */
    override fun toString(): String {
        return "CalDavCredentials(username=$username, password=***)"
    }
}

/**
 * Interface for CalDAV client operations.
 *
 * Implementations handle the HTTP communication with CalDAV servers
 * (iCloud, Google Calendar, etc.). All operations return CalDavResult
 * for type-safe error handling.
 */
interface CalDavClient {

    /**
     * List all calendars accessible by this client.
     *
     * Performs CalDAV discovery:
     * 1. PROPFIND on root to get current-user-principal
     * 2. PROPFIND on principal to get calendar-home-set
     * 3. PROPFIND on calendar-home-set to list calendars
     *
     * @return List of calendars or error
     */
    fun listCalendars(): CalDavResult<List<CalDavCalendar>>

    /**
     * Get events from a calendar within a date range.
     *
     * Uses REPORT with calendar-query to fetch events.
     *
     * @param calendarId Calendar ID (from CalDavCalendar.id)
     * @param startDate Start date (YYYY-MM-DD format)
     * @param endDate End date (YYYY-MM-DD format)
     * @return List of events in the range or error
     */
    fun getEvents(calendarId: String, startDate: String, endDate: String): CalDavResult<List<CalDavEvent>>

    /**
     * Create a new event in a calendar.
     *
     * Uses PUT to create a new .ics resource.
     *
     * @param calendarId Calendar to create event in
     * @param icalData Complete ICS data for the event
     * @return Created event with server-assigned etag, or error
     */
    fun createEvent(calendarId: String, icalData: String): CalDavResult<CalDavEvent>

    /**
     * Update an existing event.
     *
     * Uses PUT with If-Match header for conditional update.
     *
     * @param href Event href (from CalDavEvent.href)
     * @param icalData Updated ICS data
     * @param etag Current etag for optimistic concurrency (optional)
     * @return Updated event with new etag, or error
     */
    fun updateEvent(href: String, icalData: String, etag: String?): CalDavResult<CalDavEvent>

    /**
     * Delete an event.
     *
     * Uses DELETE with optional If-Match header.
     *
     * @param href Event href to delete
     * @param etag Current etag for conditional delete (optional)
     * @return Success or error
     */
    fun deleteEvent(href: String, etag: String?): CalDavResult<Unit>
}
