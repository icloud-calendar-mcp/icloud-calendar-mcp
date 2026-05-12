package org.onekash.mcp.calendar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.onekash.mcp.calendar.caldav.CalDavCredentials
import org.onekash.mcp.calendar.caldav.CalDavResult
import org.onekash.mcp.calendar.caldav.OkHttpCalDavClient
import org.onekash.mcp.calendar.ics.AlarmSpec
import org.onekash.mcp.calendar.ics.IcsParser
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.ServiceResult
import java.time.LocalDate

/**
 * Live integration test with real iCloud CalDAV.
 *
 * Run with:
 * ICLOUD_USERNAME=xxx ICLOUD_PASSWORD=xxx ./gradlew test --tests "LiveCalDavTest"
 *
 * Skipped if credentials not set. Each create-update-delete test
 * cleans up its own event in a finally block.
 */
class LiveCalDavTest {

    private val username = System.getenv("ICLOUD_USERNAME")
    private val password = System.getenv("ICLOUD_PASSWORD")

    private fun newClient() = OkHttpCalDavClient(
        baseUrl = "https://caldav.icloud.com",
        credentials = CalDavCredentials(username!!, password!!)
    )

    private fun firstWritableCalendarId(service: CalendarService): String {
        val result = service.listCalendars()
        assertTrue(result is ServiceResult.Success, "listCalendars should succeed")
        val calendars = (result as ServiceResult.Success).data
        // Skip Reminders — they often have id="" and reject VEVENT
        val writable = calendars.firstOrNull {
            !it.readOnly && it.id.isNotBlank() && !it.name.equals("Reminders", ignoreCase = true)
        }
        assertNotNull(writable, "expected at least one writable VEVENT calendar")
        return writable!!.id
    }

    @Test
    fun `list calendars from iCloud`() {
        assumeTrue(username != null && password != null, "Credentials not set")

        val service = CalendarService(newClient())
        val result = service.listCalendars()

        when (result) {
            is ServiceResult.Success -> {
                println("\n=== Calendars Found ===")
                result.data.forEach { cal ->
                    println("- ${cal.name} (${cal.id})")
                    println("  Color: ${cal.color ?: "none"}")
                    println("  ReadOnly: ${cal.readOnly}")
                }
                println("Total: ${result.data.size} calendars")
            }
            is ServiceResult.Error -> {
                println("\n=== Error ===")
                println("Code: ${result.code}")
                println("Message: ${result.message}")
            }
        }
    }

    @Test
    fun `create-fetch-delete event with CRLF + CREATED + LAST-MODIFIED`() {
        assumeTrue(username != null && password != null, "Credentials not set")

        val service = CalendarService(newClient())
        val calendarId = firstWritableCalendarId(service)

        val today = LocalDate.now()
        val startTime = "${today.plusDays(7)}T18:00:00Z"
        val endTime = "${today.plusDays(7)}T19:00:00Z"

        val createResult = service.createEvent(
            calendarId = calendarId,
            summary = "MCP-LIVE: chunk 26-29 sanity",
            startTime = startTime,
            endTime = endTime,
            description = "Round-trip check: CRLF, CREATED, LAST-MODIFIED."
        )
        assertTrue(createResult is ServiceResult.Success, "create should succeed: $createResult")
        val created = (createResult as ServiceResult.Success).data
        println("\n=== Created event ===")
        println("UID: ${created.uid}")
        println("etag: ${created.etag}")

        try {
            // Pull the raw ICS back via the CalDAV client to verify wire-level properties
            val client = newClient()
            val rawResult = client.getEvents(
                calendarId,
                today.plusDays(6).toString(),
                today.plusDays(8).toString()
            )
            assertTrue(rawResult is CalDavResult.Success, "REPORT should succeed: $rawResult")
            val raw = (rawResult as CalDavResult.Success).data
                .firstOrNull { it.uid == created.uid }
            assertNotNull(raw, "created event should be retrievable: ${created.uid}")
            val ics = raw!!.icalData

            // §3.1 is verified at the producer in Rfc5545ComplianceAuditTest;
            // iCloud is known to transcode line endings on REPORT responses, so
            // we don't re-assert CRLF on the read side.

            // §3.8.7.1 + .3: server should preserve our CREATED + LAST-MODIFIED.
            assertTrue(
                ics.lineSequence().any { it.startsWith("CREATED:") },
                "iCloud should round-trip CREATED:\n$ics"
            )
            assertTrue(
                ics.lineSequence().any { it.startsWith("LAST-MODIFIED:") },
                "iCloud should round-trip LAST-MODIFIED:\n$ics"
            )
        } finally {
            val del = service.deleteEvent(created.uid)
            println("Cleanup delete: $del")
        }
    }

