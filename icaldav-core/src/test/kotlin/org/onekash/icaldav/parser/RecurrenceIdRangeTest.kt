package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RecurrenceRange
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RECURRENCE-ID;RANGE round-trip preservation.
 *
 * RFC 5545 §3.2.13 lets a RECURRENCE-ID carry a RANGE parameter whose only
 * defined value, THISANDFUTURE, signals that the referenced override applies
 * to that occurrence *and all subsequent ones*. The parser previously read
 * only TZID/VALUE off the RECURRENCE-ID property and dropped RANGE on the
 * floor — a silent data-loss gap: an override that a peer client marked
 * THISANDFUTURE came back to the server as a single-instance override.
 *
 * This scope is preservation only: the parser lifts RANGE into an ICalEvent
 * field and the generator emits it back verbatim. Expansion semantics are
 * unchanged — a THISANDFUTURE override still resolves to its one instance in
 * the expander; the flag simply survives the parse→generate round-trip.
 */
@DisplayName("RECURRENCE-ID RANGE preservation")
class RecurrenceIdRangeTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator()

    private val thisAndFutureIcs = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:standup
        DTSTART;TZID=America/New_York:20231201T100000
        DTEND;TZID=America/New_York:20231201T103000
        RRULE:FREQ=DAILY;COUNT=5
        SUMMARY:Standup
        END:VEVENT
        BEGIN:VEVENT
        UID:standup
        RECURRENCE-ID;RANGE=THISANDFUTURE;TZID=America/New_York:20231203T100000
        DTSTART;TZID=America/New_York:20231203T110000
        DTEND;TZID=America/New_York:20231203T113000
        SUMMARY:Standup (moved to 11am, from here on)
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun override(ics: String): ICalEvent {
        val events = parser.parseAllEvents(ics).getOrThrow()
        return events.first { it.recurrenceId != null }
    }

    @Nested
    @DisplayName("Parse")
    inner class Parse {

        @Test
        fun `RANGE=THISANDFUTURE is lifted onto the override event`() {
            assertEquals(RecurrenceRange.THISANDFUTURE, override(thisAndFutureIcs).recurrenceIdRange)
        }

        @Test
        fun `absent RANGE parses to null, not a synthesized default`() {
            val ics = thisAndFutureIcs.replace(";RANGE=THISANDFUTURE", "")
            assertNull(override(ics).recurrenceIdRange)
        }

        @Test
        fun `RANGE is case-insensitive per RFC 5545 parameter rules`() {
            val ics = thisAndFutureIcs.replace("RANGE=THISANDFUTURE", "RANGE=thisandfuture")
            assertEquals(RecurrenceRange.THISANDFUTURE, override(ics).recurrenceIdRange)
        }

        @Test
        fun `a plain single-instance override has no range`() {
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:standup
                RECURRENCE-ID;TZID=America/New_York:20231203T100000
                DTSTART;TZID=America/New_York:20231203T110000
                SUMMARY:Standup (just this one)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()
            assertNull(override(ics).recurrenceIdRange)
        }
    }

    @Nested
    @DisplayName("Generate")
    inner class Generate {

        @Test
        fun `RANGE=THISANDFUTURE is emitted on the RECURRENCE-ID line`() {
            val generated = generator.generate(override(thisAndFutureIcs), method = null)
            val recidLine = generated.lines().first { it.startsWith("RECURRENCE-ID") }
            assertTrue(recidLine.contains("RANGE=THISANDFUTURE"), "line was: $recidLine")
        }

        @Test
        fun `a null range emits no RANGE parameter`() {
            val plain = override(thisAndFutureIcs).copy(recurrenceIdRange = null)
            val generated = generator.generate(plain, method = null)
            val recidLine = generated.lines().first { it.startsWith("RECURRENCE-ID") }
            assertFalse(recidLine.contains("RANGE"), "line was: $recidLine")
        }
    }

    @Nested
    @DisplayName("Round-trip")
    inner class RoundTrip {

        @Test
        fun `THISANDFUTURE survives parse to generate to parse`() {
            val once = override(thisAndFutureIcs)
            val regenerated = generator.generate(once, method = null)
            val twice = override(regenerated)
            assertEquals(RecurrenceRange.THISANDFUTURE, twice.recurrenceIdRange)
            // The instant the RANGE anchors to must be preserved too.
            assertEquals(once.recurrenceId, twice.recurrenceId)
        }
    }
}
