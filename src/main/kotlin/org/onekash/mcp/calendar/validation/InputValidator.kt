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
     * Validate an IANA timezone identifier (e.g., "America/New_York").
     * Accepts null/blank as Valid (the field is optional at the MCP layer).
     */
    fun validateTimezone(timezone: String?, fieldName: String = "timezone"): ValidationResult {
        if (timezone.isNullOrBlank()) return ValidationResult.Valid
        if (timezone.length > 100) {
            return ValidationResult.Invalid("$fieldName is too long")
        }
        return try {
            java.time.ZoneId.of(timezone)
            ValidationResult.Valid
        } catch (_: Exception) {
            ValidationResult.Invalid("$fieldName is not a recognized IANA timezone")
        }
    }

    /**
     * Validate VALARM list inputs from the MCP layer.
     *
     * Bounds: list size <= 8 (RFC 5545 doesn't cap, but 8 is well above any
     * realistic UX and protects against DoS).
     *
     * Per element:
     *   - trigger must match either an RFC 5545 §3.3.6 duration regex
     *     (e.g. "-PT15M", "+P1D", "PT0S") or a basic-format UTC instant
     *     ("yyyyMMddTHHmmssZ"). Approximate; ical4j has the final say.
     *   - action (if present) must be one of DISPLAY/AUDIO/EMAIL.
     *   - description and summary length <= 500.
     *   - repeatCount must be >= 0.
     *   - repeatDuration (if present) must match the duration regex.
     */
    private val ALARM_DURATION_PATTERN = Regex("""^[+-]?P(?:T(?:\d+H)?(?:\d+M)?(?:\d+S)?|(?:\d+W|\d+D)(?:T(?:\d+H)?(?:\d+M)?(?:\d+S)?)?)$""")
    // Single source of truth lives in IcsBuilder.ICAL_ABSOLUTE_TRIGGER_REGEX —
    // re-exposed here for boundary validation symmetry with the builder/patcher.
    private val ALARM_ABSOLUTE_PATTERN get() = org.onekash.mcp.calendar.ics.IcsBuilder.ICAL_ABSOLUTE_TRIGGER_REGEX
    private val ALARM_ACTIONS = setOf("DISPLAY", "AUDIO", "EMAIL")

    /**
     * Validate alarm list. Each entry is a Map<String, Any?> mirroring the
     * AlarmSpec data class fields (decoded from the JSON-RPC `alarms` array
     * at the MCP boundary).
     */
    fun validateAlarmList(alarms: List<Map<String, Any?>>?, fieldName: String = "alarms"): ValidationResult {
        if (alarms.isNullOrEmpty()) return ValidationResult.Valid
        if (alarms.size > 8) {
            return ValidationResult.Invalid("$fieldName has too many entries (max 8)")
        }
        for ((i, entry) in alarms.withIndex()) {
            val trigger = entry["trigger"] as? String
                ?: return ValidationResult.Invalid("$fieldName[$i].trigger is required")
            if (!ALARM_DURATION_PATTERN.matches(trigger) && !ALARM_ABSOLUTE_PATTERN.matches(trigger)) {
                return ValidationResult.Invalid(
                    "$fieldName[$i].trigger must be an RFC 5545 duration (e.g. -PT15M) or absolute UTC instant (yyyyMMddTHHmmssZ)"
                )
            }
            val action = entry["action"] as? String
            if (action != null && action !in ALARM_ACTIONS) {
                return ValidationResult.Invalid("$fieldName[$i].action must be one of DISPLAY/AUDIO/EMAIL")
            }
            (entry["description"] as? String)?.let {
                if (it.length > 500) return ValidationResult.Invalid("$fieldName[$i].description exceeds 500 characters")
            }
            (entry["summary"] as? String)?.let {
                if (it.length > 500) return ValidationResult.Invalid("$fieldName[$i].summary exceeds 500 characters")
            }
            (entry["repeat_count"] as? Number)?.toInt()?.let {
                if (it < 0) return ValidationResult.Invalid("$fieldName[$i].repeat_count must be >= 0")
            }
            (entry["repeat_duration"] as? String)?.let {
                if (!ALARM_DURATION_PATTERN.matches(it)) {
                    return ValidationResult.Invalid("$fieldName[$i].repeat_duration must be an RFC 5545 duration")
                }
            }
        }
        return ValidationResult.Valid
    }

    /**
     * Validate a list of recurrence-date strings (RDATE / EXDATE values).
     * Each value must be either YYYY-MM-DD (all-day) or an ISO 8601 datetime
     * accepted by [validateDateTime] / [validateDate]. Bounded to 366 entries
     * per RFC 5545 §3.3.5 practical limit (one per day for a year is plenty
     * before clients should switch to RRULE).
     */
    fun validateRecurrenceDateList(values: List<String>?, fieldName: String): ValidationResult {
        if (values.isNullOrEmpty()) return ValidationResult.Valid
        if (values.size > 366) {
            return ValidationResult.Invalid("$fieldName has too many entries (max 366)")
        }
        for ((i, v) in values.withIndex()) {
            // Each value is either a YYYY-MM-DD or a datetime string
            val asDate = validateDate(v, "$fieldName[$i]")
            if (asDate is ValidationResult.Valid) continue
            val asDateTime = validateDateTime(v.removeSuffix("Z"), "$fieldName[$i]")
            if (asDateTime is ValidationResult.Invalid) {
                return asDateTime
            }
        }
        return ValidationResult.Valid
    }

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