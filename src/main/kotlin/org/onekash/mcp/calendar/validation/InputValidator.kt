package org.onekash.mcp.calendar.validation

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Input validation for MCP tool parameters.
 *
 * Security requirements (from MCP spec):
 * - Validate all inputs before processing
 * - Prevent injection attacks
 * - Sanitize text that will be sent to external services
 */
object InputValidator {

    // Maximum lengths to prevent DoS
    private const val MAX_TITLE_LENGTH = 500
    private const val MAX_DESCRIPTION_LENGTH = 5000
    private const val MAX_LOCATION_LENGTH = 500
    private const val MAX_CALENDAR_ID_LENGTH = 500

    // Regex patterns
    private val DATE_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val DATETIME_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$""")
    private val CALENDAR_ID_PATTERN = Regex("""^[a-zA-Z0-9\-_:/\.@]+$""")

    /**
     * Validation result sealed class.
     */
    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val message: String) : ValidationResult()
    }

    /**
     * Validate a date string (YYYY-MM-DD format).
     */
    fun validateDate(date: String?, fieldName: String = "date"): ValidationResult {
        if (date.isNullOrBlank()) {
            return ValidationResult.Invalid("$fieldName is required")
        }

        if (!DATE_PATTERN.matches(date)) {
            return ValidationResult.Invalid("$fieldName must be in YYYY-MM-DD format")
        }

        return try {
            LocalDate.parse(date)
            ValidationResult.Valid
        } catch (e: DateTimeParseException) {
            ValidationResult.Invalid("$fieldName is not a valid date")
        }
    }

    /**
     * Validate a datetime string (ISO 8601 format).
     */
    fun validateDateTime(datetime: String?, fieldName: String = "datetime"): ValidationResult {
        if (datetime.isNullOrBlank()) {
            return ValidationResult.Invalid("$fieldName is required")
        }

        if (!DATETIME_PATTERN.matches(datetime)) {
            return ValidationResult.Invalid("$fieldName must be in ISO 8601 format (YYYY-MM-DDTHH:MM:SS)")
        }

        return try {
            // Try parsing with seconds
            if (datetime.count { it == ':' } == 2) {
                LocalDateTime.parse(datetime)
            } else {
                // Parse without seconds
                LocalDateTime.parse(datetime, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            }
            ValidationResult.Valid
        } catch (e: DateTimeParseException) {
            ValidationResult.Invalid("$fieldName is not a valid datetime")
        }
    }

    /**
     * Validate calendar ID.
     */
    fun validateCalendarId(calendarId: String?, fieldName: String = "calendar_id"): ValidationResult {
        if (calendarId.isNullOrBlank()) {
            return ValidationResult.Invalid("$fieldName is required")
        }

        if (calendarId.length > MAX_CALENDAR_ID_LENGTH) {
            return ValidationResult.Invalid("$fieldName is too long (max $MAX_CALENDAR_ID_LENGTH characters)")
        }

        if (!CALENDAR_ID_PATTERN.matches(calendarId)) {
            return ValidationResult.Invalid("$fieldName contains invalid characters")
        }

        // Check for path traversal and injection patterns
        if (containsInjectionPattern(calendarId)) {
            return ValidationResult.Invalid("$fieldName contains invalid characters")
        }

        return ValidationResult.Valid
    }

    /**
     * Check for common injection and path traversal patterns.
     */
    private fun containsInjectionPattern(input: String): Boolean {
        val lowerInput = input.lowercase()

        // Dangerous URI schemes (SSRF protection)
        val dangerousSchemes = listOf(
            "file://",         // Local file access
            "javascript:",     // JS execution
            "data:",           // Data URI
            "vbscript:",       // VBScript execution
            "ftp://"           // FTP access
        )
        if (dangerousSchemes.any { lowerInput.startsWith(it) }) {
            return true
        }

        // Internal network SSRF protection
        val ssrfPatterns = listOf(
            "localhost",           // Localhost access
            "127.0.0.1",           // Loopback IPv4
            "::1",                 // Loopback IPv6
            "0.0.0.0",             // All interfaces
            "169.254.",            // Link-local metadata endpoint
            "10.",                 // Private network
            "192.168.",            // Private network
            "172.16.", "172.17.", "172.18.", "172.19.",  // Private network
            "172.20.", "172.21.", "172.22.", "172.23.",
            "172.24.", "172.25.", "172.26.", "172.27.",
            "172.28.", "172.29.", "172.30.", "172.31."
        )
        if (ssrfPatterns.any { lowerInput.contains(it) }) {
            return true
        }

        // Dangerous patterns for injection
        val dangerousPatterns = listOf(
            "..",              // Path traversal
            ";",               // Command separator
            "|",               // Pipe
            "`",               // Command substitution
            "$(",              // Command substitution
            "\${",             // Variable expansion
            "<",               // Redirect / XML
            ">",               // Redirect / XML
            "\n",              // Newline injection
            "\r",              // Carriage return injection
            "%00",             // Null byte
            "\\x00",           // Null byte (hex)
            "*",               // Glob wildcard
            "?",               // Glob single char
            "[",               // Glob character class
            "{",               // Brace expansion
            "^",               // Regex anchor
            "$",               // Regex anchor / variable
            "+"                // Regex quantifier
        )
        return dangerousPatterns.any { input.contains(it) }
    }

    /**
     * Validate event title.
     */
    fun validateTitle(title: String?, fieldName: String = "title"): ValidationResult {
        if (title.isNullOrBlank()) {
            return ValidationResult.Invalid("$fieldName is required")
        }

        if (title.length > MAX_TITLE_LENGTH) {
            return ValidationResult.Invalid("$fieldName is too long (max $MAX_TITLE_LENGTH characters)")
        }

        return ValidationResult.Valid
    }

    /**
     * Validate optional text field (description, location).
     */
    fun validateOptionalText(
        text: String?,
        fieldName: String,
        maxLength: Int = MAX_DESCRIPTION_LENGTH
    ): ValidationResult {
        if (text.isNullOrBlank()) {
            return ValidationResult.Valid // Optional field
        }

        if (text.length > maxLength) {
            return ValidationResult.Invalid("$fieldName is too long (max $maxLength characters)")
        }

        return ValidationResult.Valid
    }

    /**
     * Validate that end time is after start time.
     */
    fun validateTimeRange(startTime: String, endTime: String): ValidationResult {
        return try {
            val start = parseDateTime(startTime)
            val end = parseDateTime(endTime)

            if (end.isBefore(start)) {
                ValidationResult.Invalid("End time must be after start time")
            } else if (end == start) {
                ValidationResult.Invalid("End time must be different from start time")
            } else {
                ValidationResult.Valid
            }
        } catch (e: Exception) {
            ValidationResult.Invalid("Invalid time format")
        }
    }

    /**
     * Sanitize text for ICS output.
     * Escapes special characters to prevent injection.
     */
    fun sanitizeForIcs(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    /**
     * Parse datetime string to LocalDateTime.
     */
    private fun parseDateTime(datetime: String): LocalDateTime {
        return if (datetime.count { it == ':' } == 2) {
            LocalDateTime.parse(datetime)
        } else {
            LocalDateTime.parse(datetime, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        }
    }

    /**
     * Collect all validation errors.
     */
    fun collectErrors(vararg results: ValidationResult): List<String> {
        return results.filterIsInstance<ValidationResult.Invalid>().map { it.message }
    }
}