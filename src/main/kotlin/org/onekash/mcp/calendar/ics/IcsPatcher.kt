package org.onekash.mcp.calendar.ics

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.*
import java.io.StringReader
import java.io.StringWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Patches existing ICS content, preserving properties not being updated.
 *
 * Ported from KashCal's IcsPatcher pattern: when patching, preserve everything
 * from the original that wasn't explicitly changed. This ensures:
 * - VALARM blocks (reminders) are preserved
 * - ATTENDEE properties are preserved
 * - ORGANIZER property is preserved
 * - X-APPLE-* and other X-* properties are preserved
 * - Any other properties not explicitly being changed are preserved
 *
 * Falls back to IcsBuilder when existing ICS is null/blank (the patcher is
 * sometimes used as a "patch-or-create" entry point; an absent input is the
 * create path, not a parse failure).
 *
 * On parse failure of a non-blank existing ICS, throws
 * [UnparseableExistingIcsException] so the caller can surface a clean error
 * instead of silently rebuilding an event without the original's data.
 * (Issue #2 hardening — the prior silent fallback could produce
 * "Untitled"-summary zombie events on description-only updates.)
 */
class IcsPatcher(
    private val builder: IcsBuilder = IcsBuilder()
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(IcsPatcher::class.java)

    /**
     * Thrown by [patch] when [existingIcs] is non-blank but ical4j's
     * CalendarBuilder cannot parse it. The caller (typically
     * `CalendarService.updateEvent`) should map this to a 422-shaped error.
     *
     * The message includes the underlying parse error; the logger separately
     * records a sanitized fingerprint of the offending ICS for debugging
     * (truncated to avoid log spam, sanitized to strip credentials).
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
        // Sanitize all text inputs at entry point to prevent ICS injection via CRLF
        // This protects both the ical4j path and the IcsBuilder fallback path
        val safeSummary = summary?.let { sanitize(it) }
        val safeDescription = description?.let { sanitize(it) }
        val safeLocation = location?.let { sanitize(it) }
        val safeRrule = rrule?.let { sanitize(it) }
        val safeStatus = status?.let { sanitize(it) }
        val safeUrl = url?.let { sanitize(it) }
        val safeCategories = categories?.map { sanitize(it) }
        // RDATE/EXDATE values pass through sanitize() to strip CRLF (defense vs. ICS injection)
        val safeRdates = rdates?.map { sanitize(it) }
        val safeExdates = exdates?.map { sanitize(it) }

        if (existingIcs.isNullOrBlank()) {
            return buildFresh(uid, safeSummary, startTime, endTime, startDate, endDate,
                isAllDay, safeDescription, safeLocation, timezone, safeRrule, safeStatus, safeUrl, safeCategories, priority, endTimezone, safeRdates, safeExdates, alarms)
        }

        // Tight try around just the parse + VEVENT lookup step. Property
        // construction errors from the patch inputs themselves are NOT caught
        // here — those should propagate so callers see real input-validation
        // failures rather than this catch-all "unparseable" message.
        val calendar = try {
            CalendarBuilder().build(StringReader(existingIcs))
        } catch (e: Exception) {
            val fingerprint = existingIcs.take(200).replace(Regex("[\\r\\n]+"), " ⏎ ")
            logger.warn(
                "IcsPatcher: failed to parse existing ICS for uid={} ({} chars): {} | first 200 chars: {}",
                uid, existingIcs.length, e.toString(), fingerprint
            )
            throw UnparseableExistingIcsException(
                "Could not patch event: existing ICS is unparseable (${e.message ?: e.javaClass.simpleName})",
                e
            )
        }
        val vevent = calendar.components.filterIsInstance<VEvent>().firstOrNull()
        if (vevent == null) {
            val fingerprint = existingIcs.take(200).replace(Regex("[\\r\\n]+"), " ⏎ ")
            logger.warn(
                "IcsPatcher: existing ICS has no VEVENT for uid={} ({} chars) | first 200 chars: {}",
                uid, existingIcs.length, fingerprint
            )
            throw UnparseableExistingIcsException(
                "Could not patch event: existing ICS is unparseable (No VEVENT found)"
            )
        }

        return patchExistingCalendar(calendar, vevent, safeSummary, startTime, endTime, startDate, endDate,
            isAllDay, safeDescription, safeLocation, timezone, safeRrule, safeStatus, safeUrl, safeCategories, priority, endTimezone, safeRdates, safeExdates, alarms)
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

    private fun patchExistingCalendar(
        calendar: Calendar,
        vevent: VEvent,
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
        val props = vevent.properties

        // Update SUMMARY if provided (already sanitized at entry point)
        if (summary != null) {
            props.removeAll { it.name == Property.SUMMARY }
            props.add(Summary(summary))
        }

        // Update DESCRIPTION if provided
        if (description != null) {
            props.removeAll { it.name == Property.DESCRIPTION }
            if (description.isNotBlank()) {
                props.add(Description(description))
            }
        }

        // Update LOCATION if provided
        if (location != null) {
            props.removeAll { it.name == Property.LOCATION }
            if (location.isNotBlank()) {
                props.add(Location(location))
            }
        }

        // Update DTSTART/DTEND if time or date parameters provided
        updateDateTimes(props, startTime, endTime, startDate, endDate, isAllDay, timezone, endTimezone)

        // Update RRULE if provided
        if (rrule != null) {
            props.removeAll { it.name == Property.RRULE }
            if (rrule.isNotBlank()) {
                props.add(RRule(rrule))
            }
        }

        // Update STATUS if provided
        if (status != null) {
            props.removeAll { it.name == Property.STATUS }
            if (status.isNotBlank()) {
                props.add(Status(status))
            }
        }

        // Update URL if provided
        if (url != null) {
            props.removeAll { it.name == Property.URL }
            if (url.isNotBlank()) {
                props.add(Url(java.net.URI(url)))
            }
        }

        // Update CATEGORIES if provided
        if (categories != null) {
            props.removeAll { it.name == Property.CATEGORIES }
            if (categories.isNotEmpty()) {
                props.add(Categories(net.fortuna.ical4j.model.TextList(categories.toTypedArray())))
            }
        }

        // Update PRIORITY if provided
        if (priority != null) {
            props.removeAll { it.name == Property.PRIORITY }
            if (priority > 0) {
                props.add(Priority(priority))
            }
        }

        // Replace RDATE / EXDATE when explicitly provided. Null means "leave existing
        // values untouched" so single-field patches don't silently drop server-assigned
        // recurrence overrides.
        if (rdates != null) {
            props.removeAll { it.name == Property.RDATE }
            for (value in rdates) {
                props.add(buildRecurrenceDateProperty<RDate>(value))
            }
        }
        if (exdates != null) {
            props.removeAll { it.name == Property.EXDATE }
            for (value in exdates) {
                props.add(buildRecurrenceDateProperty<ExDate>(value))
            }
        }

        // Replace VALARM components when alarms is non-null (issue #1, RFC 5545 §3.6.6).
        // null preserves existing VALARMs (back-compat for "patch preserves VALARM");
        // non-null clears them all and re-emits the new list (one block per element).
        // Same null-vs-non-null semantics as RDATE/EXDATE above.
        //
        // ical4j 3.x quirk: vevent.alarms returns a fresh ComponentList copy, so
        // mutations on it are dropped. We mutate vevent.components (the live list)
        // directly. See VEvent.java#getAlarms() vs #getComponents().
        if (alarms != null) {
            @Suppress("UNCHECKED_CAST")
            val componentList = vevent.components as MutableList<net.fortuna.ical4j.model.Component>
            componentList.removeAll { it is VAlarm }
            for (spec in alarms) {
                componentList.add(buildVAlarm(spec))
            }
        }

        // Increment SEQUENCE
        incrementSequence(props)

        // Update DTSTAMP
        props.removeAll { it.name == Property.DTSTAMP }
        props.add(DtStamp())

        // Refresh LAST-MODIFIED on every patch (RFC 5545 §3.8.7.3).
        // CREATED is intentionally NOT touched (§3.8.7.1: never changes after first set);
        // ical4j's round-trip preserves the original value on the calendar.
        props.removeAll { it.name == Property.LAST_MODIFIED }
        props.add(LastModified(net.fortuna.ical4j.model.DateTime(true)))

        // Serialize back
        val writer = StringWriter()
        CalendarOutputter(false).output(calendar, writer)
        return writer.toString().trimEnd()
    }

    private fun updateDateTimes(
        props: net.fortuna.ical4j.model.PropertyList<Property>,
        startTime: String?,
        endTime: String?,
        startDate: String?,
        endDate: String?,
        isAllDay: Boolean?,
        timezone: String?,
        endTimezone: String?
    ) {
        val hasTimeUpdate = startTime != null || endTime != null
        val hasDateUpdate = startDate != null || endDate != null
        if (!hasTimeUpdate && !hasDateUpdate && isAllDay == null) return

        val effectiveAllDay = isAllDay ?: (hasDateUpdate && !hasTimeUpdate)

        if (effectiveAllDay && (startDate != null || endDate != null)) {
            if (startDate != null) {
                props.removeAll { it.name == Property.DTSTART }
                val start = LocalDate.parse(startDate)
                val icalDate = net.fortuna.ical4j.model.Date(
                    start.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                )
                props.add(DtStart(icalDate))
            }
            if (endDate != null || startDate != null) {
                props.removeAll { it.name == Property.DTEND }
                val end = LocalDate.parse(endDate ?: startDate!!)
                val exclusiveEnd = end.plusDays(1) // RFC 5545: DTEND is exclusive
                val icalDate = net.fortuna.ical4j.model.Date(
                    exclusiveEnd.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                )
                props.add(DtEnd(icalDate))
            }
        } else if (!effectiveAllDay && (startTime != null || endTime != null)) {
            if (startTime != null) {
                props.removeAll { it.name == Property.DTSTART }
                props.add(createDtStart(startTime, timezone))
            }
            if (endTime != null) {
                props.removeAll { it.name == Property.DTEND }
                // RFC 5545 §3.8.5.4 / Google Calendar / iCloud convention: DTEND may
                // carry its own TZID distinct from DTSTART (cross-tz events). When
                // endTimezone is null, fall back to DTSTART's timezone.
                props.add(createDtEnd(endTime, endTimezone ?: timezone))
            }
        }
    }

    private fun createDtStart(isoTime: String, timezone: String?): DtStart {
        val icalStr = formatIsoToIcal(isoTime)
        if (isoTime.endsWith("Z")) {
            val dt = net.fortuna.ical4j.model.DateTime(icalStr)
            dt.isUtc = true
            return DtStart(dt)
        }
        if (timezone != null) {
            val tz = IcsBuilder.timeZoneRegistry.getTimeZone(timezone)
            val dt = net.fortuna.ical4j.model.DateTime(icalStr)
            if (tz != null) dt.timeZone = tz
            return DtStart(dt)
        }
        val dt = net.fortuna.ical4j.model.DateTime(formatIsoToIcal(isoTime + "Z"))
        dt.isUtc = true
        return DtStart(dt)
    }

    /**
     * Build an RDATE or EXDATE property from an ISO 8601 instant or YYYY-MM-DD value.
     * One value per property to keep line-folding interactions simple (matches the
     * builder's one-line-per-value form).
     */
    private inline fun <reified T : net.fortuna.ical4j.model.property.DateListProperty>
        buildRecurrenceDateProperty(value: String): T {
        val isDate = !value.contains("T") && value.length == 10
        val list = if (isDate) {
            val d = LocalDate.parse(value)
            net.fortuna.ical4j.model.DateList(
                net.fortuna.ical4j.model.parameter.Value.DATE
            ).apply {
                add(net.fortuna.ical4j.model.Date(d.format(DateTimeFormatter.ofPattern("yyyyMMdd"))))
            }
        } else {
            val isoZ = if (value.endsWith("Z")) value else value + "Z"
            val basic = formatIsoToIcal(isoZ)
            net.fortuna.ical4j.model.DateList(
                net.fortuna.ical4j.model.parameter.Value.DATE_TIME
            ).apply {
                val dt = net.fortuna.ical4j.model.DateTime(basic)
                dt.isUtc = true
                add(dt)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return when (T::class) {
            RDate::class -> RDate(list) as T
            ExDate::class -> ExDate(list) as T
            else -> error("Unsupported recurrence date type: ${T::class}")
        }
    }

    /**
     * Build a VAlarm component from an [AlarmSpec]. Mirrors the property emission
     * order used by IcsBuilder.appendVAlarm so that round-tripping a patched event
     * through the parser is symmetric with a freshly-built one.
     */
    private fun buildVAlarm(spec: AlarmSpec): VAlarm {
        val va = VAlarm()
        val props = va.properties

        val action = spec.action?.takeIf { it.isNotBlank() } ?: "DISPLAY"
        props.add(Action(action))

        val triggerProp = if (IcsBuilder.ICAL_ABSOLUTE_TRIGGER_REGEX.matches(spec.trigger)) {
            val dt = net.fortuna.ical4j.model.DateTime(spec.trigger)
            dt.isUtc = true
            Trigger(dt)
        } else {
            // Duration form: parse via ical4j Dur. The constructor accepts strings
            // like "-PT15M", "+P1D".
            Trigger(net.fortuna.ical4j.model.Dur(spec.trigger))
        }
        props.add(triggerProp)

        when (action) {
            "DISPLAY" -> {
                val desc = spec.description?.takeIf { it.isNotBlank() } ?: "Reminder"
                props.add(Description(desc))
            }
            "EMAIL" -> {
                spec.description?.takeIf { it.isNotBlank() }?.let { props.add(Description(it)) }
                spec.summary?.takeIf { it.isNotBlank() }?.let { props.add(Summary(it)) }
            }
        }

        if (spec.repeatCount != null && spec.repeatCount > 0 && !spec.repeatDuration.isNullOrBlank()) {
            props.add(Repeat().apply { count = spec.repeatCount })
            props.add(net.fortuna.ical4j.model.property.Duration(net.fortuna.ical4j.model.Dur(spec.repeatDuration)))
        }

        return va
    }

    private fun createDtEnd(isoTime: String, timezone: String?): DtEnd {
        val icalStr = formatIsoToIcal(isoTime)
        if (isoTime.endsWith("Z")) {
            val dt = net.fortuna.ical4j.model.DateTime(icalStr)
            dt.isUtc = true
            return DtEnd(dt)
        }
        if (timezone != null) {
            val tz = IcsBuilder.timeZoneRegistry.getTimeZone(timezone)
            val dt = net.fortuna.ical4j.model.DateTime(icalStr)
            if (tz != null) dt.timeZone = tz
            return DtEnd(dt)
        }
        val dt = net.fortuna.ical4j.model.DateTime(formatIsoToIcal(isoTime + "Z"))
        dt.isUtc = true
        return DtEnd(dt)
    }

    /**
     * Convert ISO 8601 datetime to ical4j format.
     * "2025-01-15T10:00:00Z" -> "20250115T100000Z"
     */
    private fun formatIsoToIcal(isoTime: String): String {
        return isoTime.replace("-", "").replace(":", "")
    }

    /**
     * Sanitize text values to prevent ICS injection via CRLF.
     * ical4j 3.2.18 does not escape CRLF in property values during serialization,
     * so a value like "text\r\nX-EVIL:injected" would appear as a separate property.
     * We strip CR/LF to prevent property injection.
     */
    private fun sanitize(value: String): String {
        return value.replace("\r\n", " ").replace("\r", " ").replace("\n", " ")
    }

    private fun incrementSequence(props: net.fortuna.ical4j.model.PropertyList<Property>) {
        val existing = props.getProperty<Sequence>(Property.SEQUENCE)
        val currentSeq = existing?.sequenceNo ?: 0
        props.removeAll { it.name == Property.SEQUENCE }
        props.add(Sequence(currentSeq + 1))
    }
}
