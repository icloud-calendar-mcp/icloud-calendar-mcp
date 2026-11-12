package org.onekash.icaldav.recurrence

import net.fortuna.ical4j.model.NumberList
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.WeekDay
import net.fortuna.ical4j.model.WeekDayList
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import net.fortuna.ical4j.transform.recurrence.Frequency as ICalFrequency

/**
 * Expands recurring events into individual occurrences.
 *
 * Uses ical4j's Recur class for RFC 5545 compliant expansion,
 * implementing the full recurrence formula:
 * RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
 *
 * Additional handling for:
 * - EXDATE exclusions (deleted occurrences)
 * - RDATE additions (extra occurrence dates)
 * - RECURRENCE-ID overrides (modified occurrences)
 * - Timezone-aware date matching
 */
class RRuleExpander {

    /**
     * Thrown when expanding a single series would generate more than [MAX_ITERATIONS]
     * instances. Carries the offending series [uid] and the [limit] that was hit.
     *
     * The `ics` bridge (IcsParser) catches this and rethrows its own bridge-level type
     * so this core exception, and ical4j itself, stay confined to :icaldav-core.
     */
    class ExpansionLimitException(val uid: String, val limit: Int) : RuntimeException(
        "Recurrence expansion for series '$uid' exceeded the limit of $limit occurrences"
    )

