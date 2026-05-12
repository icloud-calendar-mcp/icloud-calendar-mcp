package org.onekash.mcp.calendar.ics

import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * VALARM specification for [IcsBuilder.build] / [IcsPatcher.patch] (RFC 5545 §3.6.6).
 *
 * @param trigger Either an RFC 5545 §3.3.6 duration (e.g. "-PT15M", "-P1D") for a
 *   relative alarm, or an RFC 5545 §3.3.5 basic-format UTC instant
 *   (e.g. "20260115T093000Z") for an absolute alarm. The form is detected by regex.
 * @param action One of "DISPLAY", "AUDIO", "EMAIL". Defaults to DISPLAY.
 * @param description Required by RFC 5545 §3.6.6 for DISPLAY action; defaults to
 *   "Reminder" when null. Optional for AUDIO/EMAIL.
 * @param summary Optional short subject; only emitted for EMAIL action.
 * @param repeatCount Number of times to repeat the alarm; emitted only when
 *   paired with [repeatDuration].
 * @param repeatDuration Repeat interval (e.g. "PT5M"); emitted only when paired
 *   with non-zero [repeatCount].
 */
data class AlarmSpec(
    val trigger: String,
    val action: String? = null,
    val description: String? = null,
    val summary: String? = null,
    val repeatCount: Int? = null,
    val repeatDuration: String? = null
)

/**
 * Builds valid ICS content for CalDAV uploads.
 *
 * Handles:
 * - All-day vs timed events
 * - UTC and timezone-aware datetimes
 * - RFC 5545 text escaping
 * - Line folding at 75 octets (not chars)
 * - RRULE for recurring events
 * - VTIMEZONE generation via ical4j
 *
 * Line endings: every emitted contentline ends with CRLF per RFC 5545 §3.1
 * (the ABNF: contentline = name *(";" param ) ":" value CRLF). Kotlin's
 * StringBuilder.appendLine uses the platform line separator, which is LF on
 * Linux/Android — using it directly violates §3.1. We route every line through
 * [crlfLine] instead.
 */
class IcsBuilder {

    companion object {
        private const val PRODID = "-//OnekashMCP//AppleCalendarMCP 1.0//EN"
        private const val VERSION = "2.0"
        private const val CALSCALE = "GREGORIAN"
        private const val MAX_LINE_OCTETS = 75
        private const val CRLF = "\r\n"

        // Single registry shared across all builds. createRegistry() allocates a fresh
        // ConcurrentHashMap per call; reusing one instance avoids reloading zoneinfo
        // resources on first lookup of each new builder/patcher invocation.
        internal val timeZoneRegistry: net.fortuna.ical4j.model.TimeZoneRegistry =
            TimeZoneRegistryFactory.getInstance().createRegistry()

        /**
         * Single source of truth for the "is this VALARM TRIGGER an absolute UTC
         * datetime?" check. Used by [IcsBuilder] (emit-side), [IcsPatcher.buildVAlarm]
         * (ical4j conversion-side), and [org.onekash.mcp.calendar.validation.InputValidator]
         * (boundary validation). Anchored, no backtracking risk.
         */
        internal val ICAL_ABSOLUTE_TRIGGER_REGEX = Regex("""^\d{8}T\d{6}Z$""")
    }

    private fun StringBuilder.crlfLine(line: String): StringBuilder {
        append(line)
        append(CRLF)
        return this
    }

