package org.onekash.icaldav.parser

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.testutil.PropertyRuns
import java.time.Duration
import kotlin.test.assertEquals

/**
 * RFC 5545 3.3.11 (TEXT): "An intentional formatted text line break MUST only be included in a
 * "TEXT" property value by representing the line break with the character sequence of BACKSLASH,
 * followed by a LATIN SMALL LETTER N or a LATIN CAPITAL LETTER N", and backslash, semicolon and
 * comma are escaped. So any text, once generated and parsed back, is the text it started as, with
 * CR and CRLF line breaks read as LF.
 */
class ICalTextRoundTripPropertiesTest {

    private val generator = ICalGenerator()
    private val parser = ICalParser()

    private val pieces = listOf("a", "Z", "N", "n", " ", "\\", ";", ",", ":", "\n", "\r", "\r\n", "é", "中", "\\N", "\\n")

    /**
     * Text that starts and ends with a letter, so no value is all whitespace or edge-padded. A
     * backslash directly before a line break is left out: ical4j mis-decodes that pattern (see
     * the pinned test below), which is a known limitation, not something this property covers.
     */
    private val textArb = arbitrary {
        val middle = StringBuilder()
        repeat(Arb.int(0..24).bind()) { middle.append(Arb.element(pieces).bind()) }
        ("a$middle" + "Z").replace("\\\n", "\\ \n").replace("\\\r", "\\ \r")
    }

    private fun normalized(text: String) = text.replace("\r\n", "\n").replace("\r", "\n")

    @Test
    fun `TEXT values in an event and its alarm round-trip through generate and parse`(): Unit = runBlocking {
        val cases = arbitrary { listOf(textArb.bind(), textArb.bind(), textArb.bind(), textArb.bind(), textArb.bind()) }
        checkAll(PropertyRuns.config(300), cases) { (summary, description, location, alarmText, alarmSubject) ->
            val event = ICalEvent(
                uid = "text-roundtrip@test", importId = "text-roundtrip@test",
                summary = summary, description = description, location = location,
                dtStart = ICalDateTime.parse("20260301T090000Z"), dtEnd = ICalDateTime.parse("20260301T100000Z"),
                duration = null, isAllDay = false, status = EventStatus.CONFIRMED, sequence = 0, rrule = null,
                exdates = emptyList(), recurrenceId = null,
                alarms = listOf(
                    ICalAlarm(
                        action = AlarmAction.EMAIL, trigger = Duration.ofMinutes(-15), triggerAbsolute = null,
                        description = alarmText, summary = alarmSubject
                    )
                ),
                categories = emptyList(), organizer = null, attendees = emptyList(), color = null, dtstamp = null,
                lastModified = null, created = null, transparency = Transparency.OPAQUE, url = null,
                rawProperties = emptyMap()
            )
            val ics = generator.generate(event)
            val parsed = (parser.parseAllEvents(ics) as ParseResult.Success).value.single()
            assertEquals(normalized(summary), parsed.summary, "SUMMARY\n$ics")
            assertEquals(normalized(description), parsed.description, "DESCRIPTION\n$ics")
            assertEquals(normalized(location), parsed.location, "LOCATION\n$ics")
            assertEquals(normalized(alarmText), parsed.alarms.single().description, "VALARM DESCRIPTION\n$ics")
            assertEquals(normalized(alarmSubject), parsed.alarms.single().summary, "VALARM SUMMARY\n$ics")
        }
    }

    // KNOWN LIMITATION (pinned): ical4j's TEXT unescape skips a newline escape that follows any
    // backslash, without checking whether that backslash is itself escaped. So the wire value
    // a\\\nZ (an escaped backslash, then an escaped newline) reads back as a, \, \, n, Z instead
    // of a, \, LF, Z. Generation is correct; only reading this pattern back is wrong.
    @Test
    fun `a backslash before a line break is mis-decoded by ical4j (known limitation)`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\nBEGIN:VEVENT\r\nUID:limit@test\r\n" +
            "DTSTART:20260301T090000Z\r\nSUMMARY:a\\\\\\nZ\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = (parser.parseAllEvents(ics) as ParseResult.Success).value.single()
        assertEquals("a\\\\nZ", parsed.summary)
    }
}
