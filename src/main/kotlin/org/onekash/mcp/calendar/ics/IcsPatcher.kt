package org.onekash.mcp.calendar.ics

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
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
 * Falls back to IcsBuilder when existing ICS is null/empty or unparseable.
 */
class IcsPatcher(
    private val builder: IcsBuilder = IcsBuilder()
) {

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
        priority: Int? = null
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

        if (existingIcs.isNullOrBlank()) {
            return buildFresh(uid, safeSummary, startTime, endTime, startDate, endDate,
                isAllDay, safeDescription, safeLocation, timezone, safeRrule, safeStatus, safeUrl, safeCategories, priority)
        }

        return try {
            patchWithIcal4j(existingIcs, safeSummary, startTime, endTime, startDate, endDate,
                isAllDay, safeDescription, safeLocation, timezone, safeRrule, safeStatus, safeUrl, safeCategories, priority)
        } catch (e: Exception) {
            buildFresh(uid, safeSummary, startTime, endTime, startDate, endDate,
                isAllDay, safeDescription, safeLocation, timezone, safeRrule, safeStatus, safeUrl, safeCategories, priority)
        }
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
        priority: Int?
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
            priority = priority
        )
    }

    private fun patchWithIcal4j(
        existingIcs: String,
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
        priority: Int?
    ): String {
        val calendar = CalendarBuilder().build(StringReader(existingIcs))
        val vevent = calendar.components.filterIsInstance<VEvent>().firstOrNull()
            ?: throw IllegalArgumentException("No VEVENT found")

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
        updateDateTimes(props, startTime, endTime, startDate, endDate, isAllDay, timezone)

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

        // Increment SEQUENCE
        incrementSequence(props)

        // Update DTSTAMP
        props.removeAll { it.name == Property.DTSTAMP }
        props.add(DtStamp())

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
        timezone: String?
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
                props.add(createDtEnd(endTime, timezone))
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
            val registry = TimeZoneRegistryFactory.getInstance().createRegistry()
            val tz = registry.getTimeZone(timezone)
            val dt = net.fortuna.ical4j.model.DateTime(icalStr)
            if (tz != null) dt.timeZone = tz
            return DtStart(dt)
        }
        val dt = net.fortuna.ical4j.model.DateTime(formatIsoToIcal(isoTime + "Z"))
        dt.isUtc = true
        return DtStart(dt)
    }

    private fun createDtEnd(isoTime: String, timezone: String?): DtEnd {
        val icalStr = formatIsoToIcal(isoTime)
        if (isoTime.endsWith("Z")) {
            val dt = net.fortuna.ical4j.model.DateTime(icalStr)
            dt.isUtc = true
            return DtEnd(dt)
        }
        if (timezone != null) {
            val registry = TimeZoneRegistryFactory.getInstance().createRegistry()
            val tz = registry.getTimeZone(timezone)
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
