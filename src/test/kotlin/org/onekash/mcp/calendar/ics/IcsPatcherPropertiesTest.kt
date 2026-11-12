package org.onekash.mcp.calendar.ics

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.onekash.icaldav.testutil.IcsText
import org.onekash.icaldav.testutil.PropertyRuns
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Property tests for [IcsPatcher] over generated series resources. */
class IcsPatcherPropertiesTest {

    private val patcher = IcsPatcher()
    private val parser = IcsParser()

    private val zones: List<ZoneId> = listOf(
        ZoneOffset.UTC, ZoneId.of("America/New_York"), ZoneId.of("Europe/Berlin"), ZoneId.of("Asia/Tokyo"),
        ZoneId.of("Australia/Sydney"), ZoneId.of("Asia/Kolkata"), ZoneId.of("America/Los_Angeles"),
    )

    private val localFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** A DATE-TIME property line for [t]: UTC form in UTC, TZID form elsewhere. */
    private fun dateTime(name: String, t: ZonedDateTime): String =
        if (t.zone == ZoneOffset.UTC) "$name:${utcFormat.format(t)}" else "$name;TZID=${t.zone.id}:${localFormat.format(t)}"

    /** One parsed content line: name, parameters (upper-cased names), value. */
    private data class Line(val name: String, val params: Map<String, String>, val value: String)

    private fun parseLine(raw: String): Line {
        val colon = raw.indexOf(':')
        val head = raw.substring(0, colon).split(';')
        val params = head.drop(1).associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
        return Line(head[0].uppercase(), params, raw.substring(colon + 1))
    }

    /** Each VEVENT of [ics], unfolded, as its parsed top-level lines. */
    private fun vevents(ics: String): List<List<Line>> =
        IcsText.componentBlocks(ics).map { block -> block.filter { it.isNotBlank() }.map(::parseLine) }

    private fun List<Line>.prop(name: String): Line? = firstOrNull { it.name == name }

    /** A DATE-TIME line as (TZID or null, value), so parameter order cannot matter. */
    private fun Line.tzAndValue(): Pair<String?, String> = params["TZID"] to value

    private data class Series(
        val start: ZonedDateTime,
        val rrule: String,
        val overrideIndexes: List<Int>,
        val step: Long,
        val order: Long
    )

    private val seriesArb = arbitrary {
        val zone = Arb.element(zones).bind()
        val date = LocalDate.of(Arb.int(2020..2035).bind(), Arb.int(1..12).bind(), Arb.int(1..28).bind())
        val start = ZonedDateTime.of(date, LocalTime.of(Arb.int(6..22).bind(), Arb.element(0, 15, 30, 45).bind()), zone)
        val weekly = Arb.int(0..1).bind() == 1
        val step = if (weekly) 7L else 1L
        val count = Arb.int(5..12).bind()
        val rrule = if (Arb.int(0..1).bind() == 0) {
            "FREQ=${if (weekly) "WEEKLY" else "DAILY"};COUNT=$count"
        } else {
            val last = start.plusDays(step * (count - 1)).withZoneSameInstant(ZoneOffset.UTC)
            "FREQ=${if (weekly) "WEEKLY" else "DAILY"};UNTIL=${utcFormat.format(last)}"
        }
        val overrides = (1 until count).shuffled(Random(Arb.long().bind())).take(Arb.int(0..4).bind()).sorted()
        Series(start, rrule, overrides, step, Arb.long().bind())
    }

    private fun Series.ics(): String {
        val master = listOf(
            "BEGIN:VEVENT", "UID:prop-series@test", "DTSTAMP:20260101T000000Z",
            dateTime("DTSTART", start), dateTime("DTEND", start.plusHours(1)),
            "RRULE:$rrule", "SUMMARY:Series", "END:VEVENT"
        ).joinToString("\n")
        val overrides = overrideIndexes.map { k ->
            val occurrence = start.plusDays(step * k)
            listOf(
                "BEGIN:VEVENT", "UID:prop-series@test", "DTSTAMP:20260101T000000Z",
                dateTime("RECURRENCE-ID", occurrence),
                dateTime("DTSTART", occurrence.plusMinutes(30)), dateTime("DTEND", occurrence.plusMinutes(90)),
                "SUMMARY:Override $k", "END:VEVENT"
            ).joinToString("\n")
        }
        val vevents = (listOf(master) + overrides).shuffled(Random(order))
        return "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\n" + vevents.joinToString("\n") + "\nEND:VCALENDAR"
    }

