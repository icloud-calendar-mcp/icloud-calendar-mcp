package org.onekash.mcp.calendar.integration

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.onekash.mcp.calendar.error.SecureErrorHandler
import org.onekash.mcp.calendar.validation.InputValidator
import org.onekash.mcp.calendar.validation.InputValidator.ValidationResult
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * Integration tests for MCP tool handlers.
 *
 * Tests verify:
 * - Input validation flows correctly for each tool
 * - Error responses are properly formatted
 * - Security measures are applied consistently
 */
class ToolIntegrationTest {

    // ═══════════════════════════════════════════════════════════════════
    // GET_EVENTS VALIDATION FLOW
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `get_events should validate all required parameters`() {
        // Simulate the validation flow from Main.kt get_events handler
        val calendarId: String? = null
        val startDate: String? = null
        val endDate: String? = null

        val errors = InputValidator.collectErrors(
            InputValidator.validateCalendarId(calendarId),
            InputValidator.validateDate(startDate, "start_date"),
            InputValidator.validateDate(endDate, "end_date")
        )

        assertEquals(3, errors.size)
        assertTrue(errors.any { it.contains("calendar_id") })
        assertTrue(errors.any { it.contains("start_date") })
        assertTrue(errors.any { it.contains("end_date") })
    }

    @Test
    fun `get_events should accept valid parameters`() {
        val calendarId = "cal-123"
        val startDate = "2025-01-15"
        val endDate = "2025-01-20"

        val errors = InputValidator.collectErrors(
            InputValidator.validateCalendarId(calendarId),
            InputValidator.validateDate(startDate, "start_date"),
            InputValidator.validateDate(endDate, "end_date")
        )

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `get_events should reject malicious calendar ID`() {
        val maliciousId = "../../../etc/passwd"
        val startDate = "2025-01-15"
        val endDate = "2025-01-20"

        val errors = InputValidator.collectErrors(
            InputValidator.validateCalendarId(maliciousId),
            InputValidator.validateDate(startDate, "start_date"),
            InputValidator.validateDate(endDate, "end_date")
        )

        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("invalid characters"))
    }

    @Test
    fun `get_events should reject invalid date format`() {
        val calendarId = "cal-123"
        val startDate = "01-15-2025"  // Wrong format
        val endDate = "2025-01-20"

        val errors = InputValidator.collectErrors(
            InputValidator.validateCalendarId(calendarId),
            InputValidator.validateDate(startDate, "start_date"),
            InputValidator.validateDate(endDate, "end_date")
        )

        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("YYYY-MM-DD"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // CREATE_EVENT VALIDATION FLOW
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `create_event should validate all required parameters`() {
        val calendarId: String? = null
        val title: String? = null
        val startTime: String? = null
        val endTime: String? = null

        val errors = mutableListOf<String>()
        errors.addAll(InputValidator.collectErrors(
            InputValidator.validateCalendarId(calendarId),
            InputValidator.validateTitle(title),
            InputValidator.validateDateTime(startTime, "start_time"),
            InputValidator.validateDateTime(endTime, "end_time")
        ))

        assertEquals(4, errors.size)
    }

    @Test
    fun `create_event should validate time range`() {
        val calendarId = "cal-123"
        val title = "Meeting"
        val startTime = "2025-01-15T10:00:00"
        val endTime = "2025-01-15T09:00:00"  // End before start!

        val errors = mutableListOf<String>()
        errors.addAll(InputValidator.collectErrors(
            InputValidator.validateCalendarId(calendarId),
            InputValidator.validateTitle(title),
            InputValidator.validateDateTime(startTime, "start_time"),
            InputValidator.validateDateTime(endTime, "end_time")
        ))

        val timeRangeResult = InputValidator.validateTimeRange(startTime, endTime)
        if (timeRangeResult is ValidationResult.Invalid) {
            errors.add(timeRangeResult.message)
        }

        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("after"))
    }

    @Test
    fun `create_event should validate optional fields`() {
        val location = "a".repeat(501)  // Too long
        val description = "Valid description"

        val errors = InputValidator.collectErrors(
            InputValidator.validateOptionalText(location, "location", maxLength = 500),
            InputValidator.validateOptionalText(description, "description", maxLength = 5000)
        )

        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("location"))
        assertTrue(errors[0].contains("too long"))
    }

