package org.onekash.mcp.calendar.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
}