    /**
     * Expand a recurring event into individual occurrences within a time range.
     *
     * Implements RFC 5545 recurrence expansion:
     * RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
     *
     * @param masterEvent The event with RRULE and/or RDATE
     * @param rangeStart Start of expansion range (inclusive); an all-day series counts from the start of its day
     * @param rangeEnd End of expansion range (exclusive)
     * @param overrides The series' RECURRENCE-ID override events, matched to occurrences by instant
     * @return List of occurrence events with adjusted timestamps
     */
    fun expand(
        masterEvent: ICalEvent,
        rangeStart: Instant,
        rangeEnd: Instant,
        overrides: List<ICalEvent> = emptyList()
    ): List<ICalEvent> {
        val rrule = masterEvent.rrule

        // If no RRULE and no RDATE, return single event
        if (rrule == null && masterEvent.rdates.isEmpty()) {
            return listOf(masterEvent)
        }

        val eventZone = eventZoneOf(masterEvent)
        val eventDuration = calculateDuration(masterEvent)

        // Index overrides by the INSTANT of their RECURRENCE-ID, normalized to the master's value
        // type/zone. RECURRENCE-ID identifies the original occurrence's instant (RFC 5545
        // 3.8.4.4), not a calendar day, so two overrides on one day stay distinct.
        val overridesByInstant: List<Pair<Long, ICalEvent>> = overrides.mapNotNull { ovr ->
            ovr.recurrenceId?.let { recId -> normalizeToMasterValueType(recId, masterEvent.dtStart).timestamp to ovr }
        }
        val usedOverrides = HashSet<ICalEvent>()

        // Find an as-yet-unused override whose normalized RECURRENCE-ID instant
        // matches [occurrenceInstantMs] within tolerance.
        fun matchOverride(occurrenceInstantMs: Long): ICalEvent? {
            return overridesByInstant
                .firstOrNull { (instantMs, ovr) ->
                    ovr !in usedOverrides &&
                        kotlin.math.abs(instantMs - occurrenceInstantMs) <= OVERRIDE_MATCH_TOLERANCE_MS
                }
                ?.second
                ?.also { usedOverrides.add(it) }
        }

        // The EXDATE day fallback (excludedInstants) counts the instances on a day, so when it can
        // apply (a one-per-day series with a DATE-TIME EXDATE) generate over whole days in the
        // event's zone; a day the requested range cuts still shows all its instances. That adds at
        // most one instance at each end. The result is cut back to the requested range below.
        val multiPerDay = isMultiPerDay(masterEvent)
        val dayFallback = masterEvent.exdates.any { !it.isDate } && !multiPerDay
        val rangeStartDay = rangeStart.atZone(eventZone).toLocalDate().atStartOfDay(eventZone).toInstant()
        val genStart = if (!dayFallback) rangeStart else minOf(rangeStart, rangeStartDay)
        val genEnd = if (!dayFallback) rangeEnd else
            maxOf(rangeEnd, rangeEnd.atZone(eventZone).toLocalDate().plusDays(1).atStartOfDay(eventZone).toInstant())
        // All-day RRULE instances count from the start of the range start's day (UTC midnight);
        // every other instance from the range start's instant. The end is exclusive for all.
        val allDayFloorMs = rangeStartDay.toEpochMilli()

        // ========== Candidate instances: RRULE, then RDATE ==========
        val candidates = mutableListOf<Candidate>()
        if (rrule != null) {
            // Build ical4j Recur from our RRule
            val recur = buildRecur(rrule, eventZone, masterEvent.isAllDay)

            // Get start date for recurrence calculation
            val eventStartZdt = masterEvent.dtStart.toZonedDateTime()

            val periodStart = ZonedDateTime.ofInstant(genStart, eventZone)
            val periodEnd = ZonedDateTime.ofInstant(genEnd, eventZone)

            // Always use LocalDateTime - for all-day events, use midnight
            val seed = if (masterEvent.isAllDay) {
                eventStartZdt.toLocalDate().atStartOfDay()
            } else {
                eventStartZdt.toLocalDateTime()
            }

            val rangeStartLdt = if (masterEvent.isAllDay) {
                periodStart.toLocalDate().atStartOfDay()
            } else {
                periodStart.toLocalDateTime()
            }

            val rangeEndLdt = if (masterEvent.isAllDay) {
                periodEnd.toLocalDate().atStartOfDay()
            } else {
                periodEnd.toLocalDateTime()
            }

            // Bound expansion to MAX_ITERATIONS generated instances per series (US2,
            // CWE-400). Two parts:
            //  1. Fast-forward the seed toward the window ourselves (fastForwardSeed):
            //     ical4j's own fast-forward (RecurDateSpliterator, still in 4.4.0) doubles an int
            //     multiplier and overflows to 0 for a far-seed FREQ=SECONDLY, spinning
            //     forever. Advancing the seed to the window sidesteps that loop.
            //  2. Cap in-window generation with maxCount = MAX_ITERATIONS + 1; if the
            //     count-limited result overflows the limit, abort rather than return a
            //     truncated (silently wrong) list.
            val effectiveSeed = fastForwardSeed(rrule, seed, rangeStartLdt)
            val dates: List<LocalDateTime> =
                recur.getDates(effectiveSeed, rangeStartLdt, rangeEndLdt, MAX_ITERATIONS + 1)
            if (dates.size > MAX_ITERATIONS) {
                throw ExpansionLimitException(masterEvent.uid, MAX_ITERATIONS)
            }
            for (date in dates) {
                val zdt = date.atZone(eventZone)
                candidates.add(Candidate(zdt.toInstant().toEpochMilli(), zdt.toLocalDate(), rrule = zdt, rdate = null))
            }
        }

        // RDATEs add instances; an RDATE at an instant RRULE already generated counts once
        // (RFC 5545 3.8.5.3). The dedupe is by instant, so an RDATE at another time of an
        // occupied day adds its own instance.
        val generatedInstants = candidates.mapTo(HashSet()) { it.instantMs }
        for (rdate in masterEvent.rdates) {
            if (rdate.timestamp < genStart.toEpochMilli() || rdate.timestamp >= genEnd.toEpochMilli()) continue
            if (!generatedInstants.add(rdate.timestamp)) continue
            candidates.add(Candidate(rdate.timestamp, dayOf(rdate, eventZone), rrule = null, rdate = rdate))
        }

        // ========== EXDATE ==========
        val excluded = excludedInstants(masterEvent, candidates, generatedInstants, multiPerDay, eventZone)

        // ========== Occurrences in [rangeStart, rangeEnd) ==========
        val occurrences = mutableListOf<ICalEvent>()
        for (candidate in candidates) {
            if (candidate.instantMs in excluded) continue
            val lowerMs = if (masterEvent.isAllDay && candidate.rrule != null) allDayFloorMs else rangeStart.toEpochMilli()
            if (candidate.instantMs < lowerMs || candidate.instantMs >= rangeEnd.toEpochMilli()) continue

            // If there's an override for this occurrence's instant, use it instead
            val override = matchOverride(candidate.instantMs)
            if (override != null) {
                occurrences.add(override)
                continue
            }

            val occurrenceStart = candidate.rrule?.let { ICalDateTime.fromZonedDateTime(it, masterEvent.isAllDay) }
                ?: candidate.rdate!!
            val occurrenceEnd = eventDuration?.let { dur ->
                ICalDateTime.fromTimestamp(
                    occurrenceStart.timestamp + dur.toMillis(),
                    occurrenceStart.timezone,
                    masterEvent.isAllDay
                )
            }
            val dayCode = candidate.rdate?.toDayCode()
                ?: "%04d%02d%02d".format(candidate.day.year, candidate.day.monthValue, candidate.day.dayOfMonth)
            occurrences.add(
                masterEvent.copy(
                    importId = "${masterEvent.uid}:OCC:$dayCode",
                    dtStart = occurrenceStart,
                    dtEnd = occurrenceEnd,
                    rrule = null,
                    exdates = emptyList(),
                    rdates = emptyList(),
                    recurrenceId = null
                )
            )
        }

        return occurrences.sortedBy { it.dtStart.timestamp }
    }

