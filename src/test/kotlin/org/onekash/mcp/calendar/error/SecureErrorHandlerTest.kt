package org.onekash.mcp.calendar.error

import org.onekash.mcp.calendar.error.SecureErrorHandler.ErrorCode
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for SecureErrorHandler.
 *
 * Tests verify:
 * - Error messages don't leak credentials
 * - Error messages don't leak file paths
 * - Error messages don't leak email addresses
 * - JSON output is properly escaped
 * - CalDAV errors are safely categorized
 */
class SecureErrorHandlerTest {

    // ═══════════════════════════════════════════════════════════════════
    // BASIC ERROR RESULT TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `createErrorResult should return valid JSON structure`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.VALIDATION_ERROR,
            "Invalid input"
        )

        assertTrue(result.isError == true)
        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("\"success\": false"))
        assertTrue(content.text.contains("\"code\": \"VALIDATION_ERROR\""))
        assertTrue(content.text.contains("\"message\": \"Invalid input\""))
    }

    @Test
    fun `createErrorResult should include details when provided`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.VALIDATION_ERROR,
            "Multiple errors",
            "Error 1; Error 2"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("\"details\": \"Error 1; Error 2\""))
    }

    // ═══════════════════════════════════════════════════════════════════
    // CREDENTIAL SANITIZATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `should sanitize password in error message`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.AUTHENTICATION_ERROR,
            "Failed with password=secret123"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("secret123"), "Password should be masked")
        assertTrue(content.text.contains("password=****"), "Password should be replaced with ****")
    }

    @Test
    fun `should sanitize password with colon separator`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.AUTHENTICATION_ERROR,
            "password: mysecret"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("mysecret"))
    }

    @Test
    fun `should sanitize token in error message`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.AUTHENTICATION_ERROR,
            "Invalid token=abc123def456"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("abc123def456"))
        assertTrue(content.text.contains("token=****"))
    }

    @Test
    fun `should sanitize api_key in error message`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.AUTHENTICATION_ERROR,
            "Bad api_key=sk-abc123"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("sk-abc123"))
        assertTrue(content.text.contains("api_key=****"))
    }

    @Test
    fun `should sanitize api-key with dash in error message`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.AUTHENTICATION_ERROR,
            "Bad api-key=sk-xyz789"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("sk-xyz789"))
    }

    @Test
    fun `should sanitize apikey without separator in error message`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.AUTHENTICATION_ERROR,
            "Bad apikey=secret"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("secret"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // PATH SANITIZATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `should sanitize macOS user paths`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.INTERNAL_ERROR,
            "File not found: /Users/johndoe/Documents/secret.txt"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("johndoe"), "Username should be masked")
        assertTrue(content.text.contains("/****/"), "Path should show masked username")
    }

    @Test
    fun `should sanitize Linux user paths`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.INTERNAL_ERROR,
            "File not found: /home/developer/config.json"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("developer"))
    }

    @Test
    fun `should sanitize Windows user paths`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.INTERNAL_ERROR,
            "File not found: C:\\Users\\alice\\Documents\\file.txt"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("alice"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // EMAIL SANITIZATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `should partially mask email addresses`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.AUTHENTICATION_ERROR,
            "Unknown user: john.doe@icloud.com"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("john.doe@icloud.com"), "Full email should not appear")
        assertTrue(content.text.contains("joh***@***.***"), "Email should be partially masked")
    }

    @Test
    fun `should mask multiple emails in message`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.VALIDATION_ERROR,
            "Conflict: alice@example.com vs bob@example.com"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("alice@example.com"))
        assertFalse(content.text.contains("bob@example.com"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // JSON ESCAPING TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `should escape double quotes in message`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.VALIDATION_ERROR,
            "Invalid value: \"test\""
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        // The JSON should be valid (quotes escaped)
        assertTrue(content.text.contains("\\\"test\\\""))
    }

    @Test
    fun `should escape backslashes in message`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.VALIDATION_ERROR,
            "Path: C:\\temp"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        // Backslash should be escaped for JSON
        assertTrue(content.text.contains("\\\\"))
    }

    @Test
    fun `should convert newlines to spaces`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.VALIDATION_ERROR,
            "Line1\nLine2\nLine3"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("\n"), "Newlines should be converted")
        assertTrue(content.text.contains("Line1 Line2 Line3"))
    }

    @Test
    fun `should remove carriage returns`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.VALIDATION_ERROR,
            "Line1\r\nLine2"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("\r"))
    }

    @Test
    fun `should convert tabs to spaces`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.VALIDATION_ERROR,
            "Col1\tCol2"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("\t"))
        assertTrue(content.text.contains("Col1 Col2"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // CALDAV ERROR TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `caldavError should map 401 to authentication message`() {
        val exception = Exception("HTTP 401 Unauthorized")
        val result = SecureErrorHandler.caldavError(exception)

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("Authentication failed"))
        assertFalse(content.text.contains("401"))
    }

    @Test
    fun `caldavError should map 403 to access denied message`() {
        val exception = Exception("HTTP 403 Forbidden")
        val result = SecureErrorHandler.caldavError(exception)

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("Access denied"))
    }

    @Test
    fun `caldavError should map 404 to not found message`() {
        val exception = Exception("HTTP 404 Not Found")
        val result = SecureErrorHandler.caldavError(exception)

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("not found"))
    }

    @Test
    fun `caldavError should map timeout to timeout message`() {
        val exception = Exception("Connection timeout after 30s")
        val result = SecureErrorHandler.caldavError(exception)

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("timed out"))
    }

    @Test
    fun `caldavError should map connection error to connect message`() {
        val exception = Exception("Unable to connect to host")
        val result = SecureErrorHandler.caldavError(exception)

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("Unable to connect"))
    }

    @Test
    fun `caldavError should use generic message for unknown errors`() {
        val exception = Exception("Some internal server error with stack trace")
        val result = SecureErrorHandler.caldavError(exception)

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("Calendar service error"))
        // Should not leak internal details
        assertFalse(content.text.contains("stack trace"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // INTERNAL ERROR TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `internalError should return generic message`() {
        val exception = RuntimeException("NullPointerException at com.example.Secret.method(Secret.kt:42)")
        val result = SecureErrorHandler.internalError(exception)

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("internal error"))
        // Should not leak stack trace info
        assertFalse(content.text.contains("NullPointerException"))
        assertFalse(content.text.contains("Secret.kt"))
        assertFalse(content.text.contains(":42"))
    }

    @Test
    fun `internalError should not expose class names`() {
        class SecretInternalClass : Exception("Secret message")
        val result = SecureErrorHandler.internalError(SecretInternalClass())

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertFalse(content.text.contains("SecretInternalClass"))
        assertFalse(content.text.contains("Secret message"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // VALIDATION ERROR TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validationError should handle single error`() {
        val result = SecureErrorHandler.validationError(listOf("Title is required"))

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("Title is required"))
        assertFalse(content.text.contains("details"))
    }

    @Test
    fun `validationError should handle multiple errors`() {
        val result = SecureErrorHandler.validationError(listOf(
            "Title is required",
            "Start date is invalid",
            "Calendar ID not found"
        ))

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)
        assertTrue(content.text.contains("Multiple validation errors"))
        assertTrue(content.text.contains("details"))
        assertTrue(content.text.contains("Title is required"))
        assertTrue(content.text.contains("Start date is invalid"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // ERROR CODE TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `error codes should have correct HTTP status mappings`() {
        assertEquals(400, ErrorCode.VALIDATION_ERROR.httpStatus)
        assertEquals(401, ErrorCode.AUTHENTICATION_ERROR.httpStatus)
        assertEquals(403, ErrorCode.AUTHORIZATION_ERROR.httpStatus)
        assertEquals(404, ErrorCode.NOT_FOUND.httpStatus)
        assertEquals(409, ErrorCode.CONFLICT.httpStatus)
        assertEquals(429, ErrorCode.RATE_LIMITED.httpStatus)
        assertEquals(500, ErrorCode.INTERNAL_ERROR.httpStatus)
        assertEquals(502, ErrorCode.CALDAV_ERROR.httpStatus)
    }

    // ═══════════════════════════════════════════════════════════════════
    // SAFE EXECUTE TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `safeExecute should return success for successful operation`() {
        val result = SecureErrorHandler.safeExecute("test") {
            "success"
        }

        assertTrue(result.isSuccess)
        assertEquals("success", result.getOrNull())
    }

    @Test
    fun `safeExecute should return failure for exception`() {
        val result = SecureErrorHandler.safeExecute("test") {
            throw RuntimeException("Test error")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RuntimeException)
    }

    // ═══════════════════════════════════════════════════════════════════
    // COMBINED SANITIZATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `should handle message with multiple sensitive items`() {
        val result = SecureErrorHandler.createErrorResult(
            ErrorCode.AUTHENTICATION_ERROR,
            "Failed for user@example.com with password=secret at /Users/admin/app token=abc123"
        )

        val content = result.content?.firstOrNull() as? TextContent
        assertTrue(content != null)

        // All sensitive data should be masked
        assertFalse(content.text.contains("user@example.com"))
        assertFalse(content.text.contains("secret"))
        assertFalse(content.text.contains("admin"))
        assertFalse(content.text.contains("abc123"))

        // Masks should be present
        assertTrue(content.text.contains("use***@***.***") || content.text.contains("***@***.***"))
        assertTrue(content.text.contains("password=****"))
        assertTrue(content.text.contains("token=****"))
    }
}