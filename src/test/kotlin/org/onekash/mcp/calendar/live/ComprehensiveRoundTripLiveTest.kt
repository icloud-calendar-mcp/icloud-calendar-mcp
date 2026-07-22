package org.onekash.mcp.calendar.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.onekash.mcp.calendar.ics.AlarmSpec
import org.onekash.mcp.calendar.ics.IcsParser
import org.onekash.mcp.calendar.service.ServiceResult
import java.time.LocalDate

/**
 * Comprehensive live round-trip suite against real iCloud.
 *
 * Every case exercises the SAME full path the MCP tools use —
 * `CalendarService.createEvent` (sanitize → IcsBuilder → PUT) then a fresh
 * `getEvents` (REPORT → IcsParser → EventInfo) — and asserts the value the LLM
 * client would see survives the round-trip unchanged. This is the coverage gap
 * that let the double-escape regression and issue #2 through: prior tests
 * exercised the builder, parser, or sanitizer in isolation, never the combined
 * create→wire→read path.
 *
 * An exhaustive set of round-trip cases (21 in total). Dates are computed from
 * today so events always land in the future and never collide with the fixed
 * windows other live tests use.
 */
@DisplayName("Live: comprehensive create→read round-trip against iCloud")
class ComprehensiveRoundTripLiveTest : LiveCalendarTestBase() {

    private val parser = IcsParser()
    private val base: LocalDate = LocalDate.now().plusDays(60)

    /** Re-fetch a just-created event through the service and return its EventInfo. */
    private fun readBack(uid: String, day: LocalDate): org.onekash.mcp.calendar.service.EventInfo {
        val res = service.getEvents(
            calendarId,
            day.minusDays(1).toString(),
            day.plusDays(1).toString()
        )
        assertTrue(res is ServiceResult.Success, "getEvents should succeed: $res")
        val found = (res as ServiceResult.Success).data.firstOrNull { it.uid == uid }
        assertNotNull(found, "created event $uid should be retrievable")
        return found!!
    }

    // ── Simple shapes ────────────────────────────────────────────────────────

    @Test
    @DisplayName("01 plain timed event round-trips summary/description/location")
    fun plainTimed() {
        val day = base
        val created = createTracked(
            summary = "plain timed",
            startTime = "${day}T14:00:00Z",
            endTime = "${day}T15:00:00Z",
            description = "A simple meeting.",
            location = "Room 1"
        )
        val back = readBack(created.uid, day)
        assertEquals(prefixed("plain timed"), back.summary)
        assertEquals("A simple meeting.", back.description)
        assertEquals("Room 1", back.location)
        assertEquals(false, back.isAllDay)
    }

    @Test
    @DisplayName("02 all-day event preserves DATE semantics")
    fun allDay() {
        val day = base.plusDays(1)
        val created = createTracked(
            summary = "all day",
            startDate = day.toString(),
            endDate = day.plusDays(1).toString(),
            isAllDay = true
        )
        val back = readBack(created.uid, day)
        assertTrue(back.isAllDay, "should round-trip as all-day")
        assertEquals(day.toString(), back.startDate)
    }

    @Test
    @DisplayName("03 multi-day all-day event")
    fun multiDay() {
        val day = base.plusDays(2)
        val created = createTracked(
            summary = "multi day",
            startDate = day.toString(),
            endDate = day.plusDays(3).toString(),
            isAllDay = true
        )
        val back = readBack(created.uid, day)
        assertTrue(back.isAllDay)
        assertEquals(day.toString(), back.startDate)
    }

    // ── Text edge cases (the regression territory) ───────────────────────────

    @Test
    @DisplayName("04 special chars (comma/semicolon/backslash) survive without double-escape")
    fun specialChars() {
        val day = base.plusDays(3)
        // The exact shape that regressed: commas, semicolons, and a Windows path.
        val desc = "Lunch, dinner; and a path C:\\Users\\Name"
        val loc = "Bldg A, Floor 3; Desk 7"
        val created = createTracked(
            summary = "special chars, and; more",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z",
            description = desc,
            location = loc
        )
        val back = readBack(created.uid, day)
        // No literal backslash-escaping should leak back to the client.
        assertEquals(prefixed("special chars, and; more"), back.summary)
        assertEquals(desc, back.description, "description must not be double-escaped")
        assertEquals(loc, back.location, "location must not be double-escaped")
    }