    /**
     * One generated instance before EXDATE: its instant, its calendar [day] in the event's zone,
     * and where it came from ([rrule] for an RRULE instance, [rdate] for an RDATE).
     */
    private class Candidate(
        val instantMs: Long,
        val day: LocalDate,
        val rrule: ZonedDateTime?,
        val rdate: ICalDateTime?
    )

    /**
     * The candidate instants EXDATE removes (RFC 5545 3.8.5.1), with hybrid matching:
     * - a DATE-TIME EXDATE removes the instance at its exact instant;
     * - if it matches no instance, a series with one occurrence per day ([isMultiPerDay] false)
     *   removes the instance on the EXDATE's calendar day in [eventZone], but only when that day
     *   holds exactly one instance; a multi-per-day series removes nothing;
     * - a DATE EXDATE removes every instance on its date.
     */
    private fun excludedInstants(
        master: ICalEvent,
        candidates: List<Candidate>,
        instants: Set<Long>,
        multiPerDay: Boolean,
        eventZone: ZoneId
    ): Set<Long> {
        if (master.exdates.isEmpty()) return emptySet()
        val byDay = candidates.groupBy { it.day }
        val excluded = HashSet<Long>()
        for (exdate in master.exdates) {
            if (exdate.isDate) {
                byDay[exdate.toLocalDate()]?.forEach { excluded.add(it.instantMs) }
                continue
            }
            if (exdate.timestamp in instants) {
                excluded.add(exdate.timestamp)
                continue
            }
            if (multiPerDay) continue
            // An all-day series reads a DATE-TIME EXDATE's date in its own zone, as an override's
            // RECURRENCE-ID is (normalizeToMasterValueType); a timed series in the event's zone.
            val day = if (master.isAllDay) exdate.toLocalDate() else dayOf(exdate, eventZone)
            val sameDay = byDay[day]
            if (sameDay != null && sameDay.size == 1) excluded.add(sameDay[0].instantMs)
        }
        return excluded
    }

    /**
     * Expand with TimeRange convenience class.
     */
    fun expand(
        masterEvent: ICalEvent,
        range: TimeRange,
        overrides: List<ICalEvent> = emptyList()
    ): List<ICalEvent> = expand(masterEvent, range.start, range.end, overrides)

