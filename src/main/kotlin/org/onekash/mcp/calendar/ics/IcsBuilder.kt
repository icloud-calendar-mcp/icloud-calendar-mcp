package org.onekash.mcp.calendar.ics

import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.util.DurationUtils
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
 * Full adoption of the vendored icaldav-core [ICalGenerator]: the MCP tool inputs
 * are mapped onto an [ICalEvent] model and serialized by icaldav, which owns all
 * RFC 5545 heavy lifting (CRLF folding, text escaping, VTIMEZONE generation, and
 * Apple/iCloud extensions on VALARMs). ical4j is confined to :icaldav-core and is
 * NOT on the write path.
 *
 * icaldav always emits the properties iCloud requires (CALSCALE, STATUS, SEQUENCE),
 * so those appear unconditionally — an improvement over the previous hand-written
 * emitter which omitted them when the caller left them blank.
 */
class IcsBuilder {

    companion object {
        private const val PRODID = "-//OnekashMCP//AppleCalendarMCP 1.0//EN"

        /**
         * Single source of truth for the "is this VALARM TRIGGER an absolute UTC
         * datetime?" check. Used by [IcsBuilder]/[IcsPatcher] (emit-side alarm mapping)
         * and [org.onekash.mcp.calendar.validation.InputValidator] (boundary validation).
         * Anchored, no backtracking risk.
         */
        internal val ICAL_ABSOLUTE_TRIGGER_REGEX = Regex("""^\d{8}T\d{6}Z$""")

        /**
         * Map an [AlarmSpec] (MCP wire shape) to an icaldav [ICalAlarm]. Shared with
         * [IcsPatcher] so create and update paths emit identical VALARM structure.
         *
         * - Trigger: absolute UTC form (regex match) → [ICalAlarm.triggerAbsolute];
         *   otherwise parsed as an RFC 5545 duration → [ICalAlarm.trigger].
         * - REPEAT/DURATION are emitted only as an atomic pair (both present).
         */
        internal fun toICalAlarm(spec: AlarmSpec): ICalAlarm {
            val action = AlarmAction.fromString(spec.action)
            val absolute = ICAL_ABSOLUTE_TRIGGER_REGEX.matches(spec.trigger)
            val repeatPair = spec.repeatCount != null && spec.repeatCount > 0 &&
                !spec.repeatDuration.isNullOrBlank()
            return ICalAlarm(
                action = action,
                trigger = if (absolute) null else DurationUtils.parse(spec.trigger),
                triggerAbsolute = if (absolute) ICalDateTime.parse(spec.trigger) else null,
                description = spec.description?.takeIf { it.isNotBlank() },
                summary = spec.summary?.takeIf { it.isNotBlank() },
                repeatCount = if (repeatPair) spec.repeatCount else 0,
                repeatDuration = if (repeatPair) DurationUtils.parse(spec.repeatDuration) else null
            )
        }
    }

    private val generator = ICalGenerator(prodId = PRODID, includeAppleExtensions = true)

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
        val recurring = !rrule.isNullOrBlank()

        // Resolve DTSTART / DTEND / DURATION for the three supported shapes.
        // RFC 5545 §3.8.5 convention: when RRULE is present, emit DTSTART + DURATION
        // (each occurrence carries its own length) instead of DTSTART + DTEND.
        val times = resolveTimes(
            startTime = startTime,
            endTime = endTime,
            startDate = startDate,
            endDate = endDate,
            isAllDay = isAllDay,
            timezone = timezone,
            endTimezone = endTimezone,
            recurring = recurring
        )

        val event = ICalEvent(
            uid = effectiveUid,
            importId = ICalEvent.generateImportId(effectiveUid, null),
            summary = summary,
            description = description?.takeIf { it.isNotBlank() },
            location = location?.takeIf { it.isNotBlank() },
            dtStart = times.dtStart,
            dtEnd = times.dtEnd,
            duration = times.duration,
            isAllDay = isAllDay,
            // Null/blank status → leave STATUS absent in the model (RFC 5545
            // §3.8.1.11 optional); the generator still emits a default line for
            // iCloud. A caller-supplied value is honored verbatim.
            status = status?.takeIf { it.isNotBlank() }?.let { EventStatus.fromString(it) },
            sequence = 0,
            rrule = if (recurring) parseRRuleOrNull(rrule) else null,
            exdates = (exdates ?: emptyList()).map { toRecurrenceDateTime(it, isAllDay) },
            rdates = (rdates ?: emptyList()).map { toRecurrenceDateTime(it, isAllDay) },
            recurrenceId = null,
            alarms = (alarms ?: emptyList()).map { toICalAlarm(it) },
            categories = categories ?: emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = lastModified?.let { toUtcICalDateTime(it) },
            created = createdAt?.let { toUtcICalDateTime(it) },
            transparency = Transparency.fromString(transp),
            url = url?.takeIf { it.isNotBlank() },
            priority = priority ?: 0,
            rawProperties = emptyMap()
        )

