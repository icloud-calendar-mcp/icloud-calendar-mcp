package org.onekash.icaldav.recurrence

import org.onekash.icaldav.testutil.withDefaultZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.parser.ICalParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals

/**
 * UNTIL bounds a recurrence inclusively (RFC 5545 section 3.3.10). When DTSTART has a TZID,
 * UNTIL is a UTC instant, so the set of occurrences must be the same whatever timezone the JVM
 * runs in. A floating DTSTART has a floating UNTIL, read on the same wall clock as the
 * occurrences.
 */
@DisplayName("RRULE UNTIL across JVM timezones")
class RRuleExpanderUtcUntilTest {

    private val parser = ICalParser()
    private val expander = RRuleExpander()
    private val ny = ZoneId.of("America/New_York")

    /** Parse and expand with the JVM default timezone forced to [jvmZone], then restore. */
    private fun expandOn(jvmZone: String, vevent: String): List<Long> = withDefaultZone(jvmZone) {
            val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\n" +
                "BEGIN:VEVENT\nUID:until-test\nSUMMARY:Until\n$vevent\nEND:VEVENT\nEND:VCALENDAR\n"
            val master = parser.parseAllEvents(ics).getOrThrow().single()
            val from = master.dtStart.toInstant().minusSeconds(DAY_SECONDS)
            val to = from.plusSeconds(1200L * DAY_SECONDS)
            expander.expand(master, TimeRange(from, to)).map { it.dtStart.timestamp }
    }

    private fun ms(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int) =
        ZonedDateTime.of(y, mo, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun nyMs(y: Int, mo: Int, d: Int, h: Int) = ms(ny, y, mo, d, h)

    private val jvmZones = listOf("America/Los_Angeles", "UTC", "Europe/Berlin", "Asia/Seoul")

    @Nested
    @DisplayName("timed series with a UTC UNTIL")
    inner class TimedUtcUntil {

        // RFC 5545 section 3.8.5.3, "Every day in January, for 3 years".
        // UNTIL=20000131T140000Z is 09:00 EST, the last occurrence itself.
        private val everyDayInJanuary = """
            DTSTART;TZID=America/New_York:19980101T090000
            DTEND;TZID=America/New_York:19980101T100000
            RRULE:FREQ=DAILY;UNTIL=20000131T140000Z;BYMONTH=1
        """.trimIndent()

        @Test
        fun `RFC every day in January keeps Jan 31 2000 in every JVM zone`() {
            for (zone in jvmZones) {
                val result = expandOn(zone, everyDayInJanuary)
                assertEquals(93, result.size, "3 Januaries of 31 days with JVM zone $zone")
                assertEquals(nyMs(1998, 1, 1, 9), result.first(), zone)
                assertEquals(nyMs(2000, 1, 31, 9), result.last(), "Jan 31 2000 is the inclusive UNTIL ($zone)")
            }
        }

        // Tuesdays 10:00 New York from 2024-03-05; the US clock change on
        // 2024-03-10 moves the later occurrences from 15:00Z to 14:00Z.
        private fun tuesdays(until: String) = """
            DTSTART;TZID=America/New_York:20240305T100000
            DTEND;TZID=America/New_York:20240305T110000
            RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=$until
        """.trimIndent()

        private val throughMar12 = listOf(nyMs(2024, 3, 5, 10), nyMs(2024, 3, 12, 10))
        private val throughMar19 = throughMar12 + nyMs(2024, 3, 19, 10)

        @Test
        fun `UNTIL one second before an occurrence ends the series before it in every JVM zone`() {
            for (zone in jvmZones) {
                assertEquals(throughMar12, expandOn(zone, tuesdays("20240319T135959Z")), zone)
            }
        }

        @Test
        fun `UNTIL equal to an occurrence keeps it in every JVM zone`() {
            for (zone in jvmZones) {
                assertEquals(throughMar19, expandOn(zone, tuesdays("20240319T140000Z")), zone)
            }
        }

        @Test
        fun `a Los Angeles series ends at UNTIL one second before an occurrence`() {
            // Daily 09:00 PST is 17:00Z; the Mar 3 occurrence is 2026-03-03T17:00Z.
            val la = ZoneId.of("America/Los_Angeles")
            val vevent = """
                DTSTART;TZID=America/Los_Angeles:20260301T090000
                DTEND;TZID=America/Los_Angeles:20260301T100000
                RRULE:FREQ=DAILY;UNTIL=20260303T165959Z
            """.trimIndent()
            for (zone in jvmZones) {
                assertEquals(listOf(1, 2).map { ms(la, 2026, 3, it, 9) }, expandOn(zone, vevent), zone)
            }
        }

        @Test
        fun `an Asia Seoul series keeps the occurrence at UNTIL and drops it one second earlier`() {
            // Daily 09:00 KST is 00:00Z the same day; the Mar 3 occurrence is 2026-03-03T00:00Z.
            val seoul = ZoneId.of("Asia/Seoul")
            fun vevent(until: String) = """
                DTSTART;TZID=Asia/Seoul:20260301T090000
                DTEND;TZID=Asia/Seoul:20260301T100000
                RRULE:FREQ=DAILY;UNTIL=$until
            """.trimIndent()
            for (zone in jvmZones) {
                assertEquals(
                    listOf(1, 2, 3).map { ms(seoul, 2026, 3, it, 9) }, expandOn(zone, vevent("20260303T000000Z")),
                    "UNTIL equal to the Mar 3 occurrence keeps it ($zone)"
                )
                assertEquals(
                    listOf(1, 2).map { ms(seoul, 2026, 3, it, 9) }, expandOn(zone, vevent("20260302T235959Z")),
                    "UNTIL one second earlier drops it ($zone)"
                )
            }
        }
    }

    @Nested
    @DisplayName("floating series with a floating UNTIL")
    inner class FloatingUntil {

        private val floatingTuesdays = """
            DTSTART:20240305T100000
            DTEND:20240305T110000
            RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=20240319T100000
        """.trimIndent()

        @Test
        fun `a floating UNTIL is read on the JVM wall clock in any zone`() {
            for (zone in listOf("America/Los_Angeles", "Asia/Tokyo")) {
                val wallClocks = expandOn(zone, floatingTuesdays).map {
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.of(zone))
                }
                assertEquals(listOf(5, 12, 19).map { LocalDateTime.of(2024, 3, it, 10, 0) }, wallClocks, zone)
            }
        }
    }

