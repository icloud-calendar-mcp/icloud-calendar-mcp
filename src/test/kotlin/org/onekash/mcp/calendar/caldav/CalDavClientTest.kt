package org.onekash.mcp.calendar.caldav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Tests for CalDavClient interface contract.
 *
 * These tests verify:
 * - Interface method signatures
 * - Result type handling
 * - Credentials validation
 */
class CalDavClientTest {

    // ═══════════════════════════════════════════════════════════════════
    // CREDENTIALS TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `CalDavCredentials stores username and password`() {
        val creds = CalDavCredentials(
            username = "user@icloud.com",
            password = "app-specific-password"
        )

        assertEquals("user@icloud.com", creds.username)
        assertEquals("app-specific-password", creds.password)
    }

    @Test
    fun `CalDavCredentials masks password in toString`() {
        val creds = CalDavCredentials(
            username = "user@icloud.com",
            password = "secret-password-123"
        )

        val str = creds.toString()
        assertFalse(str.contains("secret-password-123"))
        assertTrue(str.contains("user@icloud.com"))
        assertTrue(str.contains("***"))
    }

    @Test
    fun `CalDavCredentials isValid returns true for non-blank values`() {
        val creds = CalDavCredentials("user", "pass")
        assertTrue(creds.isValid)
    }

    @Test
    fun `CalDavCredentials isValid returns false for blank username`() {
        val creds = CalDavCredentials("", "pass")
        assertFalse(creds.isValid)
    }

    @Test
    fun `CalDavCredentials isValid returns false for blank password`() {
        val creds = CalDavCredentials("user", "")
        assertFalse(creds.isValid)
    }

    @Test
    fun `CalDavCredentials isValid returns false for whitespace only`() {
        val creds = CalDavCredentials("   ", "pass")
        assertFalse(creds.isValid)
    }

    // ═══════════════════════════════════════════════════════════════════
    // MOCK CLIENT TESTS (Verify interface contract)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `mock client can return Success for listCalendars`() {
        val client = MockCalDavClient()
        client.calendarsToReturn = listOf(
            CalDavCalendar(
                id = "home",
                href = "/home/",
                url = "https://caldav.example.com/home/",
                displayName = "Home",
                color = "#FF0000",
                ctag = "ctag1"
            )
        )

        val result = client.listCalendars()

        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Home", result.data[0].displayName)
    }

    @Test
    fun `mock client can return Error for listCalendars`() {
        val client = MockCalDavClient()
        client.errorToReturn = CalDavResult.Error(401, "Unauthorized")

        val result = client.listCalendars()

        assertIs<CalDavResult.Error>(result)
        assertEquals(401, result.code)
    }

    @Test
    fun `mock client can return events for getEvents`() {
        val client = MockCalDavClient()
        val event = CalDavEvent(
            uid = "uid1@test",
            href = "/cal/event1.ics",
            url = "https://caldav.example.com/cal/event1.ics",
            etag = "\"e1\"",
            icalData = "BEGIN:VCALENDAR..."
        )
        client.eventsToReturn = listOf(event)

        val result = client.getEvents("home", "2025-01-01", "2025-01-31")

        assertIs<CalDavResult.Success<List<CalDavEvent>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("uid1@test", result.data[0].uid)
    }

    @Test
    fun `mock client can create event`() {
        val client = MockCalDavClient()
        val newEvent = CalDavEvent(
            uid = "new-uid@test",
            href = "/cal/new-event.ics",
            url = "https://caldav.example.com/cal/new-event.ics",
            etag = "\"new-etag\"",
            icalData = "BEGIN:VCALENDAR..."
        )
        client.createdEventToReturn = newEvent

        val result = client.createEvent("home", "BEGIN:VCALENDAR...")

        assertIs<CalDavResult.Success<CalDavEvent>>(result)
        assertEquals("new-uid@test", result.data.uid)
    }

    @Test
    fun `mock client can update event`() {
        val client = MockCalDavClient()
        val updatedEvent = CalDavEvent(
            uid = "existing-uid@test",
            href = "/cal/event.ics",
            url = "https://caldav.example.com/cal/event.ics",
            etag = "\"updated-etag\"",
            icalData = "BEGIN:VCALENDAR..."
        )
        client.updatedEventToReturn = updatedEvent

        val result = client.updateEvent("/cal/event.ics", "BEGIN:VCALENDAR...", "\"old-etag\"")

        assertIs<CalDavResult.Success<CalDavEvent>>(result)
        assertEquals("\"updated-etag\"", result.data.etag)
    }

    @Test
    fun `mock client can delete event`() {
        val client = MockCalDavClient()
        client.deleteSuccess = true

        val result = client.deleteEvent("/cal/event.ics", "\"etag\"")

        assertIs<CalDavResult.Success<Unit>>(result)
    }

    @Test
    fun `mock client delete can fail with 404`() {
        val client = MockCalDavClient()
        client.errorToReturn = CalDavResult.Error(404, "Not Found")

        val result = client.deleteEvent("/cal/missing.ics", null)

        assertIs<CalDavResult.Error>(result)
        assertTrue(result.isNotFound)
    }

    @Test
    fun `mock client tracks method calls`() {
        val client = MockCalDavClient()

        client.listCalendars()
        client.getEvents("cal1", "2025-01-01", "2025-01-31")
        client.createEvent("cal1", "ics...")
        client.updateEvent("/href", "ics...", null)
        client.deleteEvent("/href", "\"etag\"")

        assertEquals(1, client.listCalendarsCalled)
        assertEquals(1, client.getEventsCalled)
        assertEquals(1, client.createEventCalled)
        assertEquals(1, client.updateEventCalled)
        assertEquals(1, client.deleteEventCalled)
    }

    // ═══════════════════════════════════════════════════════════════════
    // DATE RANGE TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `getEvents records correct date range`() {
        val client = MockCalDavClient()

        client.getEvents("home", "2025-06-01", "2025-06-30")

        assertEquals("home", client.lastCalendarId)
        assertEquals("2025-06-01", client.lastStartDate)
        assertEquals("2025-06-30", client.lastEndDate)
    }
}

