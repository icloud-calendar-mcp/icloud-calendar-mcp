package org.onekash.mcp.calendar.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.onekash.mcp.calendar.service.EventInfo
import org.onekash.mcp.calendar.service.ServiceResult
import org.onekash.mcp.calendar.validation.EventScope
import java.time.LocalDate

/**
 * Live round-trip for the stateless scoped occurrence edits/deletes (roadmap item A)
 * against real iCloud.
 *
 * Each test creates its OWN recurring series, acts on it through the same service path
 * the MCP tools use, and asserts the intended occurrences (and only those) changed. The
 * base-class janitor deletes every tracked UID afterwards; a this-and-future edit splits
 * off a new series, so its fresh UID is tracked too. Nothing an event was not created by
 * this test is ever touched.
 *
 * Occurrences are matched by the date part of their start, never by list order (a moved
 * instance re-sorts). Dates are pushed 90 days out so they never collide with the fixed
 * windows other live suites use.
 */
@DisplayName("Live: scoped single-occurrence edits/deletes round-trip against iCloud")
class ScopedOccurrenceLiveTest : LiveCalendarTestBase() {

    private val base: LocalDate = LocalDate.now().plusDays(90)

    /** Occurrences of [uid] the service reports across the inclusive [from, to] day window. */
    private fun occurrences(uid: String, from: LocalDate, to: LocalDate): List<EventInfo> {
        val res = service.getEvents(calendarId, from.toString(), to.toString())
        assertTrue(res is ServiceResult.Success, "getEvents should succeed: $res")
        return (res as ServiceResult.Success).data.filter { it.uid == uid }
    }

    /** The UTC hour ("09", "14", …) of an occurrence's start. */
    private fun EventInfo.startHour(): String = startTime!!.substringAfter('T').take(2)

    /** The date ("2026-01-15") of an occurrence's start. */
    private fun EventInfo.startDay(): String = startTime!!.substringBefore('T')

    @Test
    @DisplayName("edit this_occurrence moves one instance; siblings keep their time")
    fun editThisOccurrence() {
        val start = base
        val series = createTracked(
            summary = "scoped edit series",
            startTime = "${start}T09:00:00Z",
            endTime = "${start}T09:30:00Z",
            rrule = "FREQ=DAILY;COUNT=4"
        )
        val before = occurrences(series.uid, start.minusDays(1), start.plusDays(5))
        assertEquals(4, before.size, "series expands to four occurrences")

        // Move the second occurrence (start + 1 day) to 14:00; leave the rest alone.
        val target = before.first { it.startDay() == start.plusDays(1).toString() }
        val moved = service.updateEvent(
            target.handle!!,
            startTime = target.startTime!!.replace("T09:00", "T14:00"),
            endTime = target.endTime!!.replace("T09:30", "T14:30"),
            scope = EventScope.THIS_OCCURRENCE
        )
        assertTrue(moved is ServiceResult.Success, "occurrence edit succeeds: $moved")

        val after = occurrences(series.uid, start.minusDays(1), start.plusDays(5))
        assertEquals(4, after.size, "still four occurrences after editing one")
        val editedDay = start.plusDays(1).toString()
        assertEquals("14", after.first { it.startDay() == editedDay }.startHour(), "edited instance moved to 14:00")
        assertTrue(
            after.filter { it.startDay() != editedDay }.all { it.startHour() == "09" },
            "siblings keep their 09:00 time: $after"
        )
    }

    @Test
    @DisplayName("delete this_occurrence removes one date; siblings remain")
    fun deleteThisOccurrence() {
        val start = base.plusDays(10)
        val series = createTracked(
            summary = "scoped delete series",
            startTime = "${start}T09:00:00Z",
            endTime = "${start}T09:30:00Z",
            rrule = "FREQ=DAILY;COUNT=4"
        )
        val before = occurrences(series.uid, start.minusDays(1), start.plusDays(5))
        assertEquals(4, before.size)

        val victimDay = start.plusDays(2).toString()
        val victim = before.first { it.startDay() == victimDay }
        val deleted = service.deleteEvent(victim.handle!!, scope = EventScope.THIS_OCCURRENCE)
        assertTrue(deleted is ServiceResult.Success, "occurrence delete succeeds: $deleted")

        val after = occurrences(series.uid, start.minusDays(1), start.plusDays(5))
        assertEquals(3, after.size, "one occurrence gone, three remain")
        assertFalse(after.any { it.startDay() == victimDay }, "the deleted date is absent: $after")
    }

    @Test
    @DisplayName("this_and_future edit truncates the master and splits off a new series")
    fun editThisAndFuture() {
        val start = base.plusDays(20)
        val series = createTracked(
            summary = "scoped split series",
            startTime = "${start}T09:00:00Z",
            endTime = "${start}T09:30:00Z",
            rrule = "FREQ=DAILY;COUNT=4"
        )
        val before = occurrences(series.uid, start.minusDays(1), start.plusDays(5))
        assertEquals(4, before.size)

        // Split at the third occurrence (start + 2): earlier two stay on the master,
        // the third and later become a fresh series with a new summary.
        val splitDay = start.plusDays(2).toString()
        val pivot = before.first { it.startDay() == splitDay }
        val split = service.updateEvent(
            pivot.handle!!,
            summary = prefixed("scoped split series (renamed onward)"),
            scope = EventScope.THIS_AND_FUTURE
        )
        assertTrue(split is ServiceResult.Success, "this_and_future edit succeeds: $split")
        val newSeries = (split as ServiceResult.Success).data
        assertNotNull(newSeries.uid)
        // Track the split-off series so the janitor cleans it up too (it has a fresh UID).
        track(newSeries.uid)
        assertFalse(newSeries.uid == series.uid, "the split created a new series UID")

        val masterAfter = occurrences(series.uid, start.minusDays(1), start.plusDays(5))
        assertEquals(2, masterAfter.size, "master keeps only the first two occurrences: $masterAfter")
        assertTrue(masterAfter.none { it.startDay() >= splitDay }, "master stops before the split: $masterAfter")

        val newAfter = occurrences(newSeries.uid, start.minusDays(1), start.plusDays(5))
        assertEquals(2, newAfter.size, "new series carries the split occurrence and later: $newAfter")
        assertTrue(newAfter.all { it.summary.contains("renamed onward") }, "new series carries the patch: $newAfter")
        assertTrue(newAfter.all { it.startDay() >= splitDay }, "new series starts at the split: $newAfter")
    }
}
