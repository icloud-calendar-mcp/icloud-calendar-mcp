package org.onekash.mcp.calendar.caldav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for CalDAV data models.
 *
 * Tests verify:
 * - CalDavResult sealed class hierarchy
 * - CalDavCalendar data class properties
 * - CalDavEvent data class properties
 * - Helper extension functions
 */
class CalDavModelsTest {

    // ═══════════════════════════════════════════════════════════════════
    // CalDavResult TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `CalDavResult Success contains data`() {
        val result: CalDavResult<String> = CalDavResult.Success("test data")

        assertIs<CalDavResult.Success<String>>(result)
        assertEquals("test data", result.data)
    }

    @Test
    fun `CalDavResult Error contains code and message`() {
        val result: CalDavResult<String> = CalDavResult.Error(401, "Unauthorized")

        assertIs<CalDavResult.Error>(result)
        assertEquals(401, result.code)
        assertEquals("Unauthorized", result.message)
        assertFalse(result.isRetryable)
    }

    @Test
    fun `CalDavResult Error can be marked retryable`() {
        val result = CalDavResult.Error(503, "Service Unavailable", isRetryable = true)

        assertTrue(result.isRetryable)
    }

    @Test
    fun `CalDavResult isSuccess returns true for Success`() {
        val result: CalDavResult<Int> = CalDavResult.Success(42)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `CalDavResult isSuccess returns false for Error`() {
        val result: CalDavResult<Int> = CalDavResult.Error(500, "Error")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `CalDavResult isError returns true for Error`() {
        val result: CalDavResult<Int> = CalDavResult.Error(404, "Not Found")
        assertTrue(result.isError)
    }

    @Test
    fun `CalDavResult isError returns false for Success`() {
        val result: CalDavResult<Int> = CalDavResult.Success(100)
        assertFalse(result.isError)
    }

    @Test
    fun `CalDavResult getOrNull returns data for Success`() {
        val result: CalDavResult<String> = CalDavResult.Success("value")
        assertEquals("value", result.getOrNull())
    }

    @Test
    fun `CalDavResult getOrNull returns null for Error`() {
        val result: CalDavResult<String> = CalDavResult.Error(500, "Error")
        assertNull(result.getOrNull())
    }

    @Test
    fun `CalDavResult getOrDefault returns data for Success`() {
        val result: CalDavResult<String> = CalDavResult.Success("actual")
        assertEquals("actual", result.getOrDefault("default"))
    }

    @Test
    fun `CalDavResult getOrDefault returns default for Error`() {
        val result: CalDavResult<String> = CalDavResult.Error(500, "Error")
        assertEquals("default", result.getOrDefault("default"))
    }

    @Test
    fun `CalDavResult map transforms Success data`() {
        val result: CalDavResult<Int> = CalDavResult.Success(5)
        val mapped = result.map { it * 2 }

        assertIs<CalDavResult.Success<Int>>(mapped)
        assertEquals(10, mapped.data)
    }

    @Test
    fun `CalDavResult map preserves Error`() {
        val result: CalDavResult<Int> = CalDavResult.Error(404, "Not Found")
        val mapped = result.map { it * 2 }

        assertIs<CalDavResult.Error>(mapped)
        assertEquals(404, mapped.code)
    }

    @Test
    fun `CalDavResult fold handles Success`() {
        val result: CalDavResult<Int> = CalDavResult.Success(10)
        val folded = result.fold(
            onSuccess = { "Success: $it" },
            onError = { code, msg -> "Error: $code - $msg" }
        )
        assertEquals("Success: 10", folded)
    }

    @Test
    fun `CalDavResult fold handles Error`() {
        val result: CalDavResult<Int> = CalDavResult.Error(500, "Internal Error")
        val folded = result.fold(
            onSuccess = { "Success: $it" },
            onError = { code, msg -> "Error: $code - $msg" }
        )
        assertEquals("Error: 500 - Internal Error", folded)
    }

    // ═══════════════════════════════════════════════════════════════════
    // CalDavResult ERROR CODE HELPERS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `CalDavResult Error isAuthError for 401`() {
        val error = CalDavResult.Error(401, "Unauthorized")
        assertTrue(error.isAuthError)
        assertFalse(error.isNotFound)
    }

    @Test
    fun `CalDavResult Error isAuthError for 403`() {
        val error = CalDavResult.Error(403, "Forbidden")
        assertTrue(error.isAuthError)
    }

    @Test
    fun `CalDavResult Error isNotFound for 404`() {
        val error = CalDavResult.Error(404, "Not Found")
        assertTrue(error.isNotFound)
        assertFalse(error.isAuthError)
    }

    @Test
    fun `CalDavResult Error isServerError for 5xx`() {
        assertTrue(CalDavResult.Error(500, "Internal").isServerError)
        assertTrue(CalDavResult.Error(502, "Bad Gateway").isServerError)
        assertTrue(CalDavResult.Error(503, "Unavailable").isServerError)
        assertFalse(CalDavResult.Error(400, "Bad Request").isServerError)
    }

    // ═══════════════════════════════════════════════════════════════════
    // CalDavCalendar TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `CalDavCalendar stores all properties`() {
        val calendar = CalDavCalendar(
            id = "home",
            href = "/1234567/calendars/home/",
            url = "https://caldav.icloud.com/1234567/calendars/home/",
            displayName = "Personal",
            color = "#FF5733",
            ctag = "abc123",
            isReadOnly = false
        )

        assertEquals("home", calendar.id)
        assertEquals("/1234567/calendars/home/", calendar.href)
        assertEquals("https://caldav.icloud.com/1234567/calendars/home/", calendar.url)
        assertEquals("Personal", calendar.displayName)
        assertEquals("#FF5733", calendar.color)
        assertEquals("abc123", calendar.ctag)
        assertFalse(calendar.isReadOnly)
    }

    @Test
    fun `CalDavCalendar allows null color`() {
        val calendar = CalDavCalendar(
            id = "work",
            href = "/1234567/calendars/work/",
            url = "https://caldav.icloud.com/1234567/calendars/work/",
            displayName = "Work",
            color = null,
            ctag = "xyz789"
        )

        assertNull(calendar.color)
    }

    @Test
    fun `CalDavCalendar allows null ctag`() {
        val calendar = CalDavCalendar(
            id = "new",
            href = "/1234567/calendars/new/",
            url = "https://caldav.icloud.com/1234567/calendars/new/",
            displayName = "New Calendar",
            color = "#3366FF",
            ctag = null
        )

        assertNull(calendar.ctag)
    }

    @Test
    fun `CalDavCalendar isReadOnly defaults to false`() {
        val calendar = CalDavCalendar(
            id = "cal",
            href = "/path/",
            url = "https://example.com/path/",
            displayName = "Cal",
            color = null,
            ctag = null
        )

        assertFalse(calendar.isReadOnly)
    }

    @Test
    fun `CalDavCalendar can be marked read only`() {
        val calendar = CalDavCalendar(
            id = "shared",
            href = "/shared/",
            url = "https://example.com/shared/",
            displayName = "Shared",
            color = null,
            ctag = null,
            isReadOnly = true
        )

        assertTrue(calendar.isReadOnly)
    }

    @Test
    fun `CalDavCalendar equality based on all fields`() {
        val cal1 = CalDavCalendar(
            id = "home", href = "/home/", url = "https://ex.com/home/",
            displayName = "Home", color = "#FFF", ctag = "123"
        )
        val cal2 = CalDavCalendar(
            id = "home", href = "/home/", url = "https://ex.com/home/",
            displayName = "Home", color = "#FFF", ctag = "123"
        )
        val cal3 = CalDavCalendar(
            id = "home", href = "/home/", url = "https://ex.com/home/",
            displayName = "Home", color = "#FFF", ctag = "456"  // different ctag
        )

        assertEquals(cal1, cal2)
        assertFalse(cal1 == cal3)  // Different ctag
    }

    // ═══════════════════════════════════════════════════════════════════
    // CalDavEvent TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `CalDavEvent stores all properties`() {
        val icsData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-123@example.com
            DTSTART:20250115T090000Z
            DTEND:20250115T100000Z
            SUMMARY:Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = CalDavEvent(
            uid = "event-123@example.com",
            href = "/1234567/calendars/home/event-123.ics",
            url = "https://caldav.icloud.com/1234567/calendars/home/event-123.ics",
            etag = "\"etag-abc\"",
            icalData = icsData
        )

        assertEquals("event-123@example.com", event.uid)
        assertEquals("/1234567/calendars/home/event-123.ics", event.href)
        assertEquals("https://caldav.icloud.com/1234567/calendars/home/event-123.ics", event.url)
        assertEquals("\"etag-abc\"", event.etag)
        assertEquals(icsData, event.icalData)
    }

    @Test
    fun `CalDavEvent allows null etag`() {
        val event = CalDavEvent(
            uid = "new-event@example.com",
            href = "/calendars/home/new-event.ics",
            url = "https://caldav.icloud.com/calendars/home/new-event.ics",
            etag = null,
            icalData = "BEGIN:VCALENDAR..."
        )

        assertNull(event.etag)
    }

    @Test
    fun `CalDavEvent hasEtag returns true when etag present`() {
        val event = CalDavEvent(
            uid = "evt", href = "/evt.ics", url = "https://ex.com/evt.ics",
            etag = "\"123\"", icalData = "..."
        )
        assertTrue(event.hasEtag)
    }

    @Test
    fun `CalDavEvent hasEtag returns false when etag null`() {
        val event = CalDavEvent(
            uid = "evt", href = "/evt.ics", url = "https://ex.com/evt.ics",
            etag = null, icalData = "..."
        )
        assertFalse(event.hasEtag)
    }

    @Test
    fun `CalDavEvent hasEtag returns false when etag blank`() {
        val event = CalDavEvent(
            uid = "evt", href = "/evt.ics", url = "https://ex.com/evt.ics",
            etag = "   ", icalData = "..."
        )
        assertFalse(event.hasEtag)
    }

    @Test
    fun `CalDavEvent equality based on uid and url`() {
        val ics1 = "VERSION:1"
        val ics2 = "VERSION:2"

        val evt1 = CalDavEvent(
            uid = "same-uid", href = "/evt.ics", url = "https://ex.com/evt.ics",
            etag = "\"a\"", icalData = ics1
        )
        val evt2 = CalDavEvent(
            uid = "same-uid", href = "/evt.ics", url = "https://ex.com/evt.ics",
            etag = "\"b\"", icalData = ics2  // Different etag and data
        )

        // Data classes compare all fields
        assertFalse(evt1 == evt2)
    }

    // ═══════════════════════════════════════════════════════════════════
    // CalDavCalendar ID EXTRACTION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `extractCalendarId from standard iCloud href`() {
        val href = "/1234567/calendars/home/"
        assertEquals("home", extractCalendarIdFromHref(href))
    }

    @Test
    fun `extractCalendarId from href without trailing slash`() {
        val href = "/1234567/calendars/work"
        assertEquals("work", extractCalendarIdFromHref(href))
    }

    @Test
    fun `extractCalendarId from minimal href`() {
        val href = "/calendars/"
        assertEquals("calendars", extractCalendarIdFromHref(href))
    }

    @Test
    fun `extractCalendarId from single segment`() {
        val href = "/home/"
        assertEquals("home", extractCalendarIdFromHref(href))
    }

    @Test
    fun `extractCalendarId handles UUID-style id`() {
        val href = "/user/calendars/550e8400-e29b-41d4-a716-446655440000/"
        assertEquals("550e8400-e29b-41d4-a716-446655440000", extractCalendarIdFromHref(href))
    }

    // ═══════════════════════════════════════════════════════════════════
    // COLOR NORMALIZATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `normalizeColor returns null for null input`() {
        assertNull(normalizeColor(null))
    }

    @Test
    fun `normalizeColor returns null for blank input`() {
        assertNull(normalizeColor(""))
        assertNull(normalizeColor("   "))
    }

    @Test
    fun `normalizeColor keeps valid hex with hash`() {
        assertEquals("#FF5733", normalizeColor("#FF5733"))
        assertEquals("#ffffff", normalizeColor("#ffffff"))
    }

    @Test
    fun `normalizeColor adds hash to bare hex`() {
        assertEquals("#FF5733", normalizeColor("FF5733"))
        assertEquals("#abc123", normalizeColor("abc123"))
    }

    @Test
    fun `normalizeColor converts 8-char hex to 6-char`() {
        // iCloud sometimes sends RRGGBBAA format
        assertEquals("#FF5733", normalizeColor("#FF5733FF"))
        assertEquals("#123456", normalizeColor("#12345678"))
    }

    @Test
    fun `normalizeColor returns null for invalid format`() {
        assertNull(normalizeColor("red"))
        assertNull(normalizeColor("#GGG"))
        assertNull(normalizeColor("rgb(255,0,0)"))
    }
}
