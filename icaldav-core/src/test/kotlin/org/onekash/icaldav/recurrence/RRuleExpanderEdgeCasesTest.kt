package org.onekash.icaldav.recurrence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.testutil.IcsText
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Cases a mutation audit found no test for: a seed years before the range, an all-day RDATE
 * series cut by a midday range start, and a DATE RECURRENCE-ID on a zoned evening series.
 */
class RRuleExpanderEdgeCasesTest {

    private val expander = RRuleExpander()
    private val newYork = ZoneId.of("America/New_York")

    private fun events(vararg vevents: String): List<ICalEvent> {
        val result = ICalParser().parseAllEvents(IcsText.calendar(*vevents))
        return (result as ParseResult.Success).value
    }

    private fun vevent(vararg lines: String) =
        (listOf("BEGIN:VEVENT", "DTSTAMP:20200101T000000Z") + lines + "END:VEVENT").joinToString("\r\n")

    private fun days(occurrences: List<ICalEvent>, zone: ZoneId = ZoneId.of("UTC")) =
        occurrences.map { it.dtStart.toInstant().atZone(zone).toLocalDate().toString().replace("-", "") }

    private fun march(fromDay: Int, toDay: Int) =
        Instant.parse("2026-03-%02dT00:00:00Z".format(fromDay)) to Instant.parse("2026-03-%02dT00:00:00Z".format(toDay))

    @Test
    fun `a DAILY series with an INTERVAL starting years before the range keeps its lattice`() {
        val expected = mapOf(
            2 to listOf("20260302", "20260304", "20260306", "20260308", "20260310", "20260312", "20260314"),
            3 to listOf("20260303", "20260306", "20260309", "20260312"),
            4 to listOf("20260302", "20260306", "20260310", "20260314"),
        )
        for ((interval, dates) in expected) {
            val master = events(vevent("UID:d$interval@test", "DTSTART:20200101T090000Z", "DTEND:20200101T100000Z",
                "RRULE:FREQ=DAILY;INTERVAL=$interval")).single()
            val (start, end) = march(1, 15)
            assertEquals(dates, days(expander.expand(master, start, end)), "INTERVAL=$interval")
        }
    }

    @Test
    fun `a WEEKLY series with an INTERVAL starting years before the range keeps its lattice`() {
        val expected = mapOf(
            2 to listOf("20260310", "20260324", "20260407", "20260421"),
            3 to listOf("20260303", "20260324", "20260414"),
        )
        for ((interval, dates) in expected) {
            val master = events(vevent("UID:w$interval@test", "DTSTART:20200107T090000Z", "DTEND:20200107T100000Z",
                "RRULE:FREQ=WEEKLY;INTERVAL=$interval")).single()
            val occurrences = expander.expand(master, Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-05-01T00:00:00Z"))
            assertEquals(dates, days(occurrences), "INTERVAL=$interval")
        }
    }

    @Test
    fun `an all-day RDATE series with a DATE-TIME EXDATE starts at the range start`() {
        val master = events(vevent("UID:allday-rdate@test", "DTSTART;VALUE=DATE:20260301", "DTEND;VALUE=DATE:20260302",
            "RDATE;VALUE=DATE:20260305,20260310", "EXDATE:20260320T120000Z")).single()
        // The day fallback widens generation to the whole first day; an RDATE instance on that day
        // but before the range start is still cut, as an RDATE counts from the range instant.
        val occurrences = expander.expand(master, Instant.parse("2026-03-05T12:00:00Z"), Instant.parse("2026-03-31T00:00:00Z"))
        assertEquals(listOf("20260310"), days(occurrences))
    }

    @Test
    fun `a DATE RECURRENCE-ID replaces the evening occurrence of that local day in a zoned series`() {
        // 21:00 in New York is 02:00 UTC the next day, so the DATE must take the master's local
        // time in the master's zone. 2026-03-10 is after the US switch to daylight time (03-08).
        for ((recDay, offset) in listOf("20260305" to "-05:00", "20260310" to "-04:00")) {
            val all = events(
                vevent("UID:evening@test", "DTSTART;TZID=America/New_York:20260302T210000",
                    "DTEND;TZID=America/New_York:20260302T220000", "RRULE:FREQ=DAILY;COUNT=12", "SUMMARY:Evening"),
                vevent("UID:evening@test", "RECURRENCE-ID;VALUE=DATE:$recDay",
                    "DTSTART;TZID=America/New_York:${recDay}T200000", "DTEND;TZID=America/New_York:${recDay}T203000",
                    "SUMMARY:Moved"),
            )
            val master = all.single { it.recurrenceId == null }
            val occurrences = expander.expand(master, Instant.parse("2026-03-01T00:00:00Z"),
                Instant.parse("2026-03-20T00:00:00Z"), all.filter { it.recurrenceId != null })

            assertEquals(12, occurrences.size, recDay)
            val moved = occurrences.single { it.summary == "Moved" }
            val localDay = recDay.substring(0, 4) + "-" + recDay.substring(4, 6) + "-" + recDay.substring(6)
            assertEquals(ZonedDateTime.parse("${localDay}T20:00:00$offset").toInstant(), moved.dtStart.toInstant())
            val sameDay = occurrences.filter { days(listOf(it), newYork).single() == recDay }
            assertEquals(listOf("Moved"), sameDay.map { it.summary }, "the override replaces the day's occurrence")
        }
    }
}
