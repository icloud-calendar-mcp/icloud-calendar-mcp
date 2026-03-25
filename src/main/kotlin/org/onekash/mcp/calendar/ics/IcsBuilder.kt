package org.onekash.mcp.calendar.ics

import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

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
 */
class IcsBuilder {

    companion object {
        private const val PRODID = "-//OnekashMCP//AppleCalendarMCP 1.0//EN"
        private const val VERSION = "2.0"
        private const val CALSCALE = "GREGORIAN"
        private const val MAX_LINE_OCTETS = 75
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
        transp: String? = null
    ): String {
        val effectiveUid = uid ?: "${UUID.randomUUID()}@icloud-calendar-mcp"
        val dtstamp = utcFormatter.format(Instant.now())

        // Determine if we need VTIMEZONE
        val needsVtimezone = timezone != null &&
            startTime != null && !startTime.endsWith("Z") &&
            endTime != null && !endTime.endsWith("Z")

        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:$VERSION")
            appendLine("PRODID:$PRODID")
            appendLine("CALSCALE:$CALSCALE")

            // VTIMEZONE must appear before VEVENT
            if (needsVtimezone) {
                appendVtimezone(timezone!!)
            }

            appendLine("BEGIN:VEVENT")
            appendLine("UID:$effectiveUid")
            appendLine("DTSTAMP:$dtstamp")

            // Date/time properties
            if (isAllDay && startDate != null) {
                appendAllDayDateTimes(startDate, endDate ?: startDate)
            } else if (startTime != null && endTime != null) {
                appendTimedDateTimes(startTime, endTime, timezone)
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
                appendLine("RRULE:$rrule")
            }

            // Extended properties
            if (!status.isNullOrBlank()) {
                appendLine("STATUS:$status")
            }

            if (!url.isNullOrBlank()) {
                appendFoldedLine("URL:$url")
            }

            if (!categories.isNullOrEmpty()) {
                val escaped = categories.joinToString(",") { escapeText(it) }
                appendFoldedLine("CATEGORIES:$escaped")
            }

            if (priority != null) {
                appendLine("PRIORITY:$priority")
            }

            if (!transp.isNullOrBlank()) {
                appendLine("TRANSP:$transp")
            }

            appendLine("END:VEVENT")
            appendLine("END:VCALENDAR")
        }.trimEnd()
    }

    /**
     * Append VTIMEZONE component from ical4j registry.
     */
    private fun StringBuilder.appendVtimezone(timezoneId: String) {
        try {
            val registry = TimeZoneRegistryFactory.getInstance().createRegistry()
            val tz = registry.getTimeZone(timezoneId) ?: return
            val vtimezone = tz.vTimeZone ?: return
            val tzStr = vtimezone.toString().trimEnd()
            appendLine(tzStr)
        } catch (_: Exception) {
            // If timezone lookup fails, skip VTIMEZONE (event still valid with TZID)
        }
    }

    /**
     * Append all-day DTSTART and DTEND.
     * DTEND is exclusive per RFC 5545, so add 1 day to inclusive end.
     */
    private fun StringBuilder.appendAllDayDateTimes(startDate: String, endDate: String) {
        val start = LocalDate.parse(startDate)
        val end = LocalDate.parse(endDate)
        val exclusiveEnd = end.plusDays(1)  // RFC 5545: DTEND is exclusive

        appendLine("DTSTART;VALUE=DATE:${dateFormatter.format(start)}")
        appendLine("DTEND;VALUE=DATE:${dateFormatter.format(exclusiveEnd)}")
    }

    /**
     * Append timed DTSTART and DTEND.
     * Uses UTC (Z suffix) if times end with Z, otherwise uses TZID.
     */
    private fun StringBuilder.appendTimedDateTimes(startTime: String, endTime: String, timezone: String?) {
        if (startTime.endsWith("Z") && endTime.endsWith("Z")) {
            // UTC times - use Z suffix
            val startFormatted = formatIsoToIcalUtc(startTime)
            val endFormatted = formatIsoToIcalUtc(endTime)
            appendLine("DTSTART:$startFormatted")
            appendLine("DTEND:$endFormatted")
        } else if (timezone != null) {
            // Local times with timezone
            val startFormatted = formatIsoToIcalLocal(startTime)
            val endFormatted = formatIsoToIcalLocal(endTime)
            appendLine("DTSTART;TZID=$timezone:$startFormatted")
            appendLine("DTEND;TZID=$timezone:$endFormatted")
        } else {
            // Floating time (rare, treat as UTC)
            val startFormatted = formatIsoToIcalUtc(startTime + "Z")
            val endFormatted = formatIsoToIcalUtc(endTime + "Z")
            appendLine("DTSTART:$startFormatted")
            appendLine("DTEND:$endFormatted")
        }
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
            appendLine(line)
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
                appendLine(chunk)
                first = false
            } else {
                appendLine(" $chunk")
            }

            charOffset = chunkEnd
        }
    }
}