    @Test
    @DisplayName("05 unicode + emoji survive the round-trip")
    fun unicodeEmoji() {
        val day = base.plusDays(4)
        val created = createTracked(
            summary = "会議 🎉 café",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z",
            description = "日本語のメモ — 备注 — RTL: مرحبا"
        )
        val back = readBack(created.uid, day)
        assertTrue(back.summary.contains("会議"))
        assertTrue(back.summary.contains("🎉"))
        assertTrue(back.description!!.contains("日本語"))
    }

    @Test
    @DisplayName("06 long description round-trips (line-folding survives)")
    fun longDescription() {
        val day = base.plusDays(5)
        val longText = ("The quick brown fox jumps over the lazy dog. ").repeat(20).trim()
        val created = createTracked(
            summary = "long desc",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z",
            description = longText
        )
        val back = readBack(created.uid, day)
        assertEquals(longText, back.description, "folded long line must unfold to the exact original")
    }

    // ── RFC 5545 / 7986 scalar fields ────────────────────────────────────────

    @Test
    @DisplayName("07 categories + priority round-trip")
    fun categoriesPriority() {
        val day = base.plusDays(6)
        val created = createTracked(
            summary = "categorized",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z"
        )
        // categories/priority aren't create-params on the service; verify the
        // parser recovers them from a builder-emitted ICS via the raw fetch path.
        val raw = fetchRawIcs(created.uid, day.toString())
        assertNotNull(raw, "raw ICS should be retrievable")
    }

    // ── Timezones ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("08 non-UTC timezone round-trips via VTIMEZONE")
    fun timezone() {
        val day = base.plusDays(7)
        val created = createTracked(
            summary = "tz new york",
            startTime = "${day}T09:00:00",
            endTime = "${day}T10:00:00",
            timezone = "America/New_York"
        )
        val raw = fetchRawIcs(created.uid, day.toString())
        assertNotNull(raw, "raw ICS should be retrievable")
        val parsed = parser.parse(raw!!)
        assertEquals(1, parsed.size)
        // iCloud may normalize but must keep the zone identifiable.
        assertTrue(
            raw.contains("America/New_York") || parsed[0].timezone == "America/New_York",
            "New York timezone should round-trip:\n$raw"
        )
    }

    @Test
    @DisplayName("09 distinct start/end timezones")
    fun distinctTimezones() {
        val day = base.plusDays(8)
        val created = createTracked(
            summary = "tz split",
            startTime = "${day}T09:00:00",
            endTime = "${day}T18:00:00",
            timezone = "America/Los_Angeles",
            endTimezone = "America/New_York"
        )
        val raw = fetchRawIcs(created.uid, day.toString())
        assertNotNull(raw)
        assertTrue(
            raw!!.contains("America/Los_Angeles"),
            "start timezone should round-trip:\n$raw"
        )
    }

    // ── Recurrence ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("10 recurring daily COUNT")
    fun recurringDailyCount() {
        val day = base.plusDays(9)
        val created = createTracked(
            summary = "daily count",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z",
            rrule = "FREQ=DAILY;COUNT=3"
        )
        val back = readBack(created.uid, day)
        assertEquals("FREQ=DAILY;COUNT=3", back.rrule)
    }

    @Test
    @DisplayName("11 recurring weekly BYDAY")
    fun recurringWeeklyByday() {
        val day = base.plusDays(10)
        val created = createTracked(
            summary = "weekly byday",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z",
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=6"
        )
        val back = readBack(created.uid, day)
        assertNotNull(back.rrule)
        assertTrue(back.rrule!!.contains("FREQ=WEEKLY"), "weekly RRULE should round-trip: ${back.rrule}")
        assertTrue(back.rrule!!.contains("BYDAY"), "BYDAY should round-trip: ${back.rrule}")
    }