    private val utcFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    private val localFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * Build ICS content for an event.
     *
     * @param uid Unique identifier (generated if not provided)
     * @param summary Event title (required)
     * @param startTime ISO 8601 start time for timed events (e.g., "2025-01-15T10:00:00Z")
     * @param endTime ISO 8601 end time for timed events
     * @param startDate Start date for all-day events (YYYY-MM-DD)
     * @param endDate End date for all-day events (YYYY-MM-DD, inclusive)
     * @param isAllDay True for all-day events
     * @param description Event description
     * @param location Event location
     * @param timezone IANA timezone (e.g., "America/New_York") for non-UTC timed events
     * @param rrule Recurrence rule (e.g., "FREQ=WEEKLY;BYDAY=MO")
     * @param status Event status (TENTATIVE, CONFIRMED, CANCELLED)
     * @param url URL associated with the event
     * @param categories List of category strings
     * @param priority Priority (1=highest, 9=lowest, 0=undefined)
     * @param transp Time transparency (OPAQUE or TRANSPARENT)
     * @param createdAt RFC 5545 §3.8.7.1 CREATED timestamp; emitted only when non-null.
     *   Set this on first creation; never override on subsequent edits — CREATED never
     *   changes after the property is first added (§3.8.7.1).
     * @param lastModified RFC 5545 §3.8.7.3 LAST-MODIFIED timestamp; emitted only when non-null.
     * @param endTimezone Optional IANA timezone for DTEND (cross-timezone events e.g.
     *   flights JFK->LAX). When null or equal to [timezone], DTEND reuses [timezone].
     *   When distinct, both VTIMEZONE blocks are emitted.
     * @param rdates Additional occurrence dates (RFC 5545 §3.8.5.2 — RDATE). One line per value.
     *   ISO 8601 instants for timed events; YYYY-MM-DD for all-day. null/empty => no RDATE emitted.
     * @param exdates Excluded occurrence dates (RFC 5545 §3.8.5.1 — EXDATE). Same form as rdates.
     * @param alarms RFC 5545 §3.6.6 VALARM components; one block per element.
     *   null/empty emits nothing.
     */
    fun build(
        uid: String? = null,
        summary: String,
        startTime: String? = null,
        endTime: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        isAllDay: Boolean = false,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        rrule: String? = null,
        status: String? = null,
        url: String? = null,
        categories: List<String>? = null,
        priority: Int? = null,
        transp: String? = null,
        createdAt: Instant? = null,
        lastModified: Instant? = null,
        endTimezone: String? = null,
        rdates: List<String>? = null,
        exdates: List<String>? = null,
        alarms: List<AlarmSpec>? = null
    ): String {
        val effectiveUid = uid ?: "${UUID.randomUUID()}@icloud-calendar-mcp"
        val dtstamp = utcFormatter.format(Instant.now())

        // VTIMEZONE only matters for non-UTC timed events with a TZID.
        val timedNonUtc = startTime != null && !startTime.endsWith("Z") &&
            endTime != null && !endTime.endsWith("Z")
        val effectiveEndTz = endTimezone?.takeIf { it != timezone }
        val needsVtimezone = timezone != null && timedNonUtc

        return buildString {
            crlfLine("BEGIN:VCALENDAR")
            crlfLine("VERSION:$VERSION")
            crlfLine("PRODID:$PRODID")
            crlfLine("CALSCALE:$CALSCALE")

            // VTIMEZONE must appear before VEVENT.
            // When endTimezone is distinct, emit a second VTIMEZONE for it.
            if (needsVtimezone) {
                appendVtimezone(timezone!!)
                if (effectiveEndTz != null) {
                    appendVtimezone(effectiveEndTz)
                }
            }

            crlfLine("BEGIN:VEVENT")
            crlfLine("UID:$effectiveUid")
            crlfLine("DTSTAMP:$dtstamp")

            if (createdAt != null) {
                crlfLine("CREATED:${utcFormatter.format(createdAt)}")
            }
            if (lastModified != null) {
                crlfLine("LAST-MODIFIED:${utcFormatter.format(lastModified)}")
            }

            // Date/time properties.
            // RFC 5545 §3.8.5 + Etar/Fossify/AOSP convention: when RRULE is present,
            // emit DTSTART + DURATION instead of DTSTART + DTEND so each occurrence
            // carries its length without re-deriving from DTEND. Avoids wire-format
            // churn when servers normalize one form to the other on every push.
            val recurring = !rrule.isNullOrBlank()
            if (isAllDay && startDate != null) {
                appendAllDayDateTimes(startDate, endDate ?: startDate, recurring)
            } else if (startTime != null && endTime != null) {
                appendTimedDateTimes(startTime, endTime, timezone, effectiveEndTz, recurring)
            }

            // Summary (title)
            appendFoldedLine("SUMMARY:${escapeText(summary)}")

            // Optional properties
            if (!description.isNullOrBlank()) {
                appendFoldedLine("DESCRIPTION:${escapeText(description)}")
            }

            if (!location.isNullOrBlank()) {
                appendFoldedLine("LOCATION:${escapeText(location)}")
            }

            // Recurrence
            if (!rrule.isNullOrBlank()) {
                crlfLine("RRULE:$rrule")
            }

            // RDATE / EXDATE — one line per value (avoids comma-joining + line-folding interactions)
            rdates?.forEach { value ->
                crlfLine(formatRecurrenceDateLine(Property.RDATE, value, isAllDay))
            }
            exdates?.forEach { value ->
                crlfLine(formatRecurrenceDateLine(Property.EXDATE, value, isAllDay))
            }

            // Extended properties
            if (!status.isNullOrBlank()) {
                crlfLine("STATUS:$status")
            }

            if (!url.isNullOrBlank()) {
                appendFoldedLine("URL:$url")
            }

            if (!categories.isNullOrEmpty()) {
                val escaped = categories.joinToString(",") { escapeText(it) }
                appendFoldedLine("CATEGORIES:$escaped")
            }

            if (priority != null) {
                crlfLine("PRIORITY:$priority")
            }

            if (!transp.isNullOrBlank()) {
                crlfLine("TRANSP:$transp")
            }

            // VALARM blocks (RFC 5545 §3.6.6) — emitted as nested components
            // INSIDE the VEVENT, before END:VEVENT.
            alarms?.forEach { appendVAlarm(it) }

            crlfLine("END:VEVENT")
            crlfLine("END:VCALENDAR")
        }.trimEnd()
    }