        // method = null → no METHOD line (plain CalDAV storage PUT, not an iTIP message).
        return generator.generate(event, method = null, preserveDtstamp = false, includeVTimezone = true)
    }

    /** DTSTART / DTEND / DURATION resolved for one of the three supported event shapes. */
    private data class ResolvedTimes(
        val dtStart: ICalDateTime,
        val dtEnd: ICalDateTime?,
        val duration: Duration?
    )

    private fun resolveTimes(
        startTime: String?,
        endTime: String?,
        startDate: String?,
        endDate: String?,
        isAllDay: Boolean,
        timezone: String?,
        endTimezone: String?,
        recurring: Boolean
    ): ResolvedTimes {
        if (isAllDay && startDate != null) {
            val start = LocalDate.parse(startDate)
            val inclusiveEnd = LocalDate.parse(endDate ?: startDate)
            val exclusiveEnd = inclusiveEnd.plusDays(1) // RFC 5545: DTEND is exclusive
            val dtStart = ICalDateTime.fromLocalDate(start)
            return if (recurring) {
                val days = ChronoUnit.DAYS.between(start, exclusiveEnd)
                ResolvedTimes(dtStart, null, Duration.ofDays(days))
            } else {
                ResolvedTimes(dtStart, ICalDateTime.fromLocalDate(exclusiveEnd), null)
            }
        }

        if (startTime != null && endTime != null) {
            val utc = startTime.endsWith("Z") && endTime.endsWith("Z")
            return when {
                utc || timezone == null -> {
                    // UTC times, or floating times treated as UTC (matches prior behavior).
                    val startDt = utcICalDateTime(startTime)
                    val endInstant = Instant.parse(asUtc(endTime))
                    if (recurring) {
                        ResolvedTimes(startDt, null, Duration.between(startDt.toInstant(), endInstant))
                    } else {
                        ResolvedTimes(startDt, utcICalDateTime(endTime), null)
                    }
                }
                else -> {
                    // Local wall-clock times anchored to a TZID.
                    val startDt = localICalDateTime(startTime, timezone)
                    val effectiveEndTz = endTimezone ?: timezone
                    val endDt = localICalDateTime(endTime, effectiveEndTz)
                    if (recurring) {
                        ResolvedTimes(startDt, null, Duration.between(startDt.toInstant(), endDt.toInstant()))
                    } else {
                        ResolvedTimes(startDt, endDt, null)
                    }
                }
            }
        }

        // No usable times supplied (e.g. the patch-or-create fallback given only a
        // summary). A VEVENT without METHOD still needs a DTSTART (RFC 5545 §3.6.1),
        // so default to a one-hour timed event starting now rather than emitting a
        // date-less, non-conformant component. Timed (not all-day) keeps the value
        // type consistent with the isAllDay=false these callers pass.
        val startInstant = Instant.now()
        val dtStart = ICalDateTime.fromTimestamp(startInstant.toEpochMilli(), timezone = null, isDate = false)
        return if (recurring) {
            ResolvedTimes(dtStart, null, Duration.ofHours(1))
        } else {
            val dtEnd = ICalDateTime.fromTimestamp(
                startInstant.plusSeconds(3600).toEpochMilli(), timezone = null, isDate = false
            )
            ResolvedTimes(dtStart, dtEnd, null)
        }
    }

    /** Parse an RRULE value; on malformed input, fall back to a raw-preserving null (caller keeps verbatim). */
    private fun parseRRuleOrNull(rrule: String): RRule? =
        try {
            RRule.parse(rrule)
        } catch (_: Exception) {
            null
        }

    /** ISO 8601 UTC (or Z-suffixed) → UTC [ICalDateTime]. */
    private fun utcICalDateTime(iso: String): ICalDateTime {
        val instant = Instant.parse(asUtc(iso))
        return ICalDateTime.fromTimestamp(instant.toEpochMilli(), timezone = null, isDate = false)
    }

    /** ISO 8601 local wall-clock time + TZID → timezone-anchored [ICalDateTime]. */
    private fun localICalDateTime(iso: String, tzid: String): ICalDateTime {
        val basic = iso.removeSuffix("Z").replace("-", "").replace(":", "") // yyyyMMddTHHmmss
        return ICalDateTime.parse(basic, tzid)
    }

    /** RDATE/EXDATE value → [ICalDateTime]: VALUE=DATE for all-day, UTC instant otherwise. */
    private fun toRecurrenceDateTime(value: String, isAllDay: Boolean): ICalDateTime =
        if (isAllDay) ICalDateTime.fromLocalDate(LocalDate.parse(value)) else utcICalDateTime(value)

    private fun toUtcICalDateTime(instant: Instant): ICalDateTime =
        ICalDateTime.fromTimestamp(instant.toEpochMilli(), timezone = null, isDate = false)

    private fun asUtc(iso: String): String = if (iso.endsWith("Z")) iso else "${iso}Z"
}