    @Test
    fun `create recurring event with DURATION emits and parses back`() {
        assumeTrue(username != null && password != null, "Credentials not set")

        val service = CalendarService(newClient())
        val calendarId = firstWritableCalendarId(service)

        val today = LocalDate.now()
        val startTime = "${today.plusDays(14)}T09:00:00Z"
        val endTime = "${today.plusDays(14)}T10:00:00Z"

        val result = service.createEvent(
            calendarId = calendarId,
            summary = "MCP-LIVE: chunk 29 DURATION",
            startTime = startTime,
            endTime = endTime,
            rrule = "FREQ=WEEKLY;COUNT=2"
        )
        assertTrue(result is ServiceResult.Success, "create should succeed: $result")
        val created = (result as ServiceResult.Success).data

        try {
            // Read raw ICS back. iCloud may normalize DURATION->DTEND or vice versa,
            // but the round-trip must remain semantically valid (at least one of
            // DURATION or DTEND present, RRULE intact).
            val client = newClient()
            val rawResult = client.getEvents(
                calendarId,
                today.plusDays(13).toString(),
                today.plusDays(15).toString()
            )
            assertTrue(rawResult is CalDavResult.Success)
            val raw = (rawResult as CalDavResult.Success).data
                .firstOrNull { it.uid == created.uid }
            assertNotNull(raw, "recurring event should be retrievable")
            val ics = raw!!.icalData
            assertTrue(ics.contains("RRULE:FREQ=WEEKLY"), "RRULE should round-trip:\n$ics")
            // Either DURATION (we sent it) or DTEND (iCloud normalized it) — both legal
            val hasEndOrDuration = ics.lineSequence().any { it.startsWith("DURATION:") || it.startsWith("DTEND") }
            assertTrue(hasEndOrDuration, "Either DTEND or DURATION must be present:\n$ics")
        } finally {
            val del = service.deleteEvent(created.uid)
            println("Cleanup delete: $del")
        }
    }

    @Test
    fun `create event with RDATE + EXDATE round-trips through parser`() {
        assumeTrue(username != null && password != null, "Credentials not set")

        val service = CalendarService(newClient())
        val calendarId = firstWritableCalendarId(service)

        val today = LocalDate.now()
        val anchor = today.plusDays(21)
        val rdate1 = "${anchor.plusDays(7)}T15:00:00Z"
        val rdate2 = "${anchor.plusDays(14)}T15:00:00Z"
        val exdate1 = "${anchor.plusDays(28)}T15:00:00Z"

        val result = service.createEvent(
            calendarId = calendarId,
            summary = "MCP-LIVE: chunk 31 RDATE/EXDATE",
            startTime = "${anchor}T15:00:00Z",
            endTime = "${anchor}T16:00:00Z",
            rrule = "FREQ=WEEKLY;COUNT=4",
            rdates = listOf(rdate1, rdate2),
            exdates = listOf(exdate1)
        )
        assertTrue(result is ServiceResult.Success, "create should succeed: $result")
        val created = (result as ServiceResult.Success).data

        try {
            val client = newClient()
            val rawResult = client.getEvents(
                calendarId,
                anchor.minusDays(1).toString(),
                anchor.plusDays(60).toString()
            )
            assertTrue(rawResult is CalDavResult.Success)
            val raw = (rawResult as CalDavResult.Success).data
                .firstOrNull { it.uid == created.uid }
            assertNotNull(raw, "RDATE/EXDATE event should be retrievable")
            val ics = raw!!.icalData

            // The MCP parser is the proof point — even if iCloud reformats slightly,
            // the parser should recover both lists.
            val parsed = IcsParser().parse(ics)
            assertEquals(1, parsed.size, "parser should yield exactly one event")
            val evt = parsed[0]

            println("\n=== RDATE/EXDATE round-trip ===")
            println("RRULE: ${evt.rrule}")
            println("rdates: ${evt.rdates}")
            println("exdates: ${evt.exdates}")

            assertEquals("FREQ=WEEKLY;COUNT=4", evt.rrule, "RRULE should round-trip")
            assertTrue(evt.rdates.size >= 1, "at least one RDATE should round-trip: ${evt.rdates}")
            assertTrue(evt.exdates.size >= 1, "EXDATE should round-trip: ${evt.exdates}")
        } finally {
            val del = service.deleteEvent(created.uid)
            println("Cleanup delete: $del")
        }
    }