    /**
     * Append VTIMEZONE component from ical4j registry.
     *
     * ical4j's [VTimeZone.toString] emits its own internal CRLF separators,
     * but we re-split and re-emit each non-blank line via [crlfLine] to:
     *   1. normalize line endings (defends against ical4j version drift),
     *   2. avoid passing the multi-line block through crlfLine in one call,
     *      which would leave existing CRLFs in place AND add a trailing CRLF,
     *      producing the "double CRLF inside the block" bug.
     */
    private fun StringBuilder.appendVtimezone(timezoneId: String) {
        try {
            val tz = timeZoneRegistry.getTimeZone(timezoneId) ?: return
            val vtimezone = tz.vTimeZone ?: return
            vtimezone.toString()
                .lineSequence()
                .filter { it.isNotBlank() }
                .forEach { crlfLine(it.trimEnd()) }
        } catch (_: Exception) {
            // If timezone lookup fails, skip VTIMEZONE (event still valid with TZID)
        }
    }

    /**
     * Append all-day DTSTART and DTEND.
     * DTEND is exclusive per RFC 5545, so add 1 day to inclusive end.
     */
    private fun StringBuilder.appendAllDayDateTimes(startDate: String, endDate: String, recurring: Boolean) {
        val start = LocalDate.parse(startDate)
        val end = LocalDate.parse(endDate)
        val exclusiveEnd = end.plusDays(1)  // RFC 5545: DTEND is exclusive

        crlfLine("DTSTART;VALUE=DATE:${dateFormatter.format(start)}")
        if (recurring) {
            // Days from inclusive start to exclusive end, e.g. 2026-01-15..2026-01-15 -> P1D.
            val days = java.time.temporal.ChronoUnit.DAYS.between(start, exclusiveEnd)
            crlfLine("DURATION:P${days}D")
        } else {
            crlfLine("DTEND;VALUE=DATE:${dateFormatter.format(exclusiveEnd)}")
        }
    }

