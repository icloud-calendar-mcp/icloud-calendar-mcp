package org.onekash.mcp.calendar.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.onekash.mcp.calendar.caldav.CalDavResult
import org.onekash.mcp.calendar.ics.IcsParser
import org.onekash.mcp.calendar.service.ServiceResult
import java.time.LocalDate

/**
 * Adversarial live suite against real iCloud.
 *
 * Where the comprehensive suite checks the happy path, this one probes the ways
 * the write/read path can be abused or can fail: CRLF/property injection through
 * the tool inputs, oversized payloads, stale-ETag conflicts (412) and recovery,
 * delete idempotency, and boundary-shaped values that historically broke
 * escaping.
 */
@DisplayName("Live: adversarial calendar workflows against iCloud")
class AdversarialLiveTest : LiveCalendarTestBase() {

    private val parser = IcsParser()
    private val base: LocalDate = LocalDate.now().plusDays(80)

    // ── Injection defense ────────────────────────────────────────────────────

    @Test
    @DisplayName("CRLF injection in summary cannot smuggle a second property")
    fun crlfInjectionInSummary() {
        val day = base
        // A newline in the title must NOT create an extra ICS property line.
        val created = createTracked(
            summary = "injected\r\nATTENDEE:mailto:evil@hacker.com",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z"
        )
        val raw = fetchRawIcs(created.uid, day.toString())
        assertNotNull(raw, "event should be retrievable")
        // No standalone ATTENDEE line should exist — the value stays inside SUMMARY.
        val hasSmuggledAttendee = raw!!.lineSequence().any {
            it.startsWith("ATTENDEE:mailto:evil@hacker.com")
        }
        assertTrue(!hasSmuggledAttendee, "CRLF injection smuggled a property:\n$raw")
        // The event should still parse to exactly one event.
        assertEquals(1, parser.parse(raw).size)
    }

    @Test
    @DisplayName("property-injection via description stays a single DESCRIPTION value")
    fun injectionInDescription() {
        val day = base.plusDays(1)
        val created = createTracked(
            summary = "desc injection",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z",
            description = "note\r\nSUMMARY:HIJACKED\r\nX-EVIL:1"
        )
        val back = service.getEvents(calendarId, day.minusDays(1).toString(), day.plusDays(1).toString())
        assertTrue(back is ServiceResult.Success)
        val found = (back as ServiceResult.Success).data.first { it.uid == created.uid }
        // SUMMARY must be our real summary, not the smuggled one.
        assertEquals(prefixed("desc injection"), found.summary, "description injection hijacked SUMMARY")
    }

    // ── Boundary payloads ────────────────────────────────────────────────────

    @Test
    @DisplayName("max-length description (5000 chars) round-trips intact")
    fun maxLengthDescription() {
        val day = base.plusDays(2)
        val big = "x".repeat(5000)
        val created = createTracked(
            summary = "max desc",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z",
            description = big
        )
        val back = service.getEvents(calendarId, day.minusDays(1).toString(), day.plusDays(1).toString())
        assertTrue(back is ServiceResult.Success)
        val found = (back as ServiceResult.Success).data.first { it.uid == created.uid }
        assertEquals(5000, found.description?.length, "large description length must survive folding")
    }

    // ── ETag conflict + recovery ─────────────────────────────────────────────

    @Test
    @DisplayName("stale If-Match ETag yields 412, fresh ETag succeeds")
    fun staleEtagConflict() {
        val day = base.plusDays(3)
        val created = createTracked(
            summary = "etag conflict",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z"
        )
        val href = created.href
        val staleEtag = created.etag
        assertNotNull(staleEtag, "create should return an ETag")

        // Mutate once via the raw client so iCloud's ETag advances past `staleEtag`.
        val client = LiveTestSupport.newClient()
        val firstIcs = fetchRawIcs(created.uid, day.toString())
        assertNotNull(firstIcs, "should fetch current ICS")
        val bumped = firstIcs!!.replace("SUMMARY:", "SUMMARY:edited ")
        val freshEtag = fetchCurrentEtag(created.uid, day)
        val ok = client.updateEvent(href, bumped, etag = freshEtag)
        assertTrue(ok is CalDavResult.Success, "update with fresh ETag should succeed: $ok")

        // Now a PUT with the ORIGINAL (stale) ETag must be rejected with 412.
        val conflict = client.updateEvent(href, bumped, etag = staleEtag)
        assertTrue(
            conflict is CalDavResult.Error && conflict.code == 412,
            "stale ETag should yield 412 Precondition Failed, got: $conflict"
        )
    }

    // ── Delete semantics ─────────────────────────────────────────────────────

    @Test
    @DisplayName("double delete is idempotent-ish: second delete reports NOT_FOUND, never crashes")
    fun doubleDelete() {
        val day = base.plusDays(4)
        val created = createTracked(
            summary = "double delete",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z"
        )
        val first = service.deleteEvent(created.uid)
        assertTrue(first is ServiceResult.Success, "first delete should succeed: $first")

        // The service resolves UID via its cache, which was cleared on delete,
        // so the second delete is a clean 404 — not an exception, not a 5xx.
        val second = service.deleteEvent(created.uid)
        assertTrue(
            second is ServiceResult.Error && second.code == 404,
            "second delete should be a clean 404, got: $second"
        )
    }