    /**
     * Advance [seed] forward toward [rangeStart] by whole recurrence periods, so
     * ical4j does not have to. Its own fast-forward has an int-overflow infinite loop
     * for a far-seed FREQ=SECONDLY (a seed a large seconds-gap before the window);
     * landing the seed inside one period of the window sidesteps that loop.
     *
     * Only FIXED-LENGTH frequencies are advanced (SECONDLY/MINUTELY/HOURLY/DAILY/
     * WEEKLY). MONTHLY and YEARLY are left untouched on purpose: adding whole months
     * or years day-clamps (Jan 31 + 1 month -> Feb 28), which would change the emitted
     * day of a plain monthly/yearly rule; and they never trigger the overflow (their
     * far-seed step count is tiny, so ical4j expands them correctly on its own).
     *
     * A COUNT rule is also left untouched: skipping leading occurrences would change
     * which ones are the first COUNT. Returns [seed] unchanged whenever advancing is
     * either unsafe (MONTHLY/YEARLY, COUNT) or unnecessary (seed already at/after the
     * window). Because we advance by whole periods, [effectiveSeed] stays on the exact
     * same recurrence lattice as [seed], so no in-window occurrence is lost or shifted.
     */
    private fun fastForwardSeed(rrule: RRule, seed: LocalDateTime, rangeStart: LocalDateTime): LocalDateTime {
        if (rrule.count != null) return seed
        if (!seed.isBefore(rangeStart)) return seed
        val unit = fixedLengthUnit(rrule.freq) ?: return seed

        val interval = maxOf(rrule.interval, 1).toLong()
        // Whole periods between seed and the window start (floored). periodsToSkip *
        // interval <= periodsBetween, so the product cannot overflow a Long for any
        // representable LocalDateTime pair, and effectiveSeed lands at or before
        // rangeStart.
        val periodsBetween = unit.between(seed, rangeStart)
        val periodsToSkip = periodsBetween / interval
        if (periodsToSkip <= 0) return seed
        return seed.plus(periodsToSkip * interval, unit)
    }

    /**
     * The fixed-length [ChronoUnit] a frequency advances by, or null for MONTHLY and
     * YEARLY (variable-length units that must not be used to fast-forward a seed;
     * see [fastForwardSeed]).
     */
    private fun fixedLengthUnit(freq: Frequency): ChronoUnit? = when (freq) {
        Frequency.SECONDLY -> ChronoUnit.SECONDS
        Frequency.MINUTELY -> ChronoUnit.MINUTES
        Frequency.HOURLY -> ChronoUnit.HOURS
        Frequency.DAILY -> ChronoUnit.DAYS
        Frequency.WEEKLY -> ChronoUnit.WEEKS
        Frequency.MONTHLY, Frequency.YEARLY -> null
    }

    /**
     * Build ical4j Recur from our RRule model.
     * Uses ical4j 4.x API with generics and java.time.
     */
    private fun buildRecur(rrule: RRule, eventZone: ZoneId, isAllDay: Boolean): Recur<LocalDateTime> {
        val freq = ICalFrequency.valueOf(rrule.freq.name)
        val builder = Recur.Builder<LocalDateTime>()
            .frequency(freq)
            .interval(rrule.interval)

        rrule.count?.let { builder.count(it) }
        rrule.until?.let {
            // ical4j compares UNTIL with occurrences as wall clocks in eventZone. A UTC UNTIL (the Z
            // form, RFC 5545 3.3.10) is an instant, so on a timed series it is moved into eventZone
            // first; reading it as a UTC wall clock would end a TZID series at a different
            // occurrence (one too many west of UTC, one too few east of it). Floating and DATE
            // values, and every UNTIL on an all-day series, keep their own reading.
            val untilDate = if (!isAllDay && it.isUtc && !it.isDate) {
                it.toInstant().atZone(eventZone).toLocalDateTime()
            } else {
                it.toZonedDateTime().toLocalDateTime()
            }
            builder.until(untilDate)
        }

        // Sub-daily BY* parts (RFC 5545 §3.3.10). Emitted in
        // BYSECOND/BYMINUTE/BYHOUR order to match ical4j's internal field
        // layout in Recur (Recur.java:298-302).
        rrule.bySecond?.let { builder.secondList(it) }
        rrule.byMinute?.let { builder.minuteList(it) }
        rrule.byHour?.let { builder.hourList(it) }

        rrule.byDay?.let { days ->
            val weekDayList = WeekDayList()
            days.forEach { weekdayNum ->
                val javaDay = weekdayNum.dayOfWeek
                // ical4j 4.x: WeekDay constructor takes (WeekDay, Int), not (DayOfWeek, Int)
                val weekDay = if (weekdayNum.ordinal != null) {
                    WeekDay(WeekDay.getWeekDay(javaDay), weekdayNum.ordinal)
                } else {
                    WeekDay.getWeekDay(javaDay)
                }
                weekDayList.add(weekDay)
            }
            builder.dayList(weekDayList)
        }

        rrule.byMonthDay?.let { days ->
            builder.monthDayList(NumberList(days.joinToString(",")))
        }

        rrule.byMonth?.let { months ->
            // ical4j 4.x: monthList accepts List<Month>
            val monthList = months.map { net.fortuna.ical4j.model.Month.valueOf(it) }
            builder.monthList(monthList)
        }

        rrule.byWeekNo?.let { weeks ->
            builder.weekNoList(NumberList(weeks.joinToString(",")))
        }

        rrule.byYearDay?.let { days ->
            builder.yearDayList(NumberList(days.joinToString(",")))
        }

        rrule.bySetPos?.let { positions ->
            builder.setPosList(NumberList(positions.joinToString(",")))
        }

        // ical4j 4.x: weekStartDay expects WeekDay
        builder.weekStartDay(WeekDay.getWeekDay(rrule.wkst))

        return builder.build()
    }

    /**
     * Calculate event duration from dtStart and dtEnd or duration property.
     */
    private fun calculateDuration(event: ICalEvent): Duration? {
        return event.duration ?: event.dtEnd?.let { dtEnd ->
            Duration.ofMillis(dtEnd.timestamp - event.dtStart.timestamp)
        }
    }

    companion object {
        /**
         * Maximum number of instances a single series may generate during expansion.
         * A DoS guard (CWE-400): a pathologically frequent rule (FREQ=SECONDLY) or a
         * very wide window can otherwise force effectively unbounded work. On reaching
         * this bound the expander aborts with [ExpansionLimitException] rather than
         * returning a truncated (and silently wrong) list.
         */
        const val MAX_ITERATIONS = 10_000

        /**
         * Tolerance for matching an occurrence's instant against a RECURRENCE-ID's
         * instant. RECURRENCE-ID identifies the original occurrence's instant
         * (RFC 5545 §3.8.4.4); a small window absorbs sub-second rounding and the
         * odd off-by-a-minute a peer client emits, without ever spanning two
         * occurrences of a real-world recurrence.
         */
        private const val OVERRIDE_MATCH_TOLERANCE_MS = 60_000L

        /**
         * Reconcile a RECURRENCE-ID (or other recurrence date) against the value
         * type of the master's DTSTART, returning [value] unchanged when the
         * types already match.
         *
         * Peer clients sometimes emit a RECURRENCE-ID whose value type differs
         * from the master's DTSTART (a bare DATE against a timed master, or a
         * DATE-TIME against an all-day master), and most CalDAV servers preserve
         * it verbatim. Left unreconciled, the two describe different instants and
         * the override silently fails to match its occurrence.
         *
         * - Master timed, [value] date-form → promote the DATE to the master's
         *   time-of-day in the master's zone: exactly the instant the master's
         *   RRULE expansion produces for that calendar day.
         * - Master all-day, [value] date-time form → demote to a DATE, taking the
         *   calendar date in [value]'s own zone (UTC when floating/Z-form, which
         *   is how all-day DATE values are stored — so matching is independent of
         *   the machine's default timezone).
         */
        fun normalizeToMasterValueType(value: ICalDateTime, masterDtStart: ICalDateTime): ICalDateTime {
            if (value.isDate == masterDtStart.isDate) return value

            return if (masterDtStart.isDate) {
                // Master all-day, value date-time → demote to DATE.
                val zone = value.timezone ?: ZoneOffset.UTC
                val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(value.timestamp), zone).toLocalDate()
                ICalDateTime.fromLocalDate(date)
            } else {
                // Master timed, value date-form → promote to the master's
                // time-of-day in the master's zone.
                val masterZone = masterDtStart.timezone ?: ZoneOffset.UTC
                val masterLocalTime = ZonedDateTime
                    .ofInstant(Instant.ofEpochMilli(masterDtStart.timestamp), masterZone)
                    .toLocalTime()
                // DATE values are stored as UTC midnight, so read the calendar date in UTC.
                val valueDate = ZonedDateTime
                    .ofInstant(Instant.ofEpochMilli(value.timestamp), ZoneOffset.UTC)
                    .toLocalDate()
                val zoned = ZonedDateTime.of(valueDate, masterLocalTime, masterZone)
                ICalDateTime.fromZonedDateTime(zoned, isDate = false)
            }
        }