/**
 * Mock implementation for testing the interface contract.
 */
class MockCalDavClient : CalDavClient {

    // Results to return
    var calendarsToReturn: List<CalDavCalendar> = emptyList()
    var eventsToReturn: List<CalDavEvent> = emptyList()
    var createdEventToReturn: CalDavEvent? = null
    var updatedEventToReturn: CalDavEvent? = null
    var deleteSuccess: Boolean = true
    var errorToReturn: CalDavResult.Error? = null

    // Call tracking
    var listCalendarsCalled = 0
    var getEventsCalled = 0
    var createEventCalled = 0
    var updateEventCalled = 0
    var deleteEventCalled = 0

    // Last parameters
    var lastCalendarId: String? = null
    var lastStartDate: String? = null
    var lastEndDate: String? = null

    override fun listCalendars(): CalDavResult<List<CalDavCalendar>> {
        listCalendarsCalled++
        return errorToReturn ?: CalDavResult.Success(calendarsToReturn)
    }

    override fun getEvents(calendarId: String, startDate: String, endDate: String): CalDavResult<List<CalDavEvent>> {
        getEventsCalled++
        lastCalendarId = calendarId
        lastStartDate = startDate
        lastEndDate = endDate
        return errorToReturn ?: CalDavResult.Success(eventsToReturn)
    }

    override fun createEvent(calendarId: String, icalData: String): CalDavResult<CalDavEvent> {
        createEventCalled++
        return errorToReturn ?: createdEventToReturn?.let { CalDavResult.Success(it) }
            ?: CalDavResult.Error(500, "No event configured")
    }

    override fun updateEvent(href: String, icalData: String, etag: String?): CalDavResult<CalDavEvent> {
        updateEventCalled++
        return errorToReturn ?: updatedEventToReturn?.let { CalDavResult.Success(it) }
            ?: CalDavResult.Error(500, "No event configured")
    }

    override fun deleteEvent(href: String, etag: String?): CalDavResult<Unit> {
        deleteEventCalled++
        return errorToReturn ?: if (deleteSuccess) CalDavResult.Success(Unit) else CalDavResult.Error(500, "Delete failed")
    }
}
