package org.onekash.mcp.calendar.validation

import org.onekash.mcp.calendar.validation.InputValidator.ValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for InputValidator.
 *
 * Tests verify:
 * - Date/datetime format validation
 * - Calendar ID validation (injection prevention)
 * - Title and text field validation
 * - Time range validation
 * - ICS sanitization for injection prevention
 */
class InputValidatorTest {

    // ═══════════════════════════════════════════════════════════════════
    // DATE VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateDate should accept valid YYYY-MM-DD format`() {
        val result = InputValidator.validateDate("2025-01-15")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validateDate should accept leap year date`() {
        val result = InputValidator.validateDate("2024-02-29")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validateDate should reject null input`() {
        val result = InputValidator.validateDate(null)
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("date is required", (result as ValidationResult.Invalid).message)
    }

    @Test
    fun `validateDate should reject blank input`() {
        val result = InputValidator.validateDate("")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateDate should reject wrong format`() {
        val invalid = listOf(
            "01-15-2025",      // MM-DD-YYYY
            "15/01/2025",      // DD/MM/YYYY
            "2025/01/15",      // Wrong separator
            "2025-1-15",       // Missing leading zero
            "2025-01-5",       // Missing leading zero
            "25-01-15",        // Two-digit year
            "2025-01-15T10:00" // DateTime format
        )

        invalid.forEach { date ->
            val result = InputValidator.validateDate(date, "start_date")
            assertTrue(result is ValidationResult.Invalid, "Should reject: $date")
            assertTrue(
                (result as ValidationResult.Invalid).message.contains("YYYY-MM-DD"),
                "Error message should mention format for: $date"
            )
        }
    }

    @Test
    fun `validateDate should reject invalid dates`() {
        val result = InputValidator.validateDate("2025-02-30") // Feb 30 doesn't exist
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateDate should use custom field name in error`() {
        val result = InputValidator.validateDate(null, "start_date")
        assertEquals("start_date is required", (result as ValidationResult.Invalid).message)
    }

    // ═══════════════════════════════════════════════════════════════════
    // DATETIME VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateDateTime should accept ISO 8601 with seconds`() {
        val result = InputValidator.validateDateTime("2025-01-15T09:30:00")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validateDateTime should accept ISO 8601 without seconds`() {
        val result = InputValidator.validateDateTime("2025-01-15T09:30")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validateDateTime should reject null input`() {
        val result = InputValidator.validateDateTime(null)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateDateTime should reject wrong formats`() {
        val invalid = listOf(
            "2025-01-15",           // Date only
            "2025-01-15 09:30:00",  // Space instead of T
            "2025-01-15T9:30:00",   // Missing leading zero
            "2025-01-15T09:30:00Z", // With timezone
            "09:30:00",             // Time only
            "2025-01-15T25:00:00",  // Invalid hour
            "2025-01-15T09:60:00"   // Invalid minute
        )

        invalid.forEach { datetime ->
            val result = InputValidator.validateDateTime(datetime, "start_time")
            assertTrue(result is ValidationResult.Invalid, "Should reject: $datetime")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CALENDAR ID VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateCalendarId should accept valid IDs`() {
        val validIds = listOf(
            "calendar-1",
            "cal_123",
            "https://caldav.icloud.com/123456/calendars/home/",
            "user@example.com",
            "CAL-001-ABC"
        )

        validIds.forEach { id ->
            val result = InputValidator.validateCalendarId(id)
            assertTrue(result is ValidationResult.Valid, "Should accept: $id")
        }
    }

    @Test
    fun `validateCalendarId should reject null input`() {
        val result = InputValidator.validateCalendarId(null)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateCalendarId should reject too long IDs`() {
        val longId = "a".repeat(501)
        val result = InputValidator.validateCalendarId(longId)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).message.contains("too long"))
    }

    @Test
    fun `validateCalendarId should reject injection characters`() {
        val malicious = listOf(
            "cal; DROP TABLE calendars;",  // SQL injection
            "cal<script>alert(1)</script>", // XSS
            "cal\nX-Header: injection",     // Header injection
            "cal\r\nSet-Cookie: bad",       // CRLF injection
            "cal$(whoami)",                  // Command injection
            "cal`id`",                       // Command injection
            "cal|cat /etc/passwd",           // Pipe injection
            "../../../etc/passwd",           // Path traversal
            "cal%00null"                     // Null byte injection
        )

        malicious.forEach { id ->
            val result = InputValidator.validateCalendarId(id)
            assertTrue(result is ValidationResult.Invalid, "Should reject malicious: $id")
            assertTrue(
                (result as ValidationResult.Invalid).message.contains("invalid characters"),
                "Error should mention invalid characters for: $id"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TITLE VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateTitle should accept valid titles`() {
        val result = InputValidator.validateTitle("Team Meeting")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validateTitle should reject null input`() {
        val result = InputValidator.validateTitle(null)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateTitle should reject blank input`() {
        val result = InputValidator.validateTitle("   ")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateTitle should reject too long titles`() {
        val longTitle = "a".repeat(501)
        val result = InputValidator.validateTitle(longTitle)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).message.contains("too long"))
    }

    @Test
    fun `validateTitle should accept titles with special characters`() {
        // Titles can have special chars - we sanitize on output, not input
        val result = InputValidator.validateTitle("Meeting: Q1 Review & Planning!")
        assertTrue(result is ValidationResult.Valid)
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPTIONAL TEXT VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateOptionalText should accept null`() {
        val result = InputValidator.validateOptionalText(null, "description")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validateOptionalText should accept blank`() {
        val result = InputValidator.validateOptionalText("", "description")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validateOptionalText should accept valid text`() {
        val result = InputValidator.validateOptionalText("Meeting notes here", "description")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validateOptionalText should reject too long text`() {
        val longText = "a".repeat(5001)
        val result = InputValidator.validateOptionalText(longText, "description")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateOptionalText should respect custom max length`() {
        val result = InputValidator.validateOptionalText("abcdef", "location", maxLength = 5)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).message.contains("5"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // TIME RANGE VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateTimeRange should accept valid range`() {
        val result = InputValidator.validateTimeRange(
            "2025-01-15T09:00:00",
            "2025-01-15T10:00:00"
        )
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validateTimeRange should accept multi-day events`() {
        val result = InputValidator.validateTimeRange(
            "2025-01-15T09:00:00",
            "2025-01-17T10:00:00"
        )
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validateTimeRange should reject end before start`() {
        val result = InputValidator.validateTimeRange(
            "2025-01-15T10:00:00",
            "2025-01-15T09:00:00"
        )
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).message.contains("after"))
    }

    @Test
    fun `validateTimeRange should reject same start and end`() {
        val result = InputValidator.validateTimeRange(
            "2025-01-15T09:00:00",
            "2025-01-15T09:00:00"
        )
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).message.contains("different"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // ICS SANITIZATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    // NOTE: sanitizeForIcs ONLY neutralizes CRLF injection. RFC 5545 §3.3.11
    // escaping of `\ , ;` is owned by the ICalGenerator on write; escaping here
    // too caused a double-escape (clients saw literal `\,` `\;` `\\` in
    // descriptions after a round-trip). See InputValidator.sanitizeForIcs KDoc.

    @Test
    fun `sanitizeForIcs leaves backslashes for the generator to escape`() {
        val result = InputValidator.sanitizeForIcs("path\\to\\file")
        assertEquals("path\\to\\file", result)
    }

    @Test
    fun `sanitizeForIcs leaves semicolons for the generator to escape`() {
        val result = InputValidator.sanitizeForIcs("item1;item2")
        assertEquals("item1;item2", result)
    }

    @Test
    fun `sanitizeForIcs leaves commas for the generator to escape`() {
        val result = InputValidator.sanitizeForIcs("item1,item2")
        assertEquals("item1,item2", result)
    }

    @Test
    fun `sanitizeForIcs collapses newlines to spaces`() {
        val result = InputValidator.sanitizeForIcs("line1\nline2")
        assertEquals("line1 line2", result)
    }

    @Test
    fun `sanitizeForIcs collapses CRLF to a single space`() {
        val result = InputValidator.sanitizeForIcs("line1\r\nline2")
        assertEquals("line1 line2", result)
    }

    @Test
    fun `sanitizeForIcs should handle ICS injection attempt`() {
        // Attempt to inject an additional ICS property via a bare newline.
        val malicious = "Meeting\nATTENDEE:mailto:evil@hacker.com"
        val result = InputValidator.sanitizeForIcs(malicious)
        // The newline must be gone so the value cannot break onto its own line.
        assertFalse(result.contains("\n"))
        assertFalse(result.contains("\r"))
        assertEquals("Meeting ATTENDEE:mailto:evil@hacker.com", result)
    }

    @Test
    fun `sanitizeForIcs should handle complex injection`() {
        val malicious = "Title;ORGANIZER:mailto:evil@hacker.com\r\nATTENDEE:innocent@user.com"
        val result = InputValidator.sanitizeForIcs(malicious)
        // No line breaks survive → cannot smuggle a separate property.
        assertFalse(result.contains("\n"))
        assertFalse(result.contains("\r"))
        // The semicolon is preserved verbatim; the generator escapes it on write.
        assertTrue(result.contains(";"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // COLLECT ERRORS TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `collectErrors should return empty list for all valid`() {
        val errors = InputValidator.collectErrors(
            ValidationResult.Valid,
            ValidationResult.Valid
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `collectErrors should collect invalid messages`() {
        val errors = InputValidator.collectErrors(
            ValidationResult.Valid,
            ValidationResult.Invalid("Error 1"),
            ValidationResult.Valid,
            ValidationResult.Invalid("Error 2")
        )
        assertEquals(2, errors.size)
        assertEquals("Error 1", errors[0])
        assertEquals("Error 2", errors[1])
    }

    @Test
    fun `collectErrors should handle all invalid`() {
        val errors = InputValidator.collectErrors(
            ValidationResult.Invalid("E1"),
            ValidationResult.Invalid("E2"),
            ValidationResult.Invalid("E3")
        )
        assertEquals(3, errors.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    // SCOPE VALIDATION TESTS (single-occurrence edits)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateScope should accept each allowed scope value`() {
        for (token in listOf("this_occurrence", "this_and_future", "all_events")) {
            assertTrue(InputValidator.validateScope(token) is ValidationResult.Valid, "expected $token to be valid")
        }
    }

    @Test
    fun `validateScope should treat null and blank as valid (omitted, optional)`() {
        assertTrue(InputValidator.validateScope(null) is ValidationResult.Valid)
        assertTrue(InputValidator.validateScope("") is ValidationResult.Valid)
        assertTrue(InputValidator.validateScope("   ") is ValidationResult.Valid)
    }

    @Test
    fun `validateScope should reject a value outside the allowed set`() {
        val result = InputValidator.validateScope("everything")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateScope invalid message names exactly the three allowed scopes`() {
        val result = InputValidator.validateScope("nope") as ValidationResult.Invalid
        assertTrue(result.message.contains("this_occurrence"), result.message)
        assertTrue(result.message.contains("this_and_future"), result.message)
        assertTrue(result.message.contains("all_events"), result.message)
    }

    @Test
    fun `EventScope tokens map round-trip through fromToken`() {
        assertEquals(EventScope.THIS_OCCURRENCE, EventScope.fromToken("this_occurrence"))
        assertEquals(EventScope.THIS_AND_FUTURE, EventScope.fromToken("this_and_future"))
        assertEquals(EventScope.ALL_EVENTS, EventScope.fromToken("all_events"))
        assertEquals(null, EventScope.fromToken("bogus"))
        assertEquals(null, EventScope.fromToken(null))
    }

    @Test
    fun `validateOccurrenceScopeFields rejects a series rrule under this_occurrence`() {
        val result = InputValidator.validateOccurrenceScopeFields(
            scope = "this_occurrence",
            rrule = "FREQ=WEEKLY",
            rdates = null,
            exdates = null
        )
        assertTrue(result is ValidationResult.Invalid)
        assertTrue(result.message.contains("rrule"), result.message)
    }

    @Test
    fun `validateOccurrenceScopeFields rejects rdates or exdates under this_and_future`() {
        assertTrue(
            InputValidator.validateOccurrenceScopeFields("this_and_future", null, listOf("2026-08-18"), null)
                is ValidationResult.Invalid
        )
        assertTrue(
            InputValidator.validateOccurrenceScopeFields("this_and_future", null, null, listOf("2026-08-18"))
                is ValidationResult.Invalid
        )
    }

    @Test
    fun `validateOccurrenceScopeFields allows series fields under all_events`() {
        assertTrue(
            InputValidator.validateOccurrenceScopeFields("all_events", "FREQ=WEEKLY", listOf("2026-08-18"), null)
                is ValidationResult.Valid
        )
    }

    @Test
    fun `validateOccurrenceScopeFields allows an occurrence scope with no series fields`() {
        assertTrue(
            InputValidator.validateOccurrenceScopeFields("this_occurrence", null, null, null)
                is ValidationResult.Valid
        )
    }

    @Test
    fun `validateOccurrenceScopeFields is a no-op when scope is omitted`() {
        // Omitted scope keeps whole-event behavior; series fields are not this check's concern.
        assertTrue(
            InputValidator.validateOccurrenceScopeFields(null, "FREQ=WEEKLY", null, null)
                is ValidationResult.Valid
        )
    }
}