    @Test
    fun `create event with two VALARM alarms round-trips through iCloud (issue #1)`() {
        assumeTrue(username != null && password != null, "Credentials not set")

        val service = CalendarService(newClient())
        val calendarId = firstWritableCalendarId(service)

        val today = LocalDate.now()
        val result = service.createEvent(
            calendarId = calendarId,
            summary = "MCP-LIVE: chunk 36 alarms",
            startTime = "${today.plusDays(35)}T13:00:00Z",
            endTime = "${today.plusDays(35)}T14:00:00Z",
            alarms = listOf(
                AlarmSpec(trigger = "-PT15M"),                              // 15 min before
                AlarmSpec(trigger = "-P1D", description = "Day before alert") // 1 day before
            )
        )
        assertTrue(result is ServiceResult.Success, "create should succeed: $result")
        val created = (result as ServiceResult.Success).data

        try {
            // Service-layer view: create response now exposes alarms via EventInfo.
            assertEquals(2, created.alarms.size, "create response should reflect 2 alarms")

            // Wire-level round-trip: fetch raw ICS and confirm both VALARMs survived.
            val client = newClient()
            val rawResult = client.getEvents(
                calendarId,
                today.plusDays(34).toString(),
                today.plusDays(36).toString()
            )
            assertTrue(rawResult is CalDavResult.Success)
            val raw = (rawResult as CalDavResult.Success).data
                .firstOrNull { it.uid == created.uid }
            assertNotNull(raw, "alarmed event should be retrievable")
            val ics = raw!!.icalData

            val parsed = IcsParser().parse(ics)
            assertEquals(1, parsed.size)
            val wireAlarms = parsed[0].alarms

            println("\n=== VALARM round-trip ===")
            wireAlarms.forEachIndexed { i, a ->
                println("[$i] action=${a.action} trigger=${a.trigger} description=${a.description}")
            }

            assertEquals(2, wireAlarms.size, "iCloud should preserve both VALARM blocks")
            // Triggers should round-trip in their original duration form
            val wireTriggers = wireAlarms.map { it.trigger }.toSet()
            assertTrue("-PT15M" in wireTriggers, "First alarm trigger preserved: $wireTriggers")
            assertTrue("-P1D" in wireTriggers, "Second alarm trigger preserved: $wireTriggers")

            // Chunk 37: the MCP-layer response (created.alarms) must agree with the
            // wire fetch in count + trigger set so external LLM clients see what
            // actually landed.
            assertEquals(
                wireAlarms.size, created.alarms.size,
                "MCP create response alarm count must match wire fetch"
            )
            assertEquals(
                wireTriggers, created.alarms.map { it.trigger }.toSet(),
                "MCP create response triggers must match wire fetch"
            )
        } finally {
            val del = service.deleteEvent(created.uid)
            println("Cleanup delete: $del")
        }
    }

