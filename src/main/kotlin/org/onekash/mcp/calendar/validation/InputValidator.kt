package org.onekash.mcp.calendar.validation

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The scope an `update_event`/`delete_event` acts on when the target is (or could
 * be) one occurrence of a recurring series.
 *
 *  - [THIS_OCCURRENCE]: only the referenced occurrence (a RECURRENCE-ID exception,
 *    or an EXDATE on delete).
 *  - [THIS_AND_FUTURE]: the referenced occurrence and every later one.
 *  - [ALL_EVENTS]: the whole series (today's behavior for a recurring reference).
 *
 * The [token] is the exact wire value accepted on the MCP `scope` argument. This
 * is the single source of truth for the allowed values, reused by the validator,
 * the service, and the tool schemas.
 */
enum class EventScope(val token: String) {
    THIS_OCCURRENCE("this_occurrence"),
    THIS_AND_FUTURE("this_and_future"),
    ALL_EVENTS("all_events");

    companion object {
        /** The wire tokens in declaration order, for schemas and error messages. */
        val TOKENS: List<String> = entries.map { it.token }

        /** The [EventScope] for [token], or null if [token] is null/unrecognized. */
        fun fromToken(token: String?): EventScope? = entries.firstOrNull { it.token == token }
    }
}

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

    /**
     * Largest `get_events` window, in days between start_date and end_date
     * inclusive of both endpoints (so a full leap year, 2024-01-01..2025-01-01,
     * is 366 days and accepted). Caps the work a single read can request before
     * any CalDAV fetch, the first of the three response-size guards.
     */
    const val MAX_RANGE_DAYS = 366L

    // Regex patterns
    private val DATE_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")
    // A datetime is naive (no zone) or carries an explicit zone: a UTC 'Z' or a
    // numeric offset (+HH:MM / -HH:MM). Anchored and linear (no nested quantifiers),
    // so it stays ReDoS-safe.
    private val DATETIME_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(Z|[+-]\d{2}:\d{2})?$""")
    private val CALENDAR_ID_PATTERN = Regex("""^[a-zA-Z0-9\-_:/\.@]+$""")

    // Single source of truth for the trailing numeric offset (+HH:MM / -HH:MM) lives
    // in IcsBuilder.OFFSET_SUFFIX; re-exposed here so the boundary validator and the
    // ICS writer agree on what counts as an explicit zone. Anchored/fixed-length, so
    // containsMatchIn stays linear. Mirrors ALARM_ABSOLUTE_PATTERN below.
    private val OFFSET_SUFFIX get() = org.onekash.mcp.calendar.ics.IcsBuilder.OFFSET_SUFFIX

    /** True when [datetime] carries an explicit zone: a UTC 'Z' or a numeric offset. */
    private fun hasExplicitZone(datetime: String): Boolean =
        datetime.endsWith("Z") || OFFSET_SUFFIX.containsMatchIn(datetime)

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
     * Validate the span between a `get_events` start_date and end_date.
     *
     * This is a cross-field check, layered on top of the per-field
     * [validateDate] calls: it rejects an inverted range (end before start) and
     * a window wider than [MAX_RANGE_DAYS] days, before any CalDAV fetch runs.
     *
     * Defensive by design: if either date is null or unparseable, it returns
     * [ValidationResult.Valid] and leaves the format error to [validateDate], so
     * it composes in a [collectErrors] block without double-erroring or throwing.
     */
    fun validateDateSpan(startDate: String?, endDate: String?): ValidationResult {
        val start = parseLocalDateOrNull(startDate) ?: return ValidationResult.Valid
        val end = parseLocalDateOrNull(endDate) ?: return ValidationResult.Valid

        val spanDays = java.time.temporal.ChronoUnit.DAYS.between(start, end)
        if (spanDays < 0) {
            return ValidationResult.Invalid("end_date must not precede start_date")
        }
        if (spanDays > MAX_RANGE_DAYS) {
            return ValidationResult.Invalid(
                "Date range is $spanDays days, which exceeds the maximum of $MAX_RANGE_DAYS days; " +
                    "narrow the date range"
            )
        }
        return ValidationResult.Valid
    }

    /** Parse a strict YYYY-MM-DD date, or null when it is absent or malformed. */
    private fun parseLocalDateOrNull(date: String?): LocalDate? {
        if (date.isNullOrBlank() || !DATE_PATTERN.matches(date)) return null
        return try {
            LocalDate.parse(date)
        } catch (_: DateTimeParseException) {
            null
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
            return ValidationResult.Invalid(
                "$fieldName must be in ISO 8601 format: naive local (YYYY-MM-DDTHH:MM:SS), " +
                    "UTC (…Z), or with an offset (…+HH:MM)"
            )
        }

        return try {
            // The pattern permits three shapes; parse the one that matches so genuinely
            // malformed values (e.g. 2026-13-40T99:99) are still rejected here, before
            // any CalDAV call.
            if (hasExplicitZone(datetime)) {
                OffsetDateTime.parse(datetime) // Z or numeric offset (ISO_OFFSET_DATE_TIME)
            } else if (datetime.count { it == ':' } == 2) {
                LocalDateTime.parse(datetime)
            } else {
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
            // RDATE/EXDATE values are date or UTC-instant only. A numeric offset would
            // pass validateDateTime but the downstream writer (IcsBuilder/IcsPatcher
            // toRecurrenceDateTime) appends 'Z' and Instant.parse would then throw, so
            // reject offsets here.
            val withoutZ = v.removeSuffix("Z")
            if (hasExplicitZone(withoutZ)) {
                return ValidationResult.Invalid(
                    "$fieldName[$i] must be a date (YYYY-MM-DD) or a UTC datetime (…Z), not an offset datetime"
                )
            }
            val asDateTime = validateDateTime(withoutZ, "$fieldName[$i]")
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
     *
     * Endpoints are compared as absolute instants resolved the same way the write
     * path resolves them (IcsBuilder/IcsPatcher): an explicit Z/offset is that
     * instant; a naive value is anchored to [timezone] (or [endTimezone] for the end,
     * falling back to [timezone]), or treated as UTC when no zone is given. Resolving
     * identically is what keeps this guard honest for a mixed naive/zoned range: a
     * naive endpoint anchored to a non-UTC zone must be ordered against the other
     * endpoint at the same instant the stored event will use.
     */
    fun validateTimeRange(
        startTime: String,
        endTime: String,
        timezone: String? = null,
        endTimezone: String? = null
    ): ValidationResult {
        return try {
            val start = parseDateTime(startTime, timezone)
            val end = parseDateTime(endTime, endTimezone ?: timezone)

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
     * Sanitize text before it is handed to the ICS writer.
     *
     * This ONLY neutralizes CRLF injection (a value like
     * "text\r\nX-EVIL:injected" must not smuggle a separate property onto the
     * wire). It intentionally does NOT apply RFC 5545 §3.3.11 escaping of
     * `\ , ;` — the vendored [org.onekash.icaldav.parser.ICalGenerator] owns
     * that on write. Escaping here as well produced a double-escape
     * (`,` -> `\,` -> `\\\,`), so clients saw literal backslashes in
     * descriptions/titles after a create/update round-trip. Mirrors
     * IcsPatcher.sanitize().
     */
    fun sanitizeForIcs(text: String): String {
        return text
            .replace("\r\n", " ")
            .replace("\r", " ")
            .replace("\n", " ")
    }

    /**
     * Parse a datetime string to the absolute [Instant] it denotes, resolving it the
     * same way the write path does so ordering matches the stored event. A Z/offset
     * value carries its own zone (and [timezone] is ignored); a naive value is
     * anchored to [timezone], or treated as UTC when [timezone] is null.
     */
    private fun parseDateTime(datetime: String, timezone: String? = null): Instant {
        if (hasExplicitZone(datetime)) {
            return OffsetDateTime.parse(datetime).toInstant()
        }
        val local = if (datetime.count { it == ':' } == 2) {
            LocalDateTime.parse(datetime)
        } else {
            LocalDateTime.parse(datetime, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        }
        return if (timezone != null) {
            local.atZone(java.time.ZoneId.of(timezone)).toInstant()
        } else {
            local.toInstant(ZoneOffset.UTC)
        }
    }

    /**
     * Validate the optional `scope` argument on update/delete. Null/blank is Valid
     * (omitted; the caller applies the fail-loud default elsewhere based on whether
     * the reference is an occurrence). A non-null value must be one of
     * [EventScope.TOKENS].
     */
    fun validateScope(scope: String?, fieldName: String = "scope"): ValidationResult {
        if (scope.isNullOrBlank()) return ValidationResult.Valid
        if (EventScope.fromToken(scope) == null) {
            return ValidationResult.Invalid(
                "$fieldName must be one of ${EventScope.TOKENS.joinToString(", ")}"
            )
        }
        return ValidationResult.Valid
    }

    /**
     * Reject series-level fields (rrule / rdates / exdates) supplied under a
     * single-occurrence or this-and-future scope. Those fields describe the whole
     * recurrence set (RFC 5545 §3.8.5), so pairing them with a scope that targets
     * one occurrence is contradictory; the feature never silently widens an
     * occurrence intent into a series rewrite. A no-op for [EventScope.ALL_EVENTS]
     * and for an omitted/unrecognized scope (validated separately by [validateScope]).
     */
    fun validateOccurrenceScopeFields(
        scope: String?,
        rrule: String?,
        rdates: List<String>?,
        exdates: List<String>?,
        fieldName: String = "scope"
    ): ValidationResult {
        val parsed = EventScope.fromToken(scope) ?: return ValidationResult.Valid
        if (parsed != EventScope.THIS_OCCURRENCE && parsed != EventScope.THIS_AND_FUTURE) {
            return ValidationResult.Valid
        }
        val offenders = buildList {
            if (!rrule.isNullOrBlank()) add("rrule")
            if (!rdates.isNullOrEmpty()) add("rdates")
            if (!exdates.isNullOrEmpty()) add("exdates")
        }
        if (offenders.isNotEmpty()) {
            return ValidationResult.Invalid(
                "${offenders.joinToString(", ")} cannot be set with $fieldName=$scope; " +
                    "series-level fields belong to the whole series (use scope=all_events)"
            )
        }
        return ValidationResult.Valid
    }

    /**
     * Collect all validation errors.
     */
    fun collectErrors(vararg results: ValidationResult): List<String> {
        return results.filterIsInstance<ValidationResult.Invalid>().map { it.message }
    }
}