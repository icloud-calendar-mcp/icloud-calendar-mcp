package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Unit tests for RDATE expansion in RRuleExpander.
 *
 * Tests verify the RFC 5545 recurrence formula:
 * RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
 */
@DisplayName("RRuleExpander RDATE Tests")
class RRuleExpanderRdateTest {

    private val parser = ICalParser()
    private val expander = RRuleExpander()
    private val zone = ZoneId.of("UTC")

    // Helper to create time range
    private fun rangeFor2026(): TimeRange {
        val start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant()
        val end = ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 0, zone).toInstant()
        return TimeRange(start, end)
    }

    @Nested
    @DisplayName("RDATE-only Expansion (no RRULE)")
    inner class RdateOnlyExpansion {

        @Test
        fun `expand event with RDATE only creates occurrences`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-only@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE only event
                RDATE:20260120T100000Z,20260125T100000Z,20260130T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // DTSTART is the first instance, then the 3 RDATEs (RFC 5545 3.8.5.2)
            assertEquals(4, occurrences.size)

            // Verify dates
            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertEquals("20260115", dayCodes.first())
            assertTrue(dayCodes.contains("20260120"))
            assertTrue(dayCodes.contains("20260125"))
            assertTrue(dayCodes.contains("20260130"))
        }

        @Test
        fun `RDATE-only event is recurring`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-recurring@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE only event
                RDATE:20260120T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            assertTrue(event.isRecurring())
            assertNull(event.rrule)
            assertEquals(1, event.rdates.size)
        }

        @Test
        fun `event without RRULE or RDATE returns single event`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:no-recurrence@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Single event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            assertEquals(1, occurrences.size)
            assertEquals(event.uid, occurrences[0].uid)
        }
    }

    @Nested
    @DisplayName("RDATE + RRULE Combination")
    inner class RdatePlusRrule {

        @Test
        fun `RDATE adds extra occurrences to RRULE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rrule-rdate@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Weekly + extra RDATE
                RRULE:FREQ=WEEKLY;COUNT=3
                RDATE:20260210T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // RRULE generates: Jan 15, Jan 22, Jan 29 (3 weekly)
            // RDATE adds: Feb 10
            // Total: 4 occurrences
            assertEquals(4, occurrences.size)

            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertTrue(dayCodes.contains("20260115")) // RRULE
            assertTrue(dayCodes.contains("20260122")) // RRULE
            assertTrue(dayCodes.contains("20260129")) // RRULE
            assertTrue(dayCodes.contains("20260210")) // RDATE
        }

        @Test
        fun `duplicate RDATE same as RRULE date is deduplicated`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rrule-rdate-dup@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Weekly with duplicate RDATE
                RRULE:FREQ=WEEKLY;COUNT=3
                RDATE:20260122T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // RRULE: Jan 15, Jan 22, Jan 29
            // RDATE: Jan 22 (duplicate - should be deduplicated)
            // Total: 3 (not 4)
            assertEquals(3, occurrences.size)

            // Verify no duplicates
            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertEquals(dayCodes.distinct().size, dayCodes.size)
        }
    }

    @Nested
    @DisplayName("EXDATE Excludes RDATE")
    inner class ExdateExcludesRdate {

        @Test
        fun `EXDATE excludes RDATE occurrence`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-exdate@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE with EXDATE
                RDATE:20260120T100000Z,20260125T100000Z,20260130T100000Z
                EXDATE:20260125T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // DTSTART plus 3 RDATEs minus 1 EXDATE = 3 occurrences
            assertEquals(3, occurrences.size)

            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertTrue(dayCodes.contains("20260120"))
            assertFalse(dayCodes.contains("20260125")) // Excluded
            assertTrue(dayCodes.contains("20260130"))
        }

        @Test
        fun `EXDATE excludes both RRULE and RDATE on same date`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rrule-rdate-exdate@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RRULE + RDATE with EXDATE
                RRULE:FREQ=WEEKLY;COUNT=3
                RDATE:20260210T100000Z
                EXDATE:20260122T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // RRULE: Jan 15, Jan 22 (excluded), Jan 29
            // RDATE: Feb 10
            // Total: 3
            assertEquals(3, occurrences.size)

            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertTrue(dayCodes.contains("20260115"))
            assertFalse(dayCodes.contains("20260122")) // EXDATE
            assertTrue(dayCodes.contains("20260129"))
            assertTrue(dayCodes.contains("20260210"))
        }
    }

    @Nested
    @DisplayName("Range Filtering")
    inner class RangeFiltering {

        @Test
        fun `RDATE outside range is excluded`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-range@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE outside range
                RDATE:20260120T100000Z,20270115T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // DTSTART (Jan 15, 2026) and Jan 20, 2026 are in range; Jan 15, 2027 is not
            assertEquals(listOf("20260115", "20260120"), occurrences.map { it.dtStart.toDayCode() })
        }

        @Test
        fun `RDATE before range start is excluded`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-before@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE before range
                RDATE:20251215T100000Z,20260120T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // DTSTART (Jan 15) and Jan 20 are in range; Dec 15, 2025 is not
            assertEquals(listOf("20260115", "20260120"), occurrences.map { it.dtStart.toDayCode() })
        }
    }

    @Nested
    @DisplayName("Occurrence Properties")
    inner class OccurrenceProperties {

        @Test
        fun `RDATE occurrence has correct importId`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-importid@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:RDATE importId test
                RDATE:20260120T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // DTSTART is the first instance; the RDATE is the second.
            assertEquals(2, occurrences.size)
            val rdateOccurrence = occurrences.single { it.dtStart.toDayCode() == "20260120" }
            assertTrue(rdateOccurrence.importId.contains("OCC:20260120"))
        }

        @Test
        fun `RDATE occurrence preserves master event properties`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-props@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Important Meeting
                DESCRIPTION:Test description
                LOCATION:Conference Room
                RDATE:20260120T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // DTSTART is the first instance; the RDATE is the second.
            assertEquals(2, occurrences.size)
            val rdateOccurrence = occurrences.single { it.dtStart.toDayCode() == "20260120" }
            assertEquals("Important Meeting", rdateOccurrence.summary)
            assertEquals("Test description", rdateOccurrence.description)
            assertEquals("Conference Room", rdateOccurrence.location)
        }

        @Test
        fun `RDATE occurrence has no RRULE or RDATE`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-clean@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Clean occurrence
                RRULE:FREQ=WEEKLY;COUNT=2
                RDATE:20260210T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // All occurrences should have no RRULE/RDATE
            occurrences.forEach { occ ->
                assertNull(occ.rrule)
                assertTrue(occ.rdates.isEmpty())
                assertTrue(occ.exdates.isEmpty())
            }
        }

        @Test
        fun `RDATE occurrence calculates correct end time from duration`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-duration@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T120000Z
                SUMMARY:2 hour event
                RDATE:20260120T140000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // DTSTART is the first instance; the RDATE is the second.
            assertEquals(2, occurrences.size)
            val occ = occurrences.single { it.dtStart.toDayCode() == "20260120" }

            // RDATE starts at 14:00, should end at 16:00 (2 hour duration)
            assertNotNull(occ.dtEnd)
            val duration = occ.dtEnd!!.timestamp - occ.dtStart.timestamp
            assertEquals(2 * 60 * 60 * 1000L, duration) // 2 hours in ms
        }
    }

    @Nested
    @DisplayName("Sorting")
    inner class Sorting {

        @Test
        fun `occurrences are sorted by timestamp`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:rdate-sort@test.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260115T100000Z
                DTEND:20260115T110000Z
                SUMMARY:Sort test
                RRULE:FREQ=MONTHLY;COUNT=2
                RDATE:20260110T100000Z,20260220T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ics)
            assertTrue(result is ParseResult.Success)
            val event = (result as ParseResult.Success).value.first()

            val occurrences = expander.expand(event, rangeFor2026())

            // Expected order: Jan 10 (RDATE), Jan 15 (RRULE), Feb 15 (RRULE), Feb 20 (RDATE)
            val dayCodes = occurrences.map { it.dtStart.toDayCode() }
            assertEquals(listOf("20260110", "20260115", "20260215", "20260220"), dayCodes)
        }
    }

    /**
     * Hybrid EXDATE matching. A DATE-TIME EXDATE first removes the occurrence at its exact
     * instant (RFC 5545 3.8.5.1). If it matches no instant, a series with one occurrence per day
     * falls back to the occurrence on the EXDATE's calendar day in the event's zone, but only
     * when that day holds exactly one instance; a series with several occurrences per day
     * removes nothing. A DATE EXDATE removes every occurrence on its date. RDATEs are deduped
     * against generated occurrences by instant (RFC 5545 3.8.5.3).
     */
    @Nested
    @DisplayName("Hybrid EXDATE matching and RDATE dedupe")
    inner class HybridMatching {

        private val newYork = ZoneId.of("America/New_York")

        private fun ics(dtstart: String, dtend: String, rrule: String?, extra: List<String>): String =
            (listOf(
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN", "BEGIN:VEVENT",
                "UID:hybrid@test.com", "DTSTAMP:20260101T000000Z", dtstart, dtend, "SUMMARY:Hybrid",
            ) + listOfNotNull(rrule?.let { "RRULE:$it" }) + extra + listOf("END:VEVENT", "END:VCALENDAR"))
                .joinToString("\r\n")

        private fun parse(text: String) = (parser.parseAllEvents(text) as ParseResult.Success).value.first()

        private fun expandIcs(text: String): List<Long> =
            expander.expand(parse(text), rangeFor2026()).map { it.dtStart.timestamp }

        private fun ny(day: Int, hour: Int): Long =
            ZonedDateTime.of(2026, 3, day, hour, 0, 0, 0, newYork).toInstant().toEpochMilli()

        /** DAILY 10:00 New York, Mar 1-3 2026: one occurrence per day. */
        private fun daily(vararg extra: String) = expandIcs(ics(
            "DTSTART;TZID=America/New_York:20260301T100000", "DTEND;TZID=America/New_York:20260301T110000",
            "FREQ=DAILY;COUNT=3", extra.toList()
        ))

        /** 09:00 and 17:00 New York, Mar 1-3 2026: two occurrences per day. */
        private fun twiceDaily(vararg extra: String) = expandIcs(ics(
            "DTSTART;TZID=America/New_York:20260301T090000", "DTEND;TZID=America/New_York:20260301T093000",
            "FREQ=DAILY;BYHOUR=9,17;COUNT=6", extra.toList()
        ))

        private val allTwiceDaily = listOf(ny(1, 9), ny(1, 17), ny(2, 9), ny(2, 17), ny(3, 9), ny(3, 17))

        @Test
        fun `twice-daily - an EXDATE at one occurrence's instant removes only that one`() {
            assertEquals(allTwiceDaily - ny(2, 9), twiceDaily("EXDATE;TZID=America/New_York:20260302T090000"))
        }

        @Test
        fun `twice-daily - a UTC EXDATE at an occurrence's instant removes only that one`() {
            // 2 Mar 2026 17:00 EST is 22:00Z.
            assertEquals(allTwiceDaily - ny(2, 17), twiceDaily("EXDATE:20260302T220000Z"))
        }

        @Test
        fun `twice-daily - an EXDATE matching no instant removes nothing`() {
            assertEquals(allTwiceDaily, twiceDaily("EXDATE;TZID=America/New_York:20260302T120000"))
        }

        @Test
        fun `twice-daily - a DATE EXDATE removes both occurrences on that date`() {
            assertEquals(allTwiceDaily - ny(2, 9) - ny(2, 17), twiceDaily("EXDATE;VALUE=DATE:20260302"))
        }

        @Test
        fun `daily - an EXDATE at another time of day removes that day's only occurrence`() {
            assertEquals(listOf(ny(1, 10), ny(3, 10)), daily("EXDATE;TZID=America/New_York:20260302T090000"))
        }

        @Test
        fun `daily - an EXDATE in UTC form at the occurrence's instant removes it`() {
            // 2 Mar 2026 10:00 EST is 15:00Z.
            assertEquals(listOf(ny(1, 10), ny(3, 10)), daily("EXDATE:20260302T150000Z"))
        }

        @Test
        fun `daily - a DATE EXDATE removes that day's occurrence`() {
            assertEquals(listOf(ny(1, 10), ny(3, 10)), daily("EXDATE;VALUE=DATE:20260302"))
        }

        @Test
        fun `daily - an RDATE on a day that already has an occurrence adds its own instance`() {
            assertEquals(
                listOf(ny(1, 10), ny(2, 10), ny(2, 15), ny(3, 10)),
                daily("RDATE;TZID=America/New_York:20260302T150000")
            )
        }

        @Test
        fun `daily - an exact EXDATE on the RRULE occurrence keeps the same-day RDATE`() {
            assertEquals(
                listOf(ny(1, 10), ny(2, 15), ny(3, 10)),
                daily("RDATE;TZID=America/New_York:20260302T150000", "EXDATE;TZID=America/New_York:20260302T100000")
            )
        }

        @Test
        fun `daily - an exact EXDATE on the RDATE keeps the same-day RRULE occurrence`() {
            assertEquals(
                listOf(ny(1, 10), ny(2, 10), ny(3, 10)),
                daily("RDATE;TZID=America/New_York:20260302T150000", "EXDATE;TZID=America/New_York:20260302T150000")
            )
        }

        @Test
        fun `daily - an off-instant EXDATE on a day holding two instances removes nothing`() {
            assertEquals(
                listOf(ny(1, 10), ny(2, 10), ny(2, 15), ny(3, 10)),
                daily("RDATE;TZID=America/New_York:20260302T150000", "EXDATE;TZID=America/New_York:20260302T120000")
            )
        }

        @Test
        fun `a DATE EXDATE on an all-day series removes that day`() {
            val utc = { d: Int -> ZonedDateTime.of(2026, 3, d, 0, 0, 0, 0, zone).toInstant().toEpochMilli() }
            assertEquals(
                listOf(utc(1), utc(3)),
                expandIcs(ics("DTSTART;VALUE=DATE:20260301", "DTEND;VALUE=DATE:20260302", "FREQ=DAILY;COUNT=3",
                    listOf("EXDATE;VALUE=DATE:20260302")))
            )
        }

        @Test
        fun `a UTC EXDATE on a Seoul morning series removes the occurrence on its Seoul day`() {
            // Daily 08:00 KST. 2026-03-04T23:05Z is 08:05 KST on Mar 5 (UTC date Mar 4).
            val seoul = ZoneId.of("Asia/Seoul")
            val kst = { d: Int -> ZonedDateTime.of(2026, 3, d, 8, 0, 0, 0, seoul).toInstant().toEpochMilli() }
            val out = expandIcs(ics(
                "DTSTART;TZID=Asia/Seoul:20260301T080000", "DTEND;TZID=Asia/Seoul:20260301T090000",
                "FREQ=DAILY;COUNT=7", listOf("EXDATE:20260304T230500Z")
            ))
            assertEquals((1..7).map(kst) - kst(5), out)
        }

        @Test
        fun `a UTC EXDATE equal to an occurrence's startTime removes exactly it in Seoul and Los Angeles`() {
            val seoul = ZoneId.of("Asia/Seoul")
            val kst = { d: Int -> ZonedDateTime.of(2026, 3, d, 8, 0, 0, 0, seoul).toInstant().toEpochMilli() }
            assertEquals(
                (1..7).map(kst) - kst(5),
                expandIcs(ics("DTSTART;TZID=Asia/Seoul:20260301T080000", "DTEND;TZID=Asia/Seoul:20260301T090000",
                    "FREQ=DAILY;COUNT=7", listOf("EXDATE:20260304T230000Z")))
            )
            val la = ZoneId.of("America/Los_Angeles")
            val pst = { d: Int -> ZonedDateTime.of(2026, 3, d, 20, 0, 0, 0, la).toInstant().toEpochMilli() }
            // Mar 3 20:00 PST is 2026-03-04T04:00Z.
            assertEquals(
                (1..7).map(pst) - pst(3),
                expandIcs(ics("DTSTART;TZID=America/Los_Angeles:20260301T200000",
                    "DTEND;TZID=America/Los_Angeles:20260301T210000", "FREQ=DAILY;COUNT=7",
                    listOf("EXDATE:20260304T040000Z")))
            )
        }

        @Test
        fun `a floating EXDATE on a TZID series is read in the series zone in every JVM zone`() {
            // 08:00 and 18:00 KST; a floating EXDATE at 08:00 on Mar 2 means 08:00 KST.
            val seoul = ZoneId.of("Asia/Seoul")
            val kst = { d: Int, h: Int -> ZonedDateTime.of(2026, 3, d, h, 0, 0, 0, seoul).toInstant().toEpochMilli() }
            val text = ics("DTSTART;TZID=Asia/Seoul:20260301T080000", "DTEND;TZID=Asia/Seoul:20260301T083000",
                "FREQ=DAILY;BYHOUR=8,18;COUNT=6", listOf("EXDATE:20260302T080000"))
            for (jvm in listOf("UTC", "Asia/Seoul", "America/Los_Angeles")) {
                val saved = TimeZone.getDefault()
                val out = try {
                    TimeZone.setDefault(TimeZone.getTimeZone(jvm))
                    expandIcs(text)
                } finally {
                    TimeZone.setDefault(saved)
                }
                assertEquals(
                    listOf(kst(1, 8), kst(1, 18), kst(2, 18), kst(3, 8), kst(3, 18)), out, "JVM zone $jvm"
                )
            }
        }

        @Test
        fun `an RDATE-only series keeps two RDATEs on the same day`() {
            // DTSTART is the first instance, then both same-day RDATEs.
            assertEquals(
                listOf(ny(1, 10), ny(2, 9), ny(2, 15)),
                expandIcs(ics("DTSTART;TZID=America/New_York:20260301T100000",
                    "DTEND;TZID=America/New_York:20260301T110000", null,
                    listOf("RDATE;TZID=America/New_York:20260302T090000,20260302T150000")))
            )
        }

        @Test
        fun `a multi-per-day series keeps its EXDATEs at their instants when its time moves (known limitation)`() {
            // FREQ=HOURLY;INTERVAL=12 from 09:00 gives 09:00 and 21:00; deleting Mar 2 09:00 and then
            // moving DTSTART to 10:00 leaves an EXDATE that no occurrence matches, so Mar 2 10:00 shows.
            val moved = expandIcs(ics(
                "DTSTART;TZID=America/New_York:20260301T100000", "DTEND;TZID=America/New_York:20260301T103000",
                "FREQ=HOURLY;INTERVAL=12;COUNT=6", listOf("EXDATE;TZID=America/New_York:20260302T090000")
            ))
            assertTrue(ny(2, 10) in moved, "the deleted occurrence reappears at the new time: $moved")
        }

        @Test
        fun `multi-per-day classification follows FREQ, BY-time counts and same-day RDATEs`() {
            fun classify(rrule: String?, vararg extra: String) = RRuleExpander.isMultiPerDay(parse(ics(
                "DTSTART;TZID=America/New_York:20260301T090000", "DTEND;TZID=America/New_York:20260301T100000",
                rrule, extra.toList()
            )))
            assertFalse(classify("FREQ=DAILY"))
            assertFalse(classify("FREQ=DAILY;BYHOUR=9"))
            assertTrue(classify("FREQ=DAILY;BYHOUR=9,17"))
            assertFalse(classify("FREQ=WEEKLY;BYDAY=MO,TU;BYHOUR=9"))
            assertTrue(classify("FREQ=DAILY;BYHOUR=9;BYMINUTE=0,30"))
            assertTrue(classify("FREQ=HOURLY"))
            assertTrue(classify("FREQ=MINUTELY;INTERVAL=30"))
            assertFalse(classify("FREQ=DAILY", "RDATE;TZID=America/New_York:20260305T150000"))
            assertTrue(classify("FREQ=DAILY", "RDATE;TZID=America/New_York:20260301T150000"), "RDATE on DTSTART's day")
            assertTrue(classify(null, "RDATE;TZID=America/New_York:20260305T080000,20260305T150000"))
            assertFalse(classify(null, "RDATE;TZID=America/New_York:20260305T080000,20260306T150000"))
        }
    }

    @Nested
    @DisplayName("Range end and same-day overrides")
    inner class RangeEndAndOverrides {

        @Test
        fun `an RRULE instance starting exactly at the range end is not returned`() {
            val event = (parser.parseAllEvents((listOf(
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN", "BEGIN:VEVENT",
                "UID:end@test.com", "DTSTAMP:20260101T000000Z", "DTSTART:20260301T100000Z",
                "DTEND:20260301T110000Z", "RRULE:FREQ=DAILY", "SUMMARY:End", "END:VEVENT", "END:VCALENDAR"
            )).joinToString("\r\n")) as ParseResult.Success).value.first()
            val out = expander.expand(event, Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-03T10:00:00Z"))
            assertEquals(
                listOf("2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z"),
                out.map { Instant.ofEpochMilli(it.dtStart.timestamp).toString() }
            )
        }

        @Test
        fun `two overrides on the same day of a twice-daily series both replace their occurrences`() {
            val text = (listOf(
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN",
                "BEGIN:VEVENT", "UID:twice@test.com", "DTSTAMP:20260101T000000Z", "DTSTART:20260301T090000Z",
                "DTEND:20260301T093000Z", "RRULE:FREQ=DAILY;BYHOUR=9,17;COUNT=6", "SUMMARY:Base", "END:VEVENT",
                "BEGIN:VEVENT", "UID:twice@test.com", "DTSTAMP:20260101T000000Z", "RECURRENCE-ID:20260302T090000Z",
                "DTSTART:20260302T100000Z", "DTEND:20260302T103000Z", "SUMMARY:Morning moved", "END:VEVENT",
                "BEGIN:VEVENT", "UID:twice@test.com", "DTSTAMP:20260101T000000Z", "RECURRENCE-ID:20260302T170000Z",
                "DTSTART:20260302T180000Z", "DTEND:20260302T183000Z", "SUMMARY:Evening moved", "END:VEVENT",
                "END:VCALENDAR"
            )).joinToString("\r\n")
            val events = (parser.parseAllEvents(text) as ParseResult.Success).value
            val master = events.first { it.recurrenceId == null }
            val out = expander.expand(master, rangeFor2026(), events.filter { it.recurrenceId != null })
            val mar2 = out.filter { Instant.ofEpochMilli(it.dtStart.timestamp).toString().startsWith("2026-03-02") }
            assertEquals(
                listOf("2026-03-02T10:00:00Z" to "Morning moved", "2026-03-02T18:00:00Z" to "Evening moved"),
                mar2.map { Instant.ofEpochMilli(it.dtStart.timestamp).toString() to it.summary }
            )
            assertEquals(6, out.size)
        }
    }

    @Nested
    @DisplayName("Review fixes")
    inner class ReviewFixes {

        private fun parse(lines: List<String>) = (parser.parseAllEvents((listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN", "BEGIN:VEVENT", "UID:review@test.com",
            "DTSTAMP:20260101T000000Z", "SUMMARY:Review") + lines + listOf("END:VEVENT", "END:VCALENDAR"))
            .joinToString("\r\n")) as ParseResult.Success).value.first()

        private fun days(events: List<org.onekash.icaldav.model.ICalEvent>) =
            events.map { Instant.ofEpochMilli(it.dtStart.timestamp).toString().take(10) }

        @Test
        fun `an all-day series reads a TZID EXDATE's date in its own zone`() {
            // Tokyo midnight on Mar 10 is Mar 9 15:00Z; the EXDATE names Mar 10.
            val event = parse(listOf("DTSTART;VALUE=DATE:20260301", "DTEND;VALUE=DATE:20260302",
                "RRULE:FREQ=DAILY;COUNT=15", "EXDATE;TZID=Asia/Tokyo:20260310T000000"))
            val out = days(expander.expand(event, rangeFor2026()))
            assertFalse("2026-03-10" in out, "Mar 10 is excluded: $out")
            assertTrue("2026-03-09" in out, "Mar 9 stays: $out")
        }

        @Test
        fun `a UTC series reads floating EXDATE and RDATE values as UTC in every JVM zone`() {
            val lines = listOf("DTSTART:20260301T000000Z", "DTEND:20260301T010000Z", "RRULE:FREQ=DAILY;COUNT=10",
                "EXDATE:20260305T000000", "RDATE:20260312T050000")
            val results = listOf("UTC", "Asia/Seoul", "America/Los_Angeles").associateWith { jvm ->
                val saved = TimeZone.getDefault()
                try {
                    TimeZone.setDefault(TimeZone.getTimeZone(jvm))
                    expander.expand(parse(lines), rangeFor2026()).map { Instant.ofEpochMilli(it.dtStart.timestamp).toString() }
                } finally {
                    TimeZone.setDefault(saved)
                }
            }
            val utc = results.getValue("UTC")
            assertFalse("2026-03-05T00:00:00Z" in utc, "Mar 5 excluded: $utc")
            assertTrue("2026-03-12T05:00:00Z" in utc, "RDATE at 05:00Z: $utc")
            results.forEach { (jvm, out) -> assertEquals(utc, out, "JVM zone $jvm") }
        }
    }

    /**
     * RFC 5545 3.8.5.1/3.8.5.2: "The "DTSTART" property defines the first instance in the
     * recurrence set." That holds for a series defined by RDATE alone.
     */
    @Nested
    @DisplayName("DTSTART is the first instance of an RDATE-only series")
    inner class RdateOnlyDtstart {

        private fun expandRdateOnly(vararg extra: String): List<String> {
            val ics = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN", "BEGIN:VEVENT",
                "UID:rdate-dtstart@test.com", "DTSTAMP:20260101T000000Z", "DTSTART:20260115T100000Z",
                "DTEND:20260115T110000Z", "SUMMARY:RDATE only") + extra + listOf("END:VEVENT", "END:VCALENDAR"))
                .joinToString("\r\n")
            val event = (parser.parseAllEvents(ics) as ParseResult.Success).value.first()
            return expander.expand(event, rangeFor2026()).map { Instant.ofEpochMilli(it.dtStart.timestamp).toString() }
        }

        @Test
        fun `DTSTART is returned before the RDATEs`() {
            assertEquals(
                listOf("2026-01-15T10:00:00Z", "2026-01-20T10:00:00Z", "2026-01-25T10:00:00Z"),
                expandRdateOnly("RDATE:20260120T100000Z,20260125T100000Z")
            )
        }

        @Test
        fun `an RDATE equal to DTSTART counts once`() {
            assertEquals(
                listOf("2026-01-15T10:00:00Z", "2026-01-20T10:00:00Z"),
                expandRdateOnly("RDATE:20260115T100000Z,20260120T100000Z")
            )
        }

        @Test
        fun `an EXDATE on DTSTART removes it`() {
            assertEquals(
                listOf("2026-01-20T10:00:00Z"),
                expandRdateOnly("RDATE:20260120T100000Z", "EXDATE:20260115T100000Z")
            )
        }
    }
}
