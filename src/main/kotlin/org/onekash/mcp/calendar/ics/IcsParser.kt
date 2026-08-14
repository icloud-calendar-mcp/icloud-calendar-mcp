package org.onekash.mcp.calendar.ics

import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.recurrence.RRuleExpander
import org.onekash.icaldav.util.DurationUtils
import java.time.Duration
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
    val recurrenceId: String? = null,   // RFC 5545 §3.8.4.4 RECURRENCE-ID in iCal wire form
                                         // (20260818T140000Z / 20260818): the instance this
                                         // occurrence identifies within its series. Null for a
                                         // standalone (non-recurring) event.
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
    private val expander = RRuleExpander()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private companion object {
        /**
         * Upper bound on how far the recurrence expansion is widened backward to catch
         * a boundary-spanning occurrence. It mirrors the get_events span cap
         * (InputValidator's 366-day MAX_RANGE_DAYS): an occurrence longer than a year
         * that also starts more than a year before the window is effectively permanent,
         * and capping the pad keeps a pathological long-duration series from widening
         * expansion without bound (CWE-400), the same absurdity the span cap rejects
         * up front.
         */
        private val MAX_EXPANSION_PAD: Duration = Duration.ofDays(366)

        /**
         * End-time an event carrying neither DTEND nor DURATION is treated as lasting.
         * Shared by [mapTimed] (the reported end) and [effectiveDuration] (the widening
         * and [reachesWindow] end) so the two cannot drift: were they to disagree, a
         * durationless boundary-spanning occurrence would be dropped again. All-day
         * events default to a whole day instead, handled inline where that value type
         * is derived ([mapAllDay] / [effectiveDuration]).
         */
        private val DEFAULT_TIMED_DURATION: Duration = Duration.ofHours(1)
    }

    /**
     * A recurring series in the requested range expands to more instances than the
     * expander's per-series work-bound allows (CWE-400 guard). This is the bridge-level
     * mirror of [RRuleExpander.ExpansionLimitException]: it lets the root module react
     * to an over-large expansion without importing the ical4j-confined core exception
     * (see the ical4j confinement rule), matching [IcsPatcher.UnparseableExistingIcsException].
     */
    class ExpansionLimitException(val uid: String, val limit: Int) : RuntimeException(
        "Recurring event '$uid' expands to more than $limit occurrences in the requested range"
    )

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

    /**
     * Parse ICS content into the individual occurrences that fall within
     * [rangeStart]..[rangeEnd], expanding RRULE/RDATE and honoring EXDATE and
     * RECURRENCE-ID overrides.
     *
     * [parse] maps each VEVENT verbatim, which for a recurring series reports the
     * master's DTSTART rather than the occurrence the caller asked about — a
     * yearly event created in 2023 comes back dated 2023 no matter which year is
     * queried. Range-aware read paths should use this instead.
     *
     * Non-recurring events are passed through unchanged, so callers can use this
     * for mixed responses.
     */
    fun parseOccurrences(icsContent: String, rangeStart: Instant, rangeEnd: Instant): List<ParsedEvent> {
        if (icsContent.isBlank()) return emptyList()
        val events = parser.parseAllEvents(icsContent).getOrNull() ?: return emptyList()

        // A VEVENT carrying RECURRENCE-ID is a modified instance of another VEVENT
        // (RFC 5545 §3.8.4.4), not a series of its own; it is folded into its
        // master below.
        val masters = events.filter { it.recurrenceId == null }
        // A response holding only overrides has no series to expand, so fall back
        // to mapping what is there rather than returning nothing.
        if (masters.isEmpty()) return events.mapNotNull { mapEvent(it) }

        val overridesByUid = events.filter { it.recurrenceId != null }.groupBy { it.uid }

        return masters
            .flatMap { master ->
                if (master.rrule == null && master.rdates.isEmpty()) {
                    // Non-recurring: pass through verbatim (no series identity).
                    listOf(master to null)
                } else {
                    // Recurring: an occurrence that STARTS before the requested range can
                    // still be in progress during it (RFC 4791 §9.9 overlap: DTSTART < end
                    // AND DTEND > start). The expander matches by start, so it never emits
                    // those leading, boundary-spanning occurrences. Widen the expansion
                    // start backward by the master's own occurrence duration so they are
                    // generated, then drop the ones that do not actually reach the window
                    // ([reachesWindow]). CalendarService.overlapsRequestedRange stays the
                    // outer trim (and owns the upper bound + all-day exclusive-DTEND).
                    //
                    // Two accepted limits of the master-duration heuristic:
                    //  - The pad is the MASTER's duration, so a RECURRENCE-ID override (or
                    //    RDATE) stretched longer than the master, whose original instant sits
                    //    more than a master-duration before the window, is not regenerated.
                    //    This narrows, not widens, the pre-existing gap (the old code padded
                    //    by nothing) and needs a pathological edit to hit.
                    //  - A sub-daily series (MINUTELY/SECONDLY) whose occurrences each last
                    //    close to a year can, once widened, exceed the expander's
                    //    MAX_ITERATIONS and return a 413. Such a series has far more than
                    //    MAX_RETURNED_EVENTS occurrences overlapping any window, so a 413 is
                    //    the correct answer regardless; the outcome is a clean structured
                    //    error, never a crash or a truncated payload.
                    val pad = effectiveDuration(master).coerceIn(Duration.ZERO, MAX_EXPANSION_PAD)
                    val widenedStart = rangeStart.minus(pad)

                    // Each expanded occurrence is tagged with its series master so mapEvent
                    // can stamp the occurrence identity and retain the series rrule (the
                    // expander strips both from a plain occurrence). Re-throw the expander's
                    // work-bound abort as the bridge-level exception, so callers never touch
                    // the ical4j-confined core type.
                    val expanded = try {
                        expander.expand(
                            master,
                            widenedStart,
                            rangeEnd,
                            RRuleExpander.buildOverrideMap(overridesByUid[master.uid].orEmpty())
                        )
                    } catch (e: RRuleExpander.ExpansionLimitException) {
                        throw ExpansionLimitException(e.uid, e.limit)
                    }
                    expanded
                        .filter { reachesWindow(it, rangeStart) }
                        .map { it to master }
                }
            }
            .mapNotNull { (occurrence, seriesMaster) -> mapEvent(occurrence, seriesMaster) }
    }

    /**
     * A master's per-occurrence duration, matching the end-time semantics [mapEvent]
     * reports: an explicit DURATION, else DTEND − DTSTART, else the default [mapAllDay]
     * and [mapTimed] apply to an event carrying neither (a whole day for all-day, one
     * hour for timed). Used to widen the expansion window (so a boundary-spanning
     * occurrence is generated) and to reconstruct a leading occurrence's end in
     * [reachesWindow]. Deriving it here rather than reusing the expander's private
     * duration keeps the value aligned with what the reader ultimately reports and
     * keeps ical4j confined to :icaldav-core.
     */
    private fun effectiveDuration(event: ICalEvent): Duration {
        event.duration?.let { return it }
        event.dtEnd?.let { return Duration.ofMillis(it.timestamp - event.dtStart.timestamp) }
        return if (event.isAllDay) Duration.ofDays(1) else DEFAULT_TIMED_DURATION
    }

    /**
     * Lower-bound guard for the widened expansion. An occurrence whose start is inside
     * the requested range passes through unchanged (zero change to the pre-widening
     * behavior); a leading (pad) occurrence that starts before [rangeStart] is kept
     * only if it is still in progress at [rangeStart], i.e. its end is strictly after
     * it. This decides only which newly generated leading occurrences may reach the
     * outer trim; the upper bound and the all-day exclusive-DTEND handling stay with
     * CalendarService.overlapsRequestedRange.
     */
    private fun reachesWindow(occurrence: ICalEvent, rangeStart: Instant): Boolean {
        val occStart = occurrence.dtStart.toInstant()
        if (!occStart.isBefore(rangeStart)) return true
        val occEnd = occurrence.dtEnd?.toInstant() ?: occStart.plus(effectiveDuration(occurrence))
        return occEnd.isAfter(rangeStart)
    }

    /**
     * Map an [ICalEvent] to a [ParsedEvent]. When [seriesMaster] is non-null, [event]
     * is an expanded occurrence of a recurring series: its occurrence identity
     * (RECURRENCE-ID) and the series rrule are stamped from the master, since the
     * expander strips both from a plain occurrence. When [seriesMaster] is null the
     * event is mapped verbatim (standalone event, or a raw VEVENT from [parse]),
     * deriving any recurrence identity from the event's own RECURRENCE-ID.
     */
    private fun mapEvent(event: ICalEvent, seriesMaster: ICalEvent? = null): ParsedEvent? {
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

        // Occurrence identity: for an expanded occurrence, the identity is its own
        // RECURRENCE-ID (an edited override carries the ORIGINAL instant) falling back
        // to its start (a plain occurrence), and the rrule is the series master's. For
        // a verbatim map, both come from the event itself (a standalone event has
        // neither; a raw override VEVENT has its own RECURRENCE-ID).
        val recurrenceId = if (seriesMaster != null) {
            (event.recurrenceId ?: event.dtStart).toICalString()
        } else {
            event.recurrenceId?.toICalString()
        }
        val rrule = (seriesMaster?.rrule ?: event.rrule)?.toICalString()

        return base.copy(
            status = status,
            url = event.url,
            categories = event.categories,
            priority = event.priority.takeIf { it > 0 },
            organizer = organizer,
            attendeeCount = event.attendees.size,
            rrule = rrule,
            recurrenceId = recurrenceId,
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
            else -> startInstant.plus(DEFAULT_TIMED_DURATION)
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