    /**
     * Append timed DTSTART and DTEND.
     * Uses UTC (Z suffix) if times end with Z, otherwise uses TZID.
     */
    private fun StringBuilder.appendTimedDateTimes(
        startTime: String,
        endTime: String,
        timezone: String?,
        endTimezone: String?,
        recurring: Boolean
    ) {
        if (startTime.endsWith("Z") && endTime.endsWith("Z")) {
            // UTC times - use Z suffix
            val startFormatted = formatIsoToIcalUtc(startTime)
            crlfLine("DTSTART:$startFormatted")
            if (recurring) {
                crlfLine("DURATION:${computeDuration(startTime, endTime)}")
            } else {
                crlfLine("DTEND:${formatIsoToIcalUtc(endTime)}")
            }
        } else if (timezone != null) {
            // Local times with timezone. DTEND uses endTimezone when distinct, else timezone.
            val effectiveEndTz = endTimezone ?: timezone
            val startFormatted = formatIsoToIcalLocal(startTime)
            crlfLine("DTSTART;TZID=$timezone:$startFormatted")
            if (recurring) {
                crlfLine("DURATION:${computeDuration(asUtcInstant(startTime), asUtcInstant(endTime))}")
            } else {
                crlfLine("DTEND;TZID=$effectiveEndTz:${formatIsoToIcalLocal(endTime)}")
            }
        } else {
            // Floating time (rare, treat as UTC)
            val startFormatted = formatIsoToIcalUtc(asUtcInstant(startTime))
            crlfLine("DTSTART:$startFormatted")
            if (recurring) {
                crlfLine("DURATION:${computeDuration(asUtcInstant(startTime), asUtcInstant(endTime))}")
            } else {
                crlfLine("DTEND:${formatIsoToIcalUtc(asUtcInstant(endTime))}")
            }
        }
    }

    /**
     * RFC 5545 §3.8.2.5: DURATION value as ISO-8601 PT format.
     * java.time.Duration.toString() emits exactly this format
     * (e.g. PT1H, PT30M, PT1H30M, PT15S).
     */
    private fun computeDuration(startIso: String, endIso: String): String {
        val start = Instant.parse(startIso)
        val end = Instant.parse(endIso)
        return java.time.Duration.between(start, end).toString()
    }

    /** Normalize a possibly-naked ISO 8601 string to UTC form (`...Z`) idempotently. */
    private fun asUtcInstant(iso: String): String =
        if (iso.endsWith("Z")) iso else "${iso}Z"

    /**
     * Format a single RDATE / EXDATE line. All-day events take VALUE=DATE
     * with YYYYMMDD form; timed events take basic UTC instants (yyyyMMddTHHmmssZ).
     */
    private fun formatRecurrenceDateLine(name: String, value: String, isAllDay: Boolean): String {
        return if (isAllDay) {
            "$name;VALUE=DATE:${dateFormatter.format(LocalDate.parse(value))}"
        } else {
            "$name:${formatIsoToIcalUtc(if (value.endsWith("Z")) value else value + "Z")}"
        }
    }