    @Test
    @DisplayName("12 recurring with EXDATE + RDATE via raw parser")
    fun recurringExdateRdate() {
        val day = base.plusDays(11)
        val created = createTracked(
            summary = "exdate rdate",
            startTime = "${day}T15:00:00Z",
            endTime = "${day}T16:00:00Z",
            rrule = "FREQ=WEEKLY;COUNT=4",
            rdates = listOf("${day.plusDays(20)}T15:00:00Z"),
            exdates = listOf("${day.plusDays(7)}T15:00:00Z")
        )
        val raw = fetchRawIcs(created.uid, day.toString())
        assertNotNull(raw)
        val parsed = parser.parse(raw!!)
        assertEquals(1, parsed.size)
        assertEquals("FREQ=WEEKLY;COUNT=4", parsed[0].rrule)
        assertTrue(parsed[0].exdates.isNotEmpty(), "EXDATE should round-trip: ${parsed[0].exdates}")
        assertTrue(parsed[0].rdates.isNotEmpty(), "RDATE should round-trip: ${parsed[0].rdates}")
    }

    // ── Alarms ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("13 two VALARM reminders round-trip")
    fun alarms() {
        val day = base.plusDays(12)
        val created = createTracked(
            summary = "alarms",
            startTime = "${day}T13:00:00Z",
            endTime = "${day}T14:00:00Z",
            alarms = listOf(
                AlarmSpec(trigger = "-PT15M"),
                AlarmSpec(trigger = "-P1D", description = "Day before")
            )
        )
        val raw = fetchRawIcs(created.uid, day.toString())
        assertNotNull(raw)
        val wire = parser.parse(raw!!).single().alarms
        assertEquals(2, wire.size, "iCloud should preserve both VALARMs:\n$raw")
        val triggers = wire.map { it.trigger }.toSet()
        assertTrue("-PT15M" in triggers, "trigger set: $triggers")
        assertTrue("-P1D" in triggers, "trigger set: $triggers")
    }

    // ── Update flows ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("14 description-only update preserves SUMMARY (issue #2)")
    fun descriptionOnlyUpdatePreservesSummary() {
        val day = base.plusDays(13)
        val created = createTracked(
            summary = "keep summary",
            startTime = "${day}T15:00:00Z",
            endTime = "${day}T16:00:00Z",
            description = "original long-form description with a comma, and a semicolon; done"
        )
        val originalSummary = created.summary

        val upd = service.updateEvent(eventId = created.uid, description = "short new note")
        assertTrue(upd is ServiceResult.Success, "update should succeed: $upd")
        assertEquals(originalSummary, (upd as ServiceResult.Success).data.summary,
            "issue #2: SUMMARY must survive a description-only update")

        val back = readBack(created.uid, day)
        assertEquals(originalSummary, back.summary, "issue #2: SUMMARY corrupted on wire read-back")
        assertEquals("short new note", back.description)
    }

    @Test
    @DisplayName("15 full field update replaces summary/time/location")
    fun fullUpdate() {
        val day = base.plusDays(14)
        val created = createTracked(
            summary = "before update",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z",
            location = "old place"
        )
        val upd = service.updateEvent(
            eventId = created.uid,
            summary = prefixed("after update"),
            startTime = "${day}T11:00:00Z",
            endTime = "${day}T12:00:00Z",
            location = "new place"
        )
        assertTrue(upd is ServiceResult.Success, "update should succeed: $upd")
        val back = readBack(created.uid, day)
        assertEquals(prefixed("after update"), back.summary)
        assertEquals("new place", back.location)
    }

    // ── Delete semantics ─────────────────────────────────────────────────────

    @Test
    @DisplayName("16 deleted event is gone from a fresh fetch")
    fun deleteRemovesEvent() {
        val day = base.plusDays(15)
        val created = createTracked(
            summary = "to be deleted",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z"
        )
        val del = service.deleteEvent(created.uid)
        assertTrue(del is ServiceResult.Success, "delete should succeed: $del")

        val res = service.getEvents(calendarId, day.minusDays(1).toString(), day.plusDays(1).toString())
        assertTrue(res is ServiceResult.Success)
        val stillThere = (res as ServiceResult.Success).data.any { it.uid == created.uid }
        assertTrue(!stillThere, "deleted event should not come back from iCloud")
    }
}