    @Test
    fun `create_event should accept valid full parameters`() {
        val calendarId = "cal-123"
        val title = "Team Meeting"
        val startTime = "2025-01-15T09:00:00"
        val endTime = "2025-01-15T10:00:00"
        val location = "Conference Room A"
        val description = "Weekly sync meeting"

        val errors = mutableListOf<String>()
        errors.addAll(InputValidator.collectErrors(
            InputValidator.validateCalendarId(calendarId),
            InputValidator.validateTitle(title),
            InputValidator.validateDateTime(startTime, "start_time"),
            InputValidator.validateDateTime(endTime, "end_time"),
            InputValidator.validateOptionalText(location, "location", maxLength = 500),
            InputValidator.validateOptionalText(description, "description", maxLength = 5000)
        ))

        val timeRangeResult = InputValidator.validateTimeRange(startTime, endTime)
        if (timeRangeResult is ValidationResult.Invalid) {
            errors.add(timeRangeResult.message)
        }

        assertTrue(errors.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // UPDATE_EVENT VALIDATION FLOW
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `update_event should require event_id`() {
        val eventId: String? = null

        val result = InputValidator.validateCalendarId(eventId, "event_id")
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("event_id is required", (result as ValidationResult.Invalid).message)
    }

    @Test
    fun `update_event should allow partial updates`() {
        // Only updating title, other fields null
        val eventId = "evt-123"
        val title = "New Title"
        val startTime: String? = null
        val endTime: String? = null

        val errors = mutableListOf<String>()

        val eventIdResult = InputValidator.validateCalendarId(eventId, "event_id")
        if (eventIdResult is ValidationResult.Invalid) {
            errors.add(eventIdResult.message)
        }

        // Only validate if provided
        if (title != null) {
            val titleResult = InputValidator.validateTitle(title)
            if (titleResult is ValidationResult.Invalid) {
                errors.add(titleResult.message)
            }
        }

        assertTrue(errors.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // DELETE_EVENT VALIDATION FLOW
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `delete_event should require event_id`() {
        val eventId: String? = null

        val result = InputValidator.validateCalendarId(eventId, "event_id")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `delete_event should reject malicious event_id`() {
        val maliciousId = "evt; rm -rf /"

        val result = InputValidator.validateCalendarId(maliciousId, "event_id")
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).message.contains("invalid"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // ERROR RESPONSE FORMAT
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validation error response should be valid JSON`() {
        val errors = listOf("Title is required", "Start date is invalid")
        val result = SecureErrorHandler.validationError(errors)

        assertTrue(result.isError == true)
        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)

        val text = content.text
        assertTrue(text.contains("\"success\": false"))
        assertTrue(text.contains("\"code\": \"VALIDATION_ERROR\""))
        assertTrue(text.contains("\"details\""))
    }

    @Test
    fun `authentication error response should be valid JSON`() {
        val result = SecureErrorHandler.authenticationError("iCloud credentials not configured")

        assertTrue(result.isError == true)
        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)

        val text = content.text
        assertTrue(text.contains("\"code\": \"AUTH_ERROR\""))
        assertTrue(text.contains("iCloud credentials not configured"))
    }

    @Test
    fun `internal error response should not leak details`() {
        val exception = RuntimeException("Sensitive internal error with secrets")
        val result = SecureErrorHandler.internalError(exception)

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)

        val text = content.text
        assertTrue(text.contains("internal error"))
        assertFalse(text.contains("Sensitive"))
        assertFalse(text.contains("secrets"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // ICS SANITIZATION INTEGRATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `create_event should sanitize text for ICS injection`() {
        val title = "Meeting\nATTENDEE:mailto:evil@hacker.com"

        val safeTitle = InputValidator.sanitizeForIcs(title)

        // Newline must be gone (CRLF-injection defense); the value can no longer
        // break onto its own line. RFC 5545 escaping is the generator's job.
        assertFalse(safeTitle.contains("\n"))
        assertFalse(safeTitle.contains("\r"))
    }

    @Test
    fun `create_event should sanitize location for ICS injection`() {
        val location = "Room A;ORGANIZER:mailto:evil@hacker.com"

        val safeLocation = InputValidator.sanitizeForIcs(location)

        // Semicolon is preserved verbatim (no double-escape); the generator
        // escapes it on write. The injection defense is the CRLF stripping above.
        assertTrue(safeLocation.contains(";"))
        assertFalse(safeLocation.contains("\n"))
    }

    @Test
    fun `special chars survive the full tool write-read round trip without double escaping`() {
        // Regression for the escaping half of issue #2: the create/update tool path
        // ran sanitizeForIcs (which used to RFC 5545-escape) AND then the generator
        // escaped again, so clients saw literal "\," "\;" "\\" in descriptions after
        // a round-trip. This exercises the SAME combined path the tools use —
        // sanitizeForIcs -> IcsBuilder.build -> IcsParser.parse — which no prior test
        // did (they tested the builder or the sanitizer in isolation).
        val builder = org.onekash.mcp.calendar.ics.IcsBuilder()
        val parser = org.onekash.mcp.calendar.ics.IcsParser()

        val rawTitle = "Lunch, dinner; and a path C:\\Users\\Name"
        val rawDescription = "warm intro, framed; not generic. path\\here"

        val ics = builder.build(
            uid = "roundtrip@test",
            summary = InputValidator.sanitizeForIcs(rawTitle),
            startTime = "2026-08-15T09:00:00",
            endTime = "2026-08-15T10:00:00",
            timezone = "UTC",
            description = InputValidator.sanitizeForIcs(rawDescription)
        )

        val parsed = parser.parse(ics).single()
        assertEquals(rawTitle, parsed.summary)
        assertEquals(rawDescription, parsed.description)
    }

    // ═══════════════════════════════════════════════════════════════════
    // END-TO-END SECURITY FLOW
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `full validation flow should catch all security issues`() {
        // Simulate a malicious request with multiple issues
        val calendarId = "../../../etc/passwd"  // Path traversal
        val title = "Meeting"
        val startTime = "invalid"  // Invalid format
        val endTime = "2025-01-15T10:00:00"
        val location = "Room\nX-HEADER:injection"  // Header injection (valid but will be sanitized)

        val errors = mutableListOf<String>()
        errors.addAll(InputValidator.collectErrors(
            InputValidator.validateCalendarId(calendarId),
            InputValidator.validateTitle(title),
            InputValidator.validateDateTime(startTime, "start_time"),
            InputValidator.validateDateTime(endTime, "end_time")
        ))

        // Should have 2 errors: calendar_id and start_time
        assertEquals(2, errors.size)

        // Location passes validation but gets sanitized
        val locationResult = InputValidator.validateOptionalText(location, "location", maxLength = 500)
        assertTrue(locationResult is ValidationResult.Valid)

        // But sanitization makes it safe
        val safeLocation = InputValidator.sanitizeForIcs(location)
        assertFalse(safeLocation.contains("\n"))
    }
}