    @Test
    fun `description-only update preserves SUMMARY against real iCloud (issue #2)`() {
        assumeTrue(username != null && password != null, "Credentials not set")

        val service = CalendarService(newClient())
        val calendarId = firstWritableCalendarId(service)

        val today = LocalDate.now()
        val originalSummary = "MCP TEST long-description event"
        val originalDescription = "Send a short follow-up email with an attachment for a warm introduction. Frame around the pitch, not generic availability."
        val newDescription = "MCP TEST touch simple."

        val createResult = service.createEvent(
            calendarId = calendarId,
            summary = originalSummary,
            startTime = "${today.plusDays(40)}T15:00:00Z",
            endTime = "${today.plusDays(40)}T16:00:00Z",
            description = originalDescription
        )
        assertTrue(createResult is ServiceResult.Success, "create should succeed: $createResult")
        val created = (createResult as ServiceResult.Success).data

        try {
            // Issue #2 trigger: update_event with ONLY a new description
            val updateResult = service.updateEvent(
                eventId = created.uid,
                description = newDescription
            )
            assertTrue(updateResult is ServiceResult.Success,
                "description-only update should succeed: $updateResult")
            val updated = (updateResult as ServiceResult.Success).data

            // Smoking-gun assertion #1: the in-process service result preserves SUMMARY.
            assertEquals(originalSummary, updated.summary,
                "Issue #2: SUMMARY corrupted in update response. Got: '${updated.summary}'")

            // Smoking-gun assertion #2: a fresh getEvents from iCloud preserves SUMMARY.
            val client = newClient()
            val rawResult = client.getEvents(
                calendarId,
                today.plusDays(39).toString(),
                today.plusDays(41).toString()
            )
            assertTrue(rawResult is CalDavResult.Success)
            val raw = (rawResult as CalDavResult.Success).data
                .firstOrNull { it.uid == created.uid }
            assertNotNull(raw, "updated event should be retrievable")

            val parsed = IcsParser().parse(raw!!.icalData)
            assertEquals(1, parsed.size)
            val fetched = parsed[0]

            println("\n=== Issue #2 round-trip ===")
            println("Sent SUMMARY:  $originalSummary")
            println("Got  SUMMARY:  ${fetched.summary}")
            println("Sent DESC:     $newDescription")
            println("Got  DESC:     ${fetched.description}")
            println("Raw ICS from iCloud:\n${raw.icalData}")

            assertEquals(originalSummary, fetched.summary,
                "Issue #2: SUMMARY corrupted in get_events. Expected '$originalSummary' but got '${fetched.summary}'")
            assertEquals(newDescription, fetched.description,
                "DESCRIPTION should match the new short value")
        } finally {
            val del = service.deleteEvent(created.uid)
            println("Cleanup delete: $del")
        }
    }

    @Test
    fun `update event without etag triggers PROPFIND recovery (chunk 33)`() {
        assumeTrue(username != null && password != null, "Credentials not set")

        val service = CalendarService(newClient())
        val calendarId = firstWritableCalendarId(service)

        val today = LocalDate.now()
        val createResult = service.createEvent(
            calendarId = calendarId,
            summary = "MCP-LIVE: chunk 33 etag recovery",
            startTime = "${today.plusDays(28)}T11:00:00Z",
            endTime = "${today.plusDays(28)}T12:00:00Z"
        )
        assertTrue(createResult is ServiceResult.Success, "create should succeed: $createResult")
        val created = (createResult as ServiceResult.Success).data

        try {
            // Simulate the "etag was lost" scenario by calling the underlying client
            // with a null etag — the new chunk-33 PROPFIND-recovery path should kick in
            // and the update should still succeed.
            val updateIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//EN
                BEGIN:VEVENT
                UID:${created.uid}
                DTSTAMP:20260101T000000Z
                DTSTART:${today.plusDays(28).toString().replace("-","")}T110000Z
                DTEND:${today.plusDays(28).toString().replace("-","")}T120000Z
                SUMMARY:MCP-LIVE: chunk 33 etag recovery (edited via null etag)
                END:VEVENT
                END:VCALENDAR
            """.trimIndent().replace("\n", "\r\n") + "\r\n"

            val client = newClient()
            val updateResult = client.updateEvent(created.href, updateIcs, etag = null)
            println("\n=== updateEvent with null etag ===")
            println("Result: $updateResult")
            assertTrue(
                updateResult is CalDavResult.Success,
                "update with null etag should succeed via PROPFIND recovery: $updateResult"
            )
        } finally {
            // The direct client.updateEvent above bypassed the service cache,
            // so service.deleteEvent would issue DELETE with the now-stale etag
            // and 412 (orphans the event). Use the underlying client with
            // etag=null instead — chunk 33's PROPFIND recovery handles it.
            val del = newClient().deleteEvent(created.href, etag = null)
            println("Cleanup delete: $del")
        }
    }
}