    /**
     * Append a single VALARM block (RFC 5545 §3.6.6) inside the surrounding VEVENT.
     * Mirrors the basic structure of KashCal's icaldav-core
     * `ICalGenerator.appendVAlarm`, simplified for the MCP surface (no Apple
     * X-WR-ALARMUID, no RFC 9074 extensions).
     */
    private fun StringBuilder.appendVAlarm(alarm: AlarmSpec) {
        crlfLine("BEGIN:VALARM")

        val action = alarm.action?.takeIf { it.isNotBlank() } ?: "DISPLAY"
        crlfLine("ACTION:$action")

        if (ICAL_ABSOLUTE_TRIGGER_REGEX.matches(alarm.trigger)) {
            crlfLine("TRIGGER;VALUE=DATE-TIME:${alarm.trigger}")
        } else {
            crlfLine("TRIGGER:${alarm.trigger}")
        }

        // DESCRIPTION is required for DISPLAY (RFC 5545 §3.6.6); also valid on EMAIL.
        // Default to "Reminder" when DISPLAY and the caller didn't supply one.
        when (action) {
            "DISPLAY" -> {
                val desc = alarm.description?.takeIf { it.isNotBlank() } ?: "Reminder"
                crlfLine("DESCRIPTION:${escapeText(desc)}")
            }
            "EMAIL" -> {
                alarm.description?.takeIf { it.isNotBlank() }?.let {
                    crlfLine("DESCRIPTION:${escapeText(it)}")
                }
                alarm.summary?.takeIf { it.isNotBlank() }?.let {
                    crlfLine("SUMMARY:${escapeText(it)}")
                }
            }
            // AUDIO and others: no DESCRIPTION/SUMMARY
        }

        // REPEAT requires DURATION (RFC 5545 §3.8.6.2) — emit the pair atomically.
        if (alarm.repeatCount != null && alarm.repeatCount > 0 && !alarm.repeatDuration.isNullOrBlank()) {
            crlfLine("REPEAT:${alarm.repeatCount}")
            crlfLine("DURATION:${alarm.repeatDuration}")
        }

        crlfLine("END:VALARM")
    }

    /**
     * Convert ISO 8601 UTC datetime to iCal format.
     * Input: 2025-01-15T10:00:00Z
     * Output: 20250115T100000Z
     */
    private fun formatIsoToIcalUtc(isoTime: String): String {
        val instant = Instant.parse(isoTime)
        return utcFormatter.format(instant)
    }

    /**
     * Convert ISO 8601 local datetime to iCal format.
     * Input: 2025-01-15T10:00:00
     * Output: 20250115T100000
     */
    private fun formatIsoToIcalLocal(isoTime: String): String {
        // Remove Z suffix if present, then format
        val cleaned = isoTime.removeSuffix("Z")
        val parts = cleaned.replace("-", "").replace(":", "")
        return parts  // Already in YYYYMMDDTHHMMSS format after replacements
    }

    /**
     * Escape special characters per RFC 5545.
     * Order matters: backslash first!
     */
    private fun escapeText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")
    }

    /**
     * Append a line with RFC 5545 line folding.
     * Lines longer than 75 octets are folded with CRLF + space.
     * Counts UTF-8 byte length (octets), not chars.
     * Multi-byte chars (including surrogate pairs) are never split across fold boundaries.
     */
    private fun StringBuilder.appendFoldedLine(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_LINE_OCTETS) {
            crlfLine(line)
            return
        }

        var charOffset = 0
        var first = true

        while (charOffset < line.length) {
            val maxOctets = if (first) MAX_LINE_OCTETS else MAX_LINE_OCTETS - 1 // space prefix
            var chunkOctets = 0
            var chunkEnd = charOffset

            while (chunkEnd < line.length) {
                // Get the full Unicode code point (handles surrogate pairs)
                val codePoint = line.codePointAt(chunkEnd)
                val cpChars = Character.charCount(codePoint)
                val cpBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size

                if (chunkOctets + cpBytes > maxOctets) break
                chunkOctets += cpBytes
                chunkEnd += cpChars
            }

            if (chunkEnd == charOffset) {
                // Single code point exceeds limit — emit it anyway to avoid infinite loop
                val codePoint = line.codePointAt(chunkEnd)
                chunkEnd += Character.charCount(codePoint)
            }

            val chunk = line.substring(charOffset, chunkEnd)
            if (first) {
                crlfLine(chunk)
                first = false
            } else {
                crlfLine(" $chunk")
            }

            charOffset = chunkEnd
        }
    }
}