    // ── Escaping regression guard (the double-escape bug) ────────────────────

    @Test
    @DisplayName("backslash-heavy path value is not double-escaped on read-back")
    fun backslashPathNoDoubleEscape() {
        val day = base.plusDays(5)
        val location = "C:\\Users\\Name\\Calendar; share,now"
        val created = createTracked(
            summary = "backslash path",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z",
            location = location
        )
        val back = service.getEvents(calendarId, day.minusDays(1).toString(), day.plusDays(1).toString())
        assertTrue(back is ServiceResult.Success)
        val found = (back as ServiceResult.Success).data.first { it.uid == created.uid }
        assertEquals(location, found.location, "location must not gain extra backslashes on round-trip")
    }

    /** Fetch the live ETag for an event via a raw client REPORT. */
    private fun fetchCurrentEtag(uid: String, day: LocalDate): String? {
        val client = LiveTestSupport.newClient()
        val res = client.getEvents(
            calendarId,
            day.minusDays(1).toString(),
            day.plusDays(1).toString()
        )
        if (res !is CalDavResult.Success) return null
        return res.data.firstOrNull { it.uid == uid }?.etag
    }

    @Test
    @DisplayName("a refused write into a calendar without VEVENT support reports iCloud's reason")
    fun refusedWriteReportsPrecondition() {
        val raw = LiveTestSupport.newClient()
        val listing = raw.listCalendars()
        check(listing is CalDavResult.Success) { "listCalendars failed: $listing" }
        val target = listing.data.firstOrNull { it.supportedComponents.isNotEmpty() && "VEVENT" !in it.supportedComponents }
        org.junit.jupiter.api.Assumptions.assumeTrue(target != null, "no calendar without VEVENT support on this account")
        target!!

        // Probe: what does iCloud answer to a VEVENT PUT here? Log the status and element names only.
        val creds = LiveTestSupport.credentials!!
        val href = "${target.url.trimEnd('/')}/${java.util.UUID.randomUUID()}.ics"
        val day = java.time.LocalDate.now().plusDays(150).toString().replace("-", "")
        val body = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//icloud-calendar-mcp//live-test//EN",
            "BEGIN:VEVENT", "UID:${java.util.UUID.randomUUID()}@icloud-calendar-mcp-live", "DTSTAMP:20260101T000000Z",
            "DTSTART:${day}T090000Z", "DTEND:${day}T100000Z", "SUMMARY:${prefixed("vevent probe")}",
            "END:VEVENT", "END:VCALENDAR").joinToString("\r\n") + "\r\n"
        val http = okhttp3.OkHttpClient.Builder().followRedirects(false).build()
        val request = okhttp3.Request.Builder().url(href)
            .put(body.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .header("Authorization", okhttp3.Credentials.basic(creds.username, creds.password))
            .header("If-None-Match", "*")
            .build()
        var stored = false
        var probeNames = emptyList<String>()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.source()?.let { it.request(65_536); it.buffer.clone().readUtf8() }.orEmpty()
                val names = Regex("""<(?:[A-Za-z][\w.-]*:)?([A-Za-z][\w-]*)[\s/>]""").findAll(text).map { it.groupValues[1] }
                    .distinct().toList()
                println("  PROBE status=${response.code} elements=$names")
                probeNames = org.onekash.mcp.calendar.caldav.ICloudXmlParser().parsePreconditions(text)
                stored = response.isSuccessful
            }
            check(!stored) { "iCloud accepted a VEVENT PUT into a calendar without VEVENT support" }
        } finally {
            if (stored) println("  probe cleanup: ${raw.deleteEvent(href, null)::class.simpleName}")
        }

        // Assertion: create_event through the service carries iCloud's reason.
        val result = service.createEvent(
            calendarId = target.id, summary = prefixed("vevent refused"),
            startTime = "${java.time.LocalDate.now().plusDays(150)}T09:00:00Z",
            endTime = "${java.time.LocalDate.now().plusDays(150)}T10:00:00Z"
        )
        if (result is ServiceResult.Success) {
            service.deleteEvent(result.data.handle!!)
            error("create_event into a calendar without VEVENT support succeeded")
        }
        val message = (result as ServiceResult.Error).message
        println("  create_event error: code=${result.code} message=$message")
        // Assert what the probe saw: iCloud's precondition names when it sent them, else the fallback.
        if (probeNames.isNotEmpty()) {
            assertTrue(probeNames.all { it in message }, "the error names iCloud's preconditions $probeNames: $message")
        } else {
            assertTrue(message.contains("HTTP 403") && message.contains("does not accept events"),
                "iCloud gave no reason, so the error explains the calendar: $message")
        }
    }
}
