package org.onekash.mcp.calendar.ics

import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.recurrence.RRuleExpander
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

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
    private val expander = RRuleExpander()

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

    /**
     * Thrown by [patchOccurrence] / [exdateOccurrence] when the existing resource's
     * master VEVENT is not a recurring series (no RRULE and no RDATE). A
     * single-occurrence scope has no meaning without a recurrence set (RFC 5545
     * §3.8.4.4); the caller should map this to a validation error.
     */
    class NotARecurringSeriesException(message: String) : RuntimeException(message)

    /**
     * Thrown by [truncateSeries] / [splitSeries] when the this-and-future target is the
     * series' FIRST occurrence: there is no earlier occurrence to keep, so the operation
     * is equivalent to acting on the whole series. The caller should delete the whole
     * resource (delete) or patch the master in place (edit) instead of splitting.
     */
    class FirstOccurrenceException(message: String) : RuntimeException(message)

    /**
     * Thrown when reducing/truncating a series requires expanding it past the expander's
     * per-series work-bound (CWE-400 guard): a this-and-future split or delete on a
     * pathologically dense/long recurring series would enumerate its whole history up to
     * the cut. Bridge-level mirror of [RRuleExpander.ExpansionLimitException] (see the
     * ical4j confinement rule), matching [UnparseableExistingIcsException]; the caller
     * should map it to a 413-shaped error steering to a narrower operation.
     */
    class ExpansionLimitException(val uid: String, val limit: Int) : RuntimeException(
        "Recurring event '$uid' expands to more than $limit occurrences before the requested cut"
    )

    /**
     * The two resource bodies a this-and-future edit produces. CalDAV stores one UID per
     * resource, so the continuing series (fresh UID) cannot share the master's `.ics`:
     * [truncatedMaster] is PUT back to the existing href, [newSeries] is created as a new
     * resource.
     */
    data class SplitResult(val truncatedMaster: String, val newSeries: String)

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

    /**
     * Edit a single occurrence of a recurring series (scope = this occurrence).
     *
     * Stateless RECURRENCE-ID exception (RFC 5545 §3.8.4.4): parse [existingIcs],
     * find (or create) the exception VEVENT for the occurrence identified by
     * [recurrenceId] (in iCal wire form, as minted into the occurrence handle),
     * apply the patch fields to that exception, and re-serialize the master plus
     * every exception into one resource body. The master's DTSTART and RRULE are
     * never touched; series-level fields (rrule/rdates/exdates) are deliberately not
     * accepted here (rejected upstream) so an occurrence edit can never rewrite the
     * series.
     *
     * A brand-new exception starts at the occurrence's own instant, so patching only
     * the summary leaves its DTSTART at that instant (not the master's). Editing an
     * occurrence that already has an exception updates it in place (no duplicate).
     *
     * @throws UnparseableExistingIcsException if [existingIcs] is blank/unparseable or has no master VEVENT.
     * @throws NotARecurringSeriesException if the master carries no RRULE/RDATE.
     */
    fun patchOccurrence(
        existingIcs: String?,
        recurrenceId: String,
        summary: String? = null,
        startTime: String? = null,
        endTime: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        isAllDay: Boolean? = null,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        status: String? = null,
        url: String? = null,
        categories: List<String>? = null,
        priority: Int? = null,
        endTimezone: String? = null,
        alarms: List<AlarmSpec>? = null
    ): String {
        val (master, overrides) = parseSeries(existingIcs)
        val recid = normalizeRecurrenceId(recurrenceId, master)
        val targetTs = recid.timestamp

        val existing = overrides.firstOrNull { normalizedRecidTimestamp(it, master) == targetTs }
        val base = existing ?: newExceptionBase(master, recid)

        val patchedException = applyPatch(
            base,
            summary = summary?.let { sanitize(it) },
            startTime = startTime,
            endTime = endTime,
            startDate = startDate,
            endDate = endDate,
            isAllDay = isAllDay,
            description = description?.let { sanitize(it) },
            location = location?.let { sanitize(it) },
            timezone = timezone,
            // Series-level fields never flow into a single-occurrence exception.
            rrule = null,
            status = status?.let { sanitize(it) },
            url = url?.let { sanitize(it) },
            categories = categories?.map { sanitize(it) },
            priority = priority,
            endTimezone = endTimezone,
            rdates = null,
            exdates = null,
            alarms = alarms
        )

        val otherOverrides = overrides.filter { it !== existing }
        val events = listOf(master) + otherOverrides + patchedException
        return generator.generateBatch(events, includeMethod = false, includeVTimezone = true)
    }

    /**
     * Cancel a single occurrence of a recurring series (scope = this occurrence):
     * add [recurrenceId] to the master's EXDATE and drop any exception VEVENT for
     * that instant. The master's DTSTART and RRULE are unchanged.
     *
     * @throws UnparseableExistingIcsException if [existingIcs] is blank/unparseable or has no master VEVENT.
     * @throws NotARecurringSeriesException if the master carries no RRULE/RDATE.
     */
    fun exdateOccurrence(existingIcs: String?, recurrenceId: String): String {
        val (master, overrides) = parseSeries(existingIcs)
        val recid = normalizeRecurrenceId(recurrenceId, master)
        val targetTs = recid.timestamp

        val remaining = overrides.filterNot { normalizedRecidTimestamp(it, master) == targetTs }
        val alreadyExcluded = master.exdates.any { it.timestamp == targetTs }
        val exdates = if (alreadyExcluded) master.exdates else master.exdates + recid

        val updatedMaster = master.copy(
            exdates = exdates,
            sequence = master.sequence + 1,
            lastModified = ICalDateTime.now(),
            created = master.created
        )
        val events = listOf(updatedMaster) + remaining
        return generator.generateBatch(events, includeMethod = false, includeVTimezone = true)
    }

    /**
     * Truncate a recurring series at [recurrenceId] (scope = this-and-future, delete):
     * cap the master's RRULE with UNTIL at the last occurrence before [recurrenceId], so
     * the occurrence and every later one drop out while everything earlier stays. The
     * master's DTSTART is never touched. EXDATE/RDATE and exception VEVENTs at or after the
     * cut are removed (they are orphaned once the series ends earlier).
     *
     * @throws UnparseableExistingIcsException if [existingIcs] is blank/unparseable or has no master VEVENT.
     * @throws NotARecurringSeriesException if the master carries no RRULE/RDATE.
     * @throws FirstOccurrenceException if [recurrenceId] is the series' first occurrence.
     */
    fun truncateSeries(existingIcs: String?, recurrenceId: String): String {
        val (master, overrides) = parseSeries(existingIcs)
        val recid = normalizeRecurrenceId(recurrenceId, master)
        val (newMaster, keptOverrides) = truncateMaster(master, overrides, recid.timestamp)
        return generator.generateBatch(listOf(newMaster) + keptOverrides, includeMethod = false, includeVTimezone = true)
    }

    /**
     * Split a recurring series at [recurrenceId] (scope = this-and-future, edit): truncate
     * the master as [truncateSeries] does, and produce a brand-new series (fresh UID) that
     * starts at the occurrence, carries the master's recurrence rule (COUNT reduced by the
     * occurrences the master keeps, UNTIL preserved), and has the patch fields applied. The
     * two bodies are returned separately ([SplitResult]) because they must live in
     * different CalDAV resources.
     *
     * Series-level fields (rrule/rdates/exdates) are deliberately not accepted; a
     * this-and-future edit changes the occurrences' content, not the recurrence pattern.
     *
     * @throws UnparseableExistingIcsException if [existingIcs] is blank/unparseable or has no master VEVENT.
     * @throws NotARecurringSeriesException if the master carries no RRULE/RDATE.
     * @throws FirstOccurrenceException if [recurrenceId] is the series' first occurrence.
     */
    fun splitSeries(
        existingIcs: String?,
        recurrenceId: String,
        summary: String? = null,
        startTime: String? = null,
        endTime: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        isAllDay: Boolean? = null,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        endTimezone: String? = null,
        alarms: List<AlarmSpec>? = null
    ): SplitResult {
        val (master, overrides) = parseSeries(existingIcs)
        val recid = normalizeRecurrenceId(recurrenceId, master)

        val (newMaster, keptOverrides) = truncateMaster(master, overrides, recid.timestamp)
        val truncatedIcs = generator.generateBatch(
            listOf(newMaster) + keptOverrides, includeMethod = false, includeVTimezone = true
        )

        val freshUid = "${java.util.UUID.randomUUID()}@onekash-mcp"
        val newSeriesBase = newSeriesFromOccurrence(master, recid, freshUid)
        val patched = applyPatch(
            newSeriesBase,
            summary = summary?.let { sanitize(it) },
            startTime = startTime,
            endTime = endTime,
            startDate = startDate,
            endDate = endDate,
            isAllDay = isAllDay,
            description = description?.let { sanitize(it) },
            location = location?.let { sanitize(it) },
            timezone = timezone,
            // Series-level fields never flow through a scoped edit; keep the new series' rule.
            rrule = null,
            status = null,
            url = null,
            categories = null,
            priority = null,
            endTimezone = endTimezone,
            rdates = null,
            exdates = null,
            alarms = alarms
        ).copy(sequence = 0)  // a brand-new series starts at SEQUENCE 0

        // Carry the modified instances that fall AFTER the cut onto the new series, mirroring how
        // future EXDATE/RDATE are carried. Each keeps its RECURRENCE-ID and its own customization,
        // but is re-based to the fresh UID so it belongs to the continuing series. The override AT
        // the cut (== targetTs) is deliberately dropped: that instance is the new series' first
        // occurrence, which the this-and-future patch itself defines.
        val carriedOverrides = overrides
            .filter { (normalizedRecidTimestamp(it, master) ?: Long.MIN_VALUE) > recid.timestamp }
            .map { it.copy(uid = freshUid, importId = freshUid) }

        val newSeriesIcs = generator.generateBatch(
            listOf(patched) + carriedOverrides, includeMethod = false, includeVTimezone = true
        )
        return SplitResult(truncatedIcs, newSeriesIcs)
    }

    /**
     * Build the capped master for a this-and-future cut at [targetTs] plus the exception
     * VEVENTs that survive it (those strictly before the cut). Throws
     * [FirstOccurrenceException] when no occurrence precedes the cut.
     */
    private fun truncateMaster(
        master: ICalEvent,
        overrides: List<ICalEvent>,
        targetTs: Long
    ): Pair<ICalEvent, List<ICalEvent>> {
        val keptInstants = occurrenceInstantsBefore(master, targetTs, applyExdates = true)
        if (keptInstants.isEmpty()) {
            throw FirstOccurrenceException(
                "this_and_future targets the first occurrence; act on the whole series instead"
            )
        }
        val lastKept = keptInstants.max()
        // UNTIL is inclusive (RFC 5545 §3.3.10): landing it on the last kept occurrence keeps
        // that instance and drops the target and everything after. A timed series requires a
        // UTC DATE-TIME UNTIL; an all-day series requires a DATE UNTIL.
        val until = ICalDateTime.fromTimestamp(lastKept, timezone = null, isDate = master.dtStart.isDate)
        val cappedRrule = master.rrule?.copy(until = until, count = null)

        val newMaster = master.copy(
            rrule = cappedRrule,
            exdates = master.exdates.filter { it.timestamp < targetTs },
            rdates = master.rdates.filter { it.timestamp < targetTs },
            sequence = master.sequence + 1,
            lastModified = ICalDateTime.now(),
            created = master.created
        )
        val keptOverrides = overrides.filter {
            (normalizedRecidTimestamp(it, master) ?: Long.MAX_VALUE) < targetTs
        }
        return newMaster to keptOverrides
    }

    /**
     * The continuing series for a this-and-future edit: the master copied to a fresh UID,
     * its DTSTART at the occurrence's own instant (DTEND shifted by the master's duration),
     * its RRULE's COUNT reduced by the occurrences the truncated master keeps (UNTIL and
     * open-ended rules pass through), and its future EXDATE/RDATE carried over. It takes the
     * [freshUid] the split mints so the continuing series and its carried-over exceptions share
     * one UID. [applyPatch] then applies the caller's edits.
     */
    private fun newSeriesFromOccurrence(master: ICalEvent, recid: ICalDateTime, freshUid: String): ICalEvent {
        val targetTs = recid.timestamp
        val dtEnd = master.dtEnd?.let { mEnd ->
            ICalDateTime.fromTimestamp(
                targetTs + (mEnd.timestamp - master.dtStart.timestamp),
                timezone = mEnd.timezone,
                isDate = mEnd.isDate
            )
        }
        val newRrule = master.rrule?.let { r ->
            val originalCount = r.count
            if (originalCount != null) {
                val consumed = occurrenceInstantsBefore(master, targetTs, applyExdates = false).size
                r.copy(count = (originalCount - consumed).coerceAtLeast(1))
            } else {
                r
            }
        }
        return master.copy(
            uid = freshUid,
            importId = freshUid,
            recurrenceId = null,
            dtStart = recid,
            dtEnd = dtEnd,
            duration = if (master.dtEnd == null) master.duration else null,
            rrule = newRrule,
            exdates = master.exdates.filter { it.timestamp >= targetTs },
            rdates = master.rdates.filter { it.timestamp >= targetTs },
            sequence = 0,
            created = ICalDateTime.now(),
            lastModified = ICalDateTime.now()
        )
    }

    /**
     * Instants of the master's occurrences strictly before [targetTs]. [applyExdates] =
     * false counts pure RRULE/RDATE occurrences (for reducing COUNT, which per RFC 5545
     * bounds RRULE generation independent of EXDATE); true removes EXDATE'd days (for the
     * kept set, so a cancelled tail day is not treated as the last kept occurrence).
     */
    private fun occurrenceInstantsBefore(master: ICalEvent, targetTs: Long, applyExdates: Boolean): List<Long> {
        val source = if (applyExdates) master else master.copy(exdates = emptyList(), rdates = emptyList())
        val rangeStart = Instant.ofEpochMilli(minOf(master.dtStart.timestamp, targetTs))
        val rangeEnd = Instant.ofEpochMilli(targetTs)
        // Re-throw the expander's work-bound abort as the bridge-level exception, so the
        // root-module caller never touches the ical4j-confined core type.
        val expanded = try {
            expander.expand(source, rangeStart, rangeEnd)
        } catch (e: RRuleExpander.ExpansionLimitException) {
            throw ExpansionLimitException(e.uid, e.limit)
        }
        return expanded
            .map { it.dtStart.timestamp }
            .filter { it < targetTs }
    }

    /**
     * Parse [existingIcs] into its master VEVENT and its exception (RECURRENCE-ID)
     * VEVENTs, enforcing that the master is a recurring series.
     */
    private fun parseSeries(existingIcs: String?): Pair<ICalEvent, List<ICalEvent>> {
        if (existingIcs.isNullOrBlank()) {
            throw UnparseableExistingIcsException("Could not patch occurrence: existing ICS is required")
        }
        val events = when (val result = parser.parseAllEvents(existingIcs)) {
            is ParseResult.Success -> result.value
            is ParseResult.Error -> {
                val ex = result.error.toException()
                logFailure("<occurrence>", existingIcs, ex.toString())
                throw UnparseableExistingIcsException(
                    "Could not patch occurrence: existing ICS is unparseable (${ex.message ?: ex.javaClass.simpleName})",
                    ex
                )
            }
        }
        val master = events.firstOrNull { it.recurrenceId == null }
            ?: throw UnparseableExistingIcsException("Could not patch occurrence: no master VEVENT found")
        if (master.rrule == null && master.rdates.isEmpty()) {
            throw NotARecurringSeriesException(
                "Single-occurrence scope requires a recurring series, but this event has no RRULE/RDATE"
            )
        }
        val overrides = events.filter { it.recurrenceId != null }
        return master to overrides
    }

    /**
     * Parse a RECURRENCE-ID wire string to an [ICalDateTime] and reconcile its value
     * type to the master's DTSTART. A local (floating) form is anchored to the
     * master's timezone so the instant is stable regardless of the host zone; a
     * DATE or Z-suffixed form is unambiguous on its own.
     */
    private fun normalizeRecurrenceId(recurrenceId: String, master: ICalEvent): ICalDateTime {
        val raw = recurrenceId.trim()
        val parsed = when {
            raw.length == 8 && raw.all { it.isDigit() } -> ICalDateTime.parse(raw)          // DATE
            raw.endsWith("Z") -> ICalDateTime.parse(raw)                                     // UTC instant
            else -> ICalDateTime.parse(raw, master.dtStart.timezone?.id)                     // local → master zone
        }
        return RRuleExpander.normalizeToMasterValueType(parsed, master.dtStart)
    }

    /** The occurrence instant an override identifies, reconciled to the master's value type. */
    private fun normalizedRecidTimestamp(override: ICalEvent, master: ICalEvent): Long? =
        override.recurrenceId?.let { RRuleExpander.normalizeToMasterValueType(it, master.dtStart).timestamp }

    /**
     * Build the base exception VEVENT for a fresh single-occurrence edit: the master
     * copied to this instance, its DTSTART at the occurrence's own instant and DTEND
     * shifted by the master's duration, its RRULE/RDATE/EXDATE stripped (an instance
     * is not itself a series), and its RECURRENCE-ID set. [applyPatch] then applies
     * the caller's edits and bumps SEQUENCE.
     */
    private fun newExceptionBase(master: ICalEvent, recid: ICalDateTime): ICalEvent {
        val dtEnd = master.dtEnd?.let { mEnd ->
            ICalDateTime.fromTimestamp(
                recid.timestamp + (mEnd.timestamp - master.dtStart.timestamp),
                timezone = mEnd.timezone,
                isDate = mEnd.isDate
            )
        }
        return master.copy(
            recurrenceId = recid,
            dtStart = recid,
            dtEnd = dtEnd,
            duration = if (master.dtEnd == null) master.duration else null,
            rrule = null,
            rdates = emptyList(),
            exdates = emptyList()
        )
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
     * - Explicit zone ('Z' or a numeric offset) → absolute instant, and it wins over
     *   [timezone] (matches IcsBuilder's create-path precedence).
     * - Naive value + TZID → local wall-clock anchored to that zone.
     * - Naive value, no TZID → treated as UTC (the previous patcher's floating fallback).
     */
    private fun timedICalDateTime(iso: String, timezone: String?): ICalDateTime {
        if (iso.endsWith("Z") || IcsBuilder.OFFSET_SUFFIX.containsMatchIn(iso)) {
            val instant = OffsetDateTime.parse(iso).toInstant()
            return ICalDateTime.fromTimestamp(instant.toEpochMilli(), timezone = null, isDate = false)
        }
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