        /**
         * True when [event]'s series can put more than one occurrence on a calendar day: its
         * RRULE FREQ is HOURLY, MINUTELY or SECONDLY, or its BYHOUR, BYMINUTE and BYSECOND value
         * counts multiply to more than 1, or two of DTSTART and its RDATEs share a calendar day in
         * the event's zone. Decided from the series definition alone; a rule that BYSETPOS cuts to
         * one per day still counts as multi-per-day.
         */
        fun isMultiPerDay(event: ICalEvent): Boolean {
            event.rrule?.let { rule ->
                if (rule.freq == Frequency.HOURLY || rule.freq == Frequency.MINUTELY ||
                    rule.freq == Frequency.SECONDLY
                ) return true
                val perDay = (rule.byHour?.size ?: 1) * (rule.byMinute?.size ?: 1) * (rule.bySecond?.size ?: 1)
                if (perDay > 1) return true
            }
            val zone = eventZoneOf(event)
            val days = (listOf(event.dtStart) + event.rdates).map { dayOf(it, zone) }
            return days.size != days.toSet().size
        }

        /**
         * The zone a series is expanded in. It MUST match the zone `ICalDateTime.toZonedDateTime()`
         * resolves the master's DTSTART in, since the seed's local time is re-interpreted in this
         * zone to build each occurrence. All-day (DATE) and UTC (Z-form) series use UTC; reading the
         * nullable `timezone` field alone would fall back to the machine default and desync the
         * seed from the occurrences on a non-UTC host.
         */
        fun eventZoneOf(event: ICalEvent): ZoneId = when {
            event.isAllDay -> ZoneOffset.UTC
            event.dtStart.isUtc -> ZoneOffset.UTC
            else -> event.dtStart.timezone ?: ZoneId.systemDefault()
        }

        /** The calendar day of [value]: its literal date for a DATE, else its instant's date in [zone]. */
        internal fun dayOf(value: ICalDateTime, zone: ZoneId): LocalDate =
            if (value.isDate) value.toLocalDate() else value.toInstant().atZone(zone).toLocalDate()
    }
}

/**
 * Time range for expansion queries.
 */
data class TimeRange(
    val start: Instant,
    val end: Instant
) {
    companion object {
        /**
         * Create a range for a specific month.
         */
        fun forMonth(year: Int, month: Int, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val startOfMonth = LocalDate.of(year, month, 1).atStartOfDay(zone)
            val endOfMonth = startOfMonth.plusMonths(1)
            return TimeRange(startOfMonth.toInstant(), endOfMonth.toInstant())
        }

        /**
         * Create a range from now to N days in the future.
         */
        fun nextDays(days: Long, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val now = ZonedDateTime.now(zone)
            return TimeRange(
                now.toInstant(),
                now.plusDays(days).toInstant()
            )
        }

        /**
         * Create a range from N days ago to N days in the future.
         */
        fun aroundNow(daysBefore: Long, daysAfter: Long, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val now = ZonedDateTime.now(zone)
            return TimeRange(
                now.minusDays(daysBefore).toInstant(),
                now.plusDays(daysAfter).toInstant()
            )
        }

        /**
         * Create a 1-year range centered on now (typical sync window).
         */
        fun syncWindow(zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            return aroundNow(365, 365, zone)
        }
    }
}