    /**
     * A DATE UNTIL on a timed series reads as midnight at the start of that date, which ends
     * the series before that day's occurrence. Pinned so a change to it is deliberate.
     */
    @Nested
    @DisplayName("timed series with a DATE UNTIL")
    inner class TimedDateUntil {

        @Test
        fun `a DATE UNTIL on a Tokyo series ends before that day in any JVM zone`() {
            val tokyo = ZoneId.of("Asia/Tokyo")
            for (zone in listOf("UTC", "America/Los_Angeles")) {
                val result = expandOn(
                    zone,
                    """
                        DTSTART;TZID=Asia/Tokyo:20240305T080000
                        DTEND;TZID=Asia/Tokyo:20240305T090000
                        RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=20240319
                    """.trimIndent(),
                )
                assertEquals(listOf(5, 12).map { ms(tokyo, 2024, 3, it, 8) }, result, zone)
            }
        }
    }

    /** All-day series read UNTIL in UTC, the same as their DATE occurrences, in every JVM zone. */
    @Nested
    @DisplayName("all-day series")
    inner class AllDay {

        private fun allDayTuesdays(until: String) = """
            DTSTART;VALUE=DATE:20240305
            DTEND;VALUE=DATE:20240306
            RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=$until
        """.trimIndent()

        private fun utcMidnight(d: Int) = LocalDateTime.of(2024, 3, d, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

        @Test
        fun `a DATE UNTIL keeps its last day in every JVM zone`() {
            for (zone in jvmZones) {
                assertEquals(listOf(5, 12, 19).map(::utcMidnight), expandOn(zone, allDayTuesdays("20240319")), zone)
            }
        }

        @Test
        fun `a UTC DATE-TIME UNTIL is read in UTC in every JVM zone`() {
            // 2024-03-18T23:00Z is before the 19th's UTC-midnight occurrence wherever the JVM runs.
            for (zone in jvmZones) {
                assertEquals(listOf(5, 12).map(::utcMidnight), expandOn(zone, allDayTuesdays("20240318T230000Z")), zone)
            }
        }
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
    }
}
