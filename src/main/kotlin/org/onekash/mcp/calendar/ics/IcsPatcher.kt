package org.onekash.mcp.calendar.ics

import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Patches existing ICS content, preserving properties not being updated.
 *
 * Full adoption of the vendored icaldav-core: parse the existing ICS into an
 * [ICalEvent], apply the patch fields via [ICalEvent.copy], and re-serialize with
 * [ICalGenerator]. The model's structured fields plus its `rawProperties` map
 * carry everything through the round-trip:
 * - VALARM blocks (reminders) are preserved (unless [patch] is given a new list)
 * - ATTENDEE / ORGANIZER are preserved (structured fields)
 * - X-APPLE-* and other unknown X-* properties are preserved (rawProperties)
 * - CREATED is preserved unchanged (RFC 5545 §3.8.7.1)
 *
 * Falls back to IcsBuilder when existing ICS is null/blank (the patcher is
 * sometimes used as a "patch-or-create" entry point; an absent input is the
 * create path, not a parse failure).
 *
 * On parse failure of a non-blank existing ICS (or a body with no VEVENT), throws
 * [UnparseableExistingIcsException] so the caller can surface a clean error
 * instead of silently rebuilding an event without the original's data.
 */
class IcsPatcher(
    private val builder: IcsBuilder = IcsBuilder()
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(IcsPatcher::class.java)

    private val parser = ICalParser()
    private val generator = ICalGenerator(prodId = PRODID, includeAppleExtensions = true)

    companion object {
        private const val PRODID = "-//OnekashMCP//AppleCalendarMCP 1.0//EN"
    }

    /**
     * Thrown by [patch] when [existingIcs] is non-blank but cannot be parsed into a
     * VEVENT. The caller (typically `CalendarService.updateEvent`) should map this
     * to a 422-shaped error.
     */
    class UnparseableExistingIcsException(
        message: String,
        cause: Throwable? = null
    ) : RuntimeException(message, cause)

    fun patch(
        existingIcs: String?,
        uid: String,
        summary: String? = null,
        startTime: String? = null,
        endTime: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        isAllDay: Boolean? = null,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        rrule: String? = null,
        status: String? = null,
        url: String? = null,
        categories: List<String>? = null,
        priority: Int? = null,
        endTimezone: String? = null,
        rdates: List<String>? = null,
        exdates: List<String>? = null,
        alarms: List<AlarmSpec>? = null
    ): String {
        // Sanitize all text inputs at entry point to prevent ICS injection via CRLF.
        // This protects both the icaldav round-trip path and the IcsBuilder fallback path.
        val safeSummary = summary?.let { sanitize(it) }
        val safeDescription = description?.let { sanitize(it) }
        val safeLocation = location?.let { sanitize(it) }
        val safeRrule = rrule?.let { sanitize(it) }
        val safeStatus = status?.let { sanitize(it) }
        val safeUrl = url?.let { sanitize(it) }
        val safeCategories = categories?.map { sanitize(it) }
        val safeRdates = rdates?.map { sanitize(it) }
        val safeExdates = exdates?.map { sanitize(it) }

        if (existingIcs.isNullOrBlank()) {
            return buildFresh(uid, safeSummary, startTime, endTime, startDate, endDate,
                isAllDay, safeDescription, safeLocation, timezone, safeRrule, safeStatus, safeUrl, safeCategories, priority, endTimezone, safeRdates, safeExdates, alarms)
        }

        // Parse the existing ICS. parseAllEvents() folds any parse exception into a
        // ParseResult.Error; a well-formed calendar with no VEVENT yields an empty
        // Success. Both are "cannot patch" — surface UnparseableExistingIcsException.
        val original = when (val result = parser.parseAllEvents(existingIcs)) {
            is ParseResult.Success -> result.value.firstOrNull() ?: run {
                logFailure(uid, existingIcs, "No VEVENT found")
                throw UnparseableExistingIcsException(
                    "Could not patch event: existing ICS is unparseable (No VEVENT found)"
                )
            }
            is ParseResult.Error -> {
                val ex = result.error.toException()
                logFailure(uid, existingIcs, ex.toString())
                throw UnparseableExistingIcsException(
                    "Could not patch event: existing ICS is unparseable (${ex.message ?: ex.javaClass.simpleName})",
                    ex
                )
            }
        }

        val patched = applyPatch(
            original, safeSummary, startTime, endTime, startDate, endDate,
            isAllDay, safeDescription, safeLocation, timezone, safeRrule, safeStatus, safeUrl,
            safeCategories, priority, endTimezone, safeRdates, safeExdates, alarms
        )

        // method = null → no METHOD line (plain CalDAV storage PUT).
        // preserveDtstamp = false → DTSTAMP regenerated to now on every patch.
        return generator.generate(patched, method = null, preserveDtstamp = false, includeVTimezone = true)
    }

    private fun logFailure(uid: String, existingIcs: String, detail: String) {
        val fingerprint = existingIcs.take(200).replace(Regex("[\\r\\n]+"), " ⏎ ")
        logger.warn(
            "IcsPatcher: failed to parse existing ICS for uid={} ({} chars): {} | first 200 chars: {}",
            uid, existingIcs.length, detail, fingerprint
        )
    }

    private fun buildFresh(
        uid: String,
        summary: String?,
        startTime: String?,
        endTime: String?,
        startDate: String?,
        endDate: String?,
        isAllDay: Boolean?,
        description: String?,
        location: String?,
        timezone: String?,
        rrule: String?,
        status: String?,
        url: String?,
        categories: List<String>?,
        priority: Int?,
        endTimezone: String?,
        rdates: List<String>?,
        exdates: List<String>?,
        alarms: List<AlarmSpec>?
    ): String {
        return builder.build(
            uid = uid,
            summary = summary ?: "Untitled",
            startTime = startTime,
            endTime = endTime,
            startDate = startDate,
            endDate = endDate,
            isAllDay = isAllDay ?: false,
            description = description,
            location = location,
            timezone = timezone,
            rrule = rrule,
            status = status,
            url = url,
            categories = categories,
            priority = priority,
            endTimezone = endTimezone,
            rdates = rdates,
            exdates = exdates,
            alarms = alarms
        )
    }

    /**
     * Apply the patch fields to [original] via [ICalEvent.copy]. Each nullable
     * parameter follows "null = leave existing untouched"; the only exceptions are
     * blank strings for description/location/url/rrule, which clear the property
     * (matching the previous patcher's remove-on-blank behavior).
     *
     * SEQUENCE is incremented; LAST-MODIFIED refreshed to now; CREATED preserved;
     * DTSTAMP regenerated by the generator. All unlisted fields (attendees,
     * organizer, rawProperties/X-*, color, etc.) ride through unchanged.
     */
    private fun applyPatch(
        original: ICalEvent,
        summary: String?,
        startTime: String?,
        endTime: String?,
        startDate: String?,
        endDate: String?,
        isAllDay: Boolean?,
        description: String?,
        location: String?,
        timezone: String?,
        rrule: String?,
        status: String?,
        url: String?,
        categories: List<String>?,
        priority: Int?,
        endTimezone: String?,
        rdates: List<String>?,
        exdates: List<String>?,
        alarms: List<AlarmSpec>?
    ): ICalEvent {
        val times = applyDateTimes(original, startTime, endTime, startDate, endDate, isAllDay, timezone, endTimezone)

        return original.copy(
            summary = summary ?: original.summary,
            description = if (description != null) description.takeIf { it.isNotBlank() } else original.description,
            location = if (location != null) location.takeIf { it.isNotBlank() } else original.location,
            dtStart = times.dtStart,
            dtEnd = times.dtEnd,
            duration = times.duration,
            isAllDay = times.isAllDay,
            status = if (status != null && status.isNotBlank()) EventStatus.fromString(status) else original.status,
            sequence = original.sequence + 1,
            rrule = if (rrule != null) (if (rrule.isNotBlank()) parseRRuleOrNull(rrule) else null) else original.rrule,
            exdates = if (exdates != null) exdates.map { toRecurrenceDateTime(it, times.isAllDay) } else original.exdates,
            rdates = if (rdates != null) rdates.map { toRecurrenceDateTime(it, times.isAllDay) } else original.rdates,
            categories = categories ?: original.categories,
            url = if (url != null) url.takeIf { it.isNotBlank() } else original.url,
            priority = priority ?: original.priority,
            alarms = if (alarms != null) alarms.map { IcsBuilder.toICalAlarm(it) } else original.alarms,
            lastModified = ICalDateTime.now(),
            // CREATED intentionally preserved (RFC 5545 §3.8.7.1: never changes after first set).
            created = original.created
        )
    }

    /** DTSTART / DTEND / DURATION / isAllDay after applying any date/time patch fields. */
    private data class PatchedTimes(
        val dtStart: ICalDateTime,
        val dtEnd: ICalDateTime?,
        val duration: Duration?,
        val isAllDay: Boolean
    )

    private fun applyDateTimes(
        original: ICalEvent,
        startTime: String?,
        endTime: String?,
        startDate: String?,
        endDate: String?,
        isAllDay: Boolean?,
        timezone: String?,
        endTimezone: String?
    ): PatchedTimes {
        val hasTimeUpdate = startTime != null || endTime != null
        val hasDateUpdate = startDate != null || endDate != null
        if (!hasTimeUpdate && !hasDateUpdate && isAllDay == null) {
            return PatchedTimes(original.dtStart, original.dtEnd, original.duration, original.isAllDay)
        }

        val effectiveAllDay = isAllDay ?: (hasDateUpdate && !hasTimeUpdate)
        var dtStart = original.dtStart
        var dtEnd = original.dtEnd
        var duration = original.duration

        if (effectiveAllDay && (startDate != null || endDate != null)) {
            if (startDate != null) {
                dtStart = ICalDateTime.fromLocalDate(LocalDate.parse(startDate))
            }
            // RFC 5545: DTEND is exclusive for all-day events.
            val end = LocalDate.parse(endDate ?: startDate!!)
            dtEnd = ICalDateTime.fromLocalDate(end.plusDays(1))
            duration = null
        } else if (!effectiveAllDay && (startTime != null || endTime != null)) {
            if (startTime != null) {
                dtStart = timedICalDateTime(startTime, timezone)
            }
            if (endTime != null) {
                // RFC 5545 §3.8.5.4 convention: DTEND may carry its own TZID (cross-tz
                // events). Fall back to DTSTART's timezone when endTimezone is null.
                dtEnd = timedICalDateTime(endTime, endTimezone ?: timezone)
                duration = null
            }
        }

        return PatchedTimes(dtStart, dtEnd, duration, effectiveAllDay)
    }

    private fun parseRRuleOrNull(rrule: String): RRule? =
        try {
            RRule.parse(rrule)
        } catch (_: Exception) {
            null
        }

    /**
     * ISO 8601 timed value → [ICalDateTime].
     * - Z-suffixed → UTC.
     * - TZID present → local wall-clock anchored to that zone.
     * - Neither → treated as UTC (matches the previous patcher's floating fallback).
     */
    private fun timedICalDateTime(iso: String, timezone: String?): ICalDateTime {
        if (iso.endsWith("Z")) return utcICalDateTime(iso)
        if (timezone != null) {
            val basic = iso.replace("-", "").replace(":", "") // yyyyMMddTHHmmss
            return ICalDateTime.parse(basic, timezone)
        }
        return utcICalDateTime("${iso}Z")
    }

    private fun utcICalDateTime(iso: String): ICalDateTime {
        val instant = Instant.parse(iso)
        return ICalDateTime.fromTimestamp(instant.toEpochMilli(), timezone = null, isDate = false)
    }

    private fun toRecurrenceDateTime(value: String, isAllDay: Boolean): ICalDateTime =
        if (isAllDay) {
            ICalDateTime.fromLocalDate(LocalDate.parse(value))
        } else {
            utcICalDateTime(if (value.endsWith("Z")) value else "${value}Z")
        }

    /**
     * Sanitize text values to prevent ICS injection via CRLF.
     * Strips CR/LF so a value like "text\r\nX-EVIL:injected" cannot smuggle a
     * separate property into the emitted calendar.
     */
    private fun sanitize(value: String): String {
        return value.replace("\r\n", " ").replace("\r", " ").replace("\n", " ")
    }
}
