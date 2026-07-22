package org.onekash.mcp.calendar.ics

import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.util.DurationUtils
import java.time.Instant
import java.time.format.DateTimeFormatter

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
    val timezone: String? = null,       // IANA TZID from DTSTART; null for UTC/floating times
    val endTimezone: String? = null,    // IANA TZID from DTEND when distinct from start; null when matching or absent
    val rrule: String? = null,          // Raw RRULE string if recurring
    val rdates: List<String> = emptyList(),  // Additional occurrence dates (RFC 5545 §3.8.5.2 — RDATE).
                                             // VALUE=DATE-TIME normalized to ISO 8601 UTC; VALUE=DATE to YYYY-MM-DD.
    val exdates: List<String> = emptyList(), // Excluded occurrence dates (RFC 5545 §3.8.5.1 — EXDATE).
    val status: String? = null,         // TENTATIVE, CONFIRMED, CANCELLED
    val url: String? = null,            // URL property
    val categories: List<String> = emptyList(), // CATEGORIES
    val priority: Int? = null,          // PRIORITY (1=highest, 9=lowest)
    val organizer: String? = null,      // Formatted: "Name <email>" or just email
    val attendeeCount: Int = 0,         // Number of ATTENDEEs
    val alarms: List<ParsedAlarm> = emptyList() // RFC 5545 §3.6.6 VALARM components
)

/**
 * Parsed VALARM component (RFC 5545 §3.6.6).
 * Mirrors the inputs accepted by [org.onekash.mcp.calendar.ics.AlarmSpec].
 */
data class ParsedAlarm(
    val trigger: String,        // Either "-PT15M" form or absolute "20260115T093000Z"
    val action: String,         // DISPLAY | AUDIO | EMAIL
    val description: String? = null,
    val summary: String? = null,
    val repeatCount: Int? = null,
    val repeatDuration: String? = null
)

/**
 * ICS Parser.
 *
 * Delegates the RFC 5545 heavy lifting (line unfolding, text unescaping, DURATION
 * math, timezone resolution) to the vendored icaldav-core [ICalParser], then maps
 * its rich [ICalEvent] model down to the flat [ParsedEvent] the MCP tools expose.
 *
 * Skips:
 * - CANCELLED events
 * - Events without a SUMMARY
 */
class IcsParser {

    private val parser = ICalParser()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Parse ICS content into a list of events. Returns an empty list on any parse
     * failure (the MCP read path treats an unparseable body as "no events" rather
     * than surfacing an error to the LLM).
     */
    fun parse(icsContent: String): List<ParsedEvent> {
        if (icsContent.isBlank()) return emptyList()
        val events = parser.parseAllEvents(icsContent).getOrNull() ?: return emptyList()
        return events.mapNotNull { mapEvent(it) }
    }

    private fun mapEvent(event: ICalEvent): ParsedEvent? {
        // Skip cancelled events
        if (event.status == EventStatus.CANCELLED) return null

        // Require a non-blank summary
        val summary = event.summary?.takeIf { it.isNotBlank() } ?: return null
        val uid = event.uid.takeIf { it.isNotBlank() } ?: return null

        // Surface the STATUS exactly as the source carried it (RFC 5545 §3.8.1.11):
        // a null model status means no STATUS property was present, so we report
        // null; an explicit CONFIRMED/TENTATIVE round-trips as its own name.
        // (CANCELLED is filtered above.)
        val status = when (event.status) {
            EventStatus.TENTATIVE -> "TENTATIVE"
            EventStatus.CONFIRMED -> "CONFIRMED"
            else -> null
        }

        val organizer = event.organizer?.let { org ->
            if (org.name != null) "${org.name} <${org.email}>" else org.email
        }

        val base = if (event.isAllDay) {
            mapAllDay(event, uid, summary)
        } else {
            mapTimed(event, uid, summary)
        }

        return base.copy(
            status = status,
            url = event.url,
            categories = event.categories,
            priority = event.priority.takeIf { it > 0 },
            organizer = organizer,
            attendeeCount = event.attendees.size,
            rrule = event.rrule?.toICalString(),
            rdates = event.rdates.map { formatRecurrenceDate(it, event.isAllDay) },
            exdates = event.exdates.map { formatRecurrenceDate(it, event.isAllDay) },
            alarms = event.alarms.mapNotNull { mapAlarm(it) }
        )
    }

    private fun mapAllDay(event: ICalEvent, uid: String, summary: String): ParsedEvent {
        val startDate = event.dtStart.toLocalDate()
        // RFC 5545: DTEND is exclusive for all-day events; subtract 1 day for inclusive end.
        val endDate = event.dtEnd?.toLocalDate()?.minusDays(1) ?: startDate
        return ParsedEvent(
            uid = uid,
            summary = summary,
            description = event.description,
            location = event.location,
            isAllDay = true,
            startDate = startDate.format(dateFormatter),
            endDate = endDate.format(dateFormatter)
        )
    }

    private fun mapTimed(event: ICalEvent, uid: String, summary: String): ParsedEvent {
        val startInstant = event.dtStart.toInstant()
        // MCP end-time semantics: DTEND wins; else DTSTART+DURATION; else default +1h.
        val endInstant = when {
            event.dtEnd != null -> event.dtEnd!!.toInstant()
            event.duration != null -> startInstant.plusMillis(event.duration!!.toMillis())
            else -> startInstant.plusSeconds(3600)
        }

        val startTzid = event.dtStart.timezone?.id
        val endTzid = event.dtEnd?.timezone?.id
        val endTimezone = endTzid?.takeIf { it != startTzid }

        return ParsedEvent(
            uid = uid,
            summary = summary,
            description = event.description,
            location = event.location,
            isAllDay = false,
            startTime = formatInstant(startInstant),
            endTime = formatInstant(endInstant),
            timezone = startTzid,
            endTimezone = endTimezone
        )
    }

    /**
     * Map an icaldav [ICalAlarm] to the MCP [ParsedAlarm]. Preserves the trigger in
     * its original wire form: duration string ("-PT15M") for relative triggers, or
     * basic-format UTC instant ("20260115T093000Z") for absolute ones.
     */
    private fun mapAlarm(alarm: ICalAlarm): ParsedAlarm? {
        val trigger = alarm.trigger?.let { DurationUtils.format(it) }
            ?: alarm.triggerAbsolute?.toICalString()
            ?: return null
        return ParsedAlarm(
            trigger = trigger,
            action = alarm.action.name,
            description = alarm.description,
            summary = alarm.summary,
            repeatCount = alarm.repeatCount.takeIf { it > 0 },
            repeatDuration = alarm.repeatDuration?.let { DurationUtils.format(it) }
        )
    }

    /** Format an RDATE/EXDATE value: YYYY-MM-DD for all-day, ISO 8601 UTC instant otherwise. */
    private fun formatRecurrenceDate(dt: ICalDateTime, isAllDay: Boolean): String =
        if (isAllDay) dt.toLocalDate().format(dateFormatter) else formatInstant(dt.toInstant())

    private fun formatInstant(instant: Instant): String = instant.toString()
}