    @Test
    fun `a whole-series summary patch keeps the RRULE and every override in any VEVENT order`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(150), seriesArb) { series ->
            val label = "${series.rrule} from ${series.start}, overrides ${series.overrideIndexes}"
            val patched = patcher.patch(existingIcs = series.ics(), uid = "prop-series@test", summary = "Renamed")
            val events = vevents(patched)
            assertEquals(1 + series.overrideIndexes.size, events.size, "$label\n$patched")

            val master = events.first()
            assertNull(master.prop("RECURRENCE-ID"), "$label: master written first\n$patched")
            assertEquals("Renamed", master.prop("SUMMARY")?.value, label)
            assertEquals(
                series.rrule.split(';').toSet(), master.prop("RRULE")?.value?.split(';')?.toSet(),
                "$label: RRULE kept"
            )

            val byRecid = events.drop(1).associateBy { it.prop("RECURRENCE-ID")!!.tzAndValue() }
            for (k in series.overrideIndexes) {
                val occurrence = series.start.plusDays(series.step * k)
                val recid = parseLine(dateTime("RECURRENCE-ID", occurrence)).tzAndValue()
                val override = byRecid[recid] ?: error("$label: override $k missing\n$patched")
                assertEquals("Override $k", override.prop("SUMMARY")?.value, label)
                assertEquals(
                    parseLine(dateTime("DTSTART", occurrence.plusMinutes(30))).tzAndValue(),
                    override.prop("DTSTART")!!.tzAndValue(), "$label: override $k DTSTART"
                )
                assertEquals("prop-series@test", override.prop("UID")?.value, label)
                assertNull(override.prop("RRULE"), label)
            }
        }
    }

    private data class SplitMove(val start: ZonedDateTime, val weekly: Boolean, val count: Int, val splitAt: Int, val shiftMinutes: Long)

    private val splitMoveArb = arbitrary {
        val zone = Arb.element(zones).bind()
        val date = LocalDate.of(Arb.int(2020..2035).bind(), Arb.int(1..12).bind(), Arb.int(1..28).bind())
        val start = ZonedDateTime.of(date, LocalTime.of(Arb.int(6..22).bind(), Arb.element(0, 15, 30, 45).bind()), zone)
        val count = Arb.int(4..10).bind()
        // Keep the moved local start within 05:00..23:30 the same day (clear of DST gaps), and
        // smaller than half the daily interval.
        val minutesOfDay = start.toLocalTime().toSecondOfDay() / 60L
        val low = maxOf(-(12 * 60L - 1), 5 * 60L - minutesOfDay)
        val high = minOf(12 * 60L - 1, 23 * 60L + 30 - minutesOfDay)
        val shift = Arb.long(low..high).bind().takeIf { it != 0L } ?: 1L
        SplitMove(start, Arb.int(0..1).bind() == 1, count, Arb.int(1 until count).bind(), shift)
    }

    private fun startArg(t: ZonedDateTime): Pair<String, String?> =
        if (t.zone == ZoneOffset.UTC) t.toInstant().toString() to null
        else t.toLocalDateTime().toString().let { if (it.length == 16) "$it:00" else it } to t.zone.id

    @Test
    fun `a split series moved by less than half its interval keeps its last occurrence`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(150), splitMoveArb) { c ->
            val step = if (c.weekly) 7L else 1L
            val occurrence = { k: Int -> c.start.plusDays(step * k) }
            val until = occurrence(c.count - 1).withZoneSameInstant(ZoneOffset.UTC)
            val ics = listOf(
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN", "BEGIN:VEVENT",
                "UID:prop-split@test", "DTSTAMP:20260101T000000Z",
                dateTime("DTSTART", c.start), dateTime("DTEND", c.start.plusHours(1)),
                "RRULE:FREQ=${if (c.weekly) "WEEKLY" else "DAILY"};UNTIL=${utcFormat.format(until)}",
                "SUMMARY:Series", "END:VEVENT", "END:VCALENDAR"
            ).joinToString("\n")
            val recid = parseLine(dateTime("RECURRENCE-ID", occurrence(c.splitAt))).value
            val split = patcher.splitSeries(ics, recurrenceId = recid, summary = "Continuing")

            val moved = c.start.plusMinutes(c.shiftMinutes)
            val (startArg, tz) = startArg(moved)
            val (endArg, _) = startArg(moved.plusHours(1))
            val patched = patcher.patch(split.truncatedMaster, "prop-split@test", startTime = startArg, endTime = endArg, timezone = tz)

            val from = c.start.minusDays(2).toInstant()
            val to = occurrence(c.count + 2).toInstant()
            val truncated = parser.parseOccurrences(patched, from, to).map { java.time.Instant.parse(it.startTime!!) }
            val continuingFirst = parser.parseOccurrences(split.newSeries, from, to)
                .map { java.time.Instant.parse(it.startTime!!) }.minOrNull()
            val label = "$c\n$patched"
            assertEquals(c.splitAt, truncated.size, "occurrence count: $label")
            assertEquals(
                occurrence(c.splitAt - 1).toLocalDateTime().plusMinutes(c.shiftMinutes).atZone(c.start.zone).toInstant(),
                truncated.last(), "last occurrence: $label"
            )
            assertTrue(continuingFirst == null || truncated.none { it == continuingFirst }, "split-day duplicate: $label")
        }
    }

    // RFC 5545 3.2.20: "Applications MUST preserve the value data for x-name and iana-token values
    // that they don't recognize without attempting to interpret or parse the value data."
    private val paramValueArb = arbitrary {
        if (Arb.int(0..1).bind() == 0) {
            Arb.element("A", "b1", "URI", "70", "x-y").bind()
        } else {
            val inside = StringBuilder()
            repeat(Arb.int(1..12).bind()) { inside.append(Arb.element("a", "Z", " ", ";", ":", ",", "\\n", "é").bind()) }
            "\"$inside\""
        }
    }

    private val valueArb = arbitrary {
        val v = StringBuilder()
        repeat(Arb.int(1..90).bind()) { v.append(Arb.element("a", "Q", "9", " ", ":", "\\,", "\\;", "\\n", "\\\\", "/", "é", "中").bind()) }
        v.toString()
    }

    private val unknownLinesArb = arbitrary {
        val lines = mutableListOf<String>()
        repeat(Arb.int(1..4).bind()) { n ->
            val name = "X-PROP-" + Arb.element("ALPHA", "BETA", "APPLE-STRUCTURED-LOCATION", "GAMMA").bind()
            val params = StringBuilder()
            repeat(Arb.int(0..3).bind()) { k -> params.append(";X-P$k=").append(paramValueArb.bind()) }
            repeat(Arb.int(1..3).bind()) { lines.add("$name$params:${valueArb.bind()}$n") }
        }
        lines to Arb.long().bind()
    }

    /** Folds [line] at random points of at most 74 characters, as RFC 5545 3.1 allows. */
    private fun fold(line: String, random: Random): String {
        val out = StringBuilder()
        var rest = line
        while (rest.length > 74) {
            val cut = random.nextInt(20, 75)
            out.append(rest, 0, cut).append("\r\n ")
            rest = rest.substring(cut)
        }
        return out.append(rest).toString()
    }

    @Test
    fun `unknown properties survive parse, generate and a summary patch byte for byte and in order`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(150), unknownLinesArb) { (lines, seed) ->
            val random = Random(seed)
            val ics = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN", "BEGIN:VEVENT",
                "UID:unknown-prop@test", "DTSTAMP:20260101T000000Z", "DTSTART:20260301T090000Z",
                "DTEND:20260301T100000Z", "SUMMARY:Original") + lines.map { fold(it, random) } +
                listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n") + "\r\n"
            fun unknownIn(text: String) = IcsText.unfoldedLines(text).filter { it.startsWith("X-PROP-") }

            val parsed = (org.onekash.icaldav.parser.ICalParser().parseAllEvents(ics)
                as org.onekash.icaldav.model.ParseResult.Success).value.single()
            assertEquals(lines, parsed.unknownPropertyLines, "parsed lines\n$ics")
            val regenerated = org.onekash.icaldav.parser.ICalGenerator().generate(parsed)
            assertEquals(lines, unknownIn(regenerated), "generated lines\n$regenerated")
            assertTrue(regenerated.replace("\r\n", "\n").split("\n").all { it.toByteArray().size <= 75 },
                "every output line is folded to 75 octets\n$regenerated")

            val patched = patcher.patch(existingIcs = ics, uid = "unknown-prop@test", summary = "Renamed")
            assertEquals(lines, unknownIn(patched), "patched lines\n$patched")
        }
    }
}
