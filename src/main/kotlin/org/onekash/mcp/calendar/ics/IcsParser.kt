package org.onekash.mcp.calendar.ics

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.*
import net.fortuna.ical4j.model.parameter.TzId
import java.io.StringReader
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAmount

/**
 * Parsed event data for MCP responses.
 *
 * Provides a clean data structure for LLM consumption with
 * all times normalized to UTC ISO 8601 format.
 */
data class ParsedEvent(
    val uid: String,
    val summary: String,
    val description: String? = null,
    val location: String? = null,
    val isAllDay: Boolean = false,
    val startTime: String? = null,      // ISO 8601 UTC for timed events
    val endTime: String? = null,        // ISO 8601 UTC for timed events
    val startDate: String? = null,      // YYYY-MM-DD for all-day events
    val endDate: String? = null,        // YYYY-MM-DD for all-day events (inclusive)
    val rrule: String? = null           // Raw RRULE string if recurring
)

/**
 * ICS Parser using ical4j.
 *
 * Handles:
 * - All-day events (dates stored as strings to prevent timezone shift)
 * - Timezone conversion to UTC
 * - Line unfolding (RFC 5545)
 * - Text unescaping
 * - DURATION calculation
 */
class IcsParser {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Parse ICS content into a list of events.
     *
     * Skips:
     * - CANCELLED events
     * - Events without SUMMARY
     */
    fun parse(icsContent: String): List<ParsedEvent> {
        if (icsContent.isBlank()) return emptyList()

        return try {
            val calendar = CalendarBuilder().build(StringReader(icsContent))
            parseCalendar(calendar)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseCalendar(calendar: Calendar): List<ParsedEvent> {
        return calendar.components
            .filterIsInstance<VEvent>()
            .mapNotNull { parseEvent(it) }
    }

    private fun parseEvent(vevent: VEvent): ParsedEvent? {
        // Skip cancelled events
        val status = vevent.getProperty<Status>(Property.STATUS)
        if (status?.value == "CANCELLED") return null

        // Require summary
        val summary = vevent.summary?.value ?: return null
        if (summary.isBlank()) return null

        val uid = vevent.uid?.value ?: return null

        // Check if all-day event
        val dtStart = vevent.startDate ?: return null
        val isAllDay = isAllDayEvent(dtStart)

        // Parse description and location with unescaping
        val description = vevent.getProperty<Description>(Property.DESCRIPTION)?.value
        val location = vevent.location?.value

        // Get RRULE if present
        val rrule = vevent.getProperty<RRule>(Property.RRULE)?.value

        return if (isAllDay) {
            parseAllDayEvent(uid, summary, description, location, rrule, vevent)
        } else {
            parseTimedEvent(uid, summary, description, location, rrule, vevent)
        }
    }

    private fun isAllDayEvent(dtStart: DtStart): Boolean {
        // All-day events have DATE value type (no time component)
        val value = dtStart.value
        // If no T in the value, it's a date-only (all-day)
        return !value.contains("T")
    }

    private fun parseAllDayEvent(
        uid: String,
        summary: String,
        description: String?,
        location: String?,
        rrule: String?,
        vevent: VEvent
    ): ParsedEvent {
        // Parse as date strings to prevent timezone shifting
        val dtStart = vevent.startDate
        val dtEnd = vevent.endDate

        val startDate = parseDate(dtStart?.value)
        // RFC 5545: DTEND is exclusive for all-day events, subtract 1 day for inclusive end
        val endDate = dtEnd?.let {
            val exclusiveEnd = parseDate(it.value)
            subtractOneDay(exclusiveEnd)
        } ?: startDate

        return ParsedEvent(
            uid = uid,
            summary = summary,
            description = description,
            location = location,
            isAllDay = true,
            startDate = startDate,
            endDate = endDate,
            rrule = rrule
        )
    }

    private fun parseTimedEvent(
        uid: String,
        summary: String,
        description: String?,
        location: String?,
        rrule: String?,
        vevent: VEvent
    ): ParsedEvent {
        val dtStart = vevent.startDate!!
        val startInstant = datePropertyToInstant(dtStart)

        // Calculate end time from DTEND or DURATION
        // Note: Check for DTEND property explicitly since endDate might return default value
        val dtEndProp = vevent.getProperty<DtEnd>(Property.DTEND)
        val durationProp = vevent.getProperty<net.fortuna.ical4j.model.property.Duration>(Property.DURATION)

        val endInstant = when {
            dtEndProp != null -> datePropertyToInstant(vevent.endDate!!)
            durationProp != null -> calculateEndFromDuration(startInstant, durationProp)
            else -> startInstant.plusSeconds(3600)  // Default: 1 hour
        }

        return ParsedEvent(
            uid = uid,
            summary = summary,
            description = description,
            location = location,
            isAllDay = false,
            startTime = formatInstant(startInstant),
            endTime = formatInstant(endInstant),
            rrule = rrule
        )
    }

    private fun datePropertyToInstant(dt: DtStart): Instant {
        val value = dt.value
        val date = dt.date

        // Check if UTC (ends with Z)
        if (value.endsWith("Z")) {
            return date.toInstant()
        }

        // Check for TZID parameter
        val tzidParam = dt.getParameter<TzId>(net.fortuna.ical4j.model.Parameter.TZID)
        if (tzidParam != null) {
            val zoneId = try {
                ZoneId.of(tzidParam.value)
            } catch (e: Exception) {
                ZoneOffset.UTC
            }

            // Parse the local datetime and convert to instant
            val localDt = parseLocalDateTime(value)
            return localDt.atZone(zoneId).toInstant()
        }

        // Floating time - treat as UTC for simplicity
        return date.toInstant()
    }

    private fun datePropertyToInstant(dt: DtEnd): Instant {
        val value = dt.value
        val date = dt.date

        // Check if UTC (ends with Z)
        if (value.endsWith("Z")) {
            return date.toInstant()
        }

        // Check for TZID parameter
        val tzidParam = dt.getParameter<TzId>(net.fortuna.ical4j.model.Parameter.TZID)
        if (tzidParam != null) {
            val zoneId = try {
                ZoneId.of(tzidParam.value)
            } catch (e: Exception) {
                ZoneOffset.UTC
            }

            // Parse the local datetime and convert to instant
            val localDt = parseLocalDateTime(value)
            return localDt.atZone(zoneId).toInstant()
        }

        // Floating time - treat as UTC for simplicity
        return date.toInstant()
    }

    private fun parseLocalDateTime(value: String): LocalDateTime {
        // Format: YYYYMMDDTHHMMSS
        val datePart = value.substringBefore("T")
        val timePart = value.substringAfter("T").removeSuffix("Z")

        val year = datePart.substring(0, 4).toInt()
        val month = datePart.substring(4, 6).toInt()
        val day = datePart.substring(6, 8).toInt()

        val hour = timePart.substring(0, 2).toInt()
        val minute = timePart.substring(2, 4).toInt()
        val second = if (timePart.length >= 6) timePart.substring(4, 6).toInt() else 0

        return LocalDateTime.of(year, month, day, hour, minute, second)
    }

    private fun calculateEndFromDuration(start: Instant, duration: net.fortuna.ical4j.model.property.Duration): Instant {
        val temporalAmount: TemporalAmount = duration.duration
        val localDt = LocalDateTime.ofInstant(start, ZoneOffset.UTC)
        val endDt = localDt.plus(temporalAmount)
        return endDt.toInstant(ZoneOffset.UTC)
    }

    private fun formatInstant(instant: Instant): String {
        return instant.toString()  // ISO 8601 format with Z suffix
    }

    private fun parseDate(dateStr: String?): String {
        if (dateStr == null) return ""
        // Parse YYYYMMDD to YYYY-MM-DD
        return if (dateStr.length >= 8 && !dateStr.contains("-")) {
            "${dateStr.substring(0, 4)}-${dateStr.substring(4, 6)}-${dateStr.substring(6, 8)}"
        } else {
            dateStr.substringBefore("T")
        }
    }

    private fun subtractOneDay(dateStr: String): String {
        val date = LocalDate.parse(dateStr)
        return date.minusDays(1).format(dateFormatter)
    }
}
