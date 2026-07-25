package org.onekash.mcp.calendar.live

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.onekash.mcp.calendar.caldav.CalDavResult
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.ServiceResult
import java.time.LocalDate

/**
 * Live coverage for two service-layer guarantees around event editing. Both live
 * at CalendarService.update/deleteEvent, which AdversarialLiveTest.staleEtagConflict
 * does not exercise — that test only proves the raw CalDavClient returns 412, not
 * that the service recovers from it.
 *
 *   - a 412 Precondition Failed refetches the current etag and retries once
 *     (rather than being surfaced raw with no recovery);
 *   - update/delete resolve an event by its self-contained handle, so a fresh
 *     process with a cold cache never phantom-404s.
 *
 * Run: ./gradlew test -Pintegration   (self-skips without iCloud credentials)
 */
@DisplayName("Live: ETag-conflict recovery and cross-process event identity")
class EtagAndIdentityLiveTest : LiveCalendarTestBase() {

    private val base: LocalDate = LocalDate.now().plusDays(100)

    // ── 412 on a stale cached ETag must recover, not surface raw ───────────────
    //
    // Create an event (service caches etag E0), then mutate it out-of-band so
    // iCloud's etag advances to E1. A subsequent service.updateEvent() still holds
    // E0, sends If-Match: E0, and iCloud returns 412. The service must refetch the
    // current etag and retry once so the update succeeds (server-wins
    // reconciliation), rather than propagating the 412 verbatim.
    @Test
    @DisplayName("update after out-of-band edit recovers from 412 instead of failing")
    fun updateRecoversFromStaleEtag412() {
        val day = base
        val created = createTracked(
            summary = "412 recovery",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z"
        )

        // Advance iCloud's etag out from under the service's cache, via a fresh client.
        val rawClient = LiveTestSupport.newClient()
        val currentIcs = fetchRawIcs(created.uid, day.toString())
        assertNotNull(currentIcs, "should fetch current ICS for out-of-band edit")
        val freshEtag = fetchCurrentEtag(created.uid, day)
        val bumped = currentIcs!!.replace("SUMMARY:", "SUMMARY:oob ")
        val oob = rawClient.updateEvent(created.href, bumped, etag = freshEtag)
        assertTrue(oob is CalDavResult.Success, "out-of-band edit should succeed: $oob")

        // Now the service still has the STALE etag cached. This update must not fail
        // with a raw 412 — it should recover and apply the change. Reference the event
        // by its self-contained handle (the durable, cache-independent identity).
        val handle = created.handle
        assertNotNull(handle, "create_event must mint a handle")
        val result = service.updateEvent(handle!!, summary = prefixed("412 recovery — reconciled"))

        assertTrue(
            result is ServiceResult.Success,
            "update should recover from a stale-etag 412 by refetching and " +
                "retrying once, got: $result"
        )
    }

    // ── Event identity must be durable, not process-local (TTL cache) ──────────
    //
    // Create an event through one service instance, then resolve it from a SECOND,
    // fresh service instance — simulating a restarted process, a different worker,
    // or a call arriving after the cache TTL expired. A bare UID is not a usable
    // reference across that boundary: the fresh service has never cached the event,
    // so update/delete would phantom-404 even though it plainly exists on iCloud.
    // A self-contained handle (carrying the href) lets any process act on it.
    @Test
    @DisplayName("update from a fresh process (no warm cache) resolves the event, not phantom-404")
    fun updateFromColdProcessResolvesEvent() {
        val day = base.plusDays(1)
        val created = createTracked(
            summary = "cold process",
            startTime = "${day}T09:00:00Z",
            endTime = "${day}T10:00:00Z"
        )

        // A brand-new service that has never seen this event — the cross-process /
        // post-TTL condition, made deterministic (no 5-minute wait).
        val coldService = CalendarService(LiveTestSupport.newClient())

        // Reference the event by its handle: a fresh process has an empty cache, so a
        // bare UID would phantom-404. The handle carries the href, so it resolves.
        val handle = created.handle
        assertNotNull(handle, "create_event must mint a handle")
        val result = coldService.updateEvent(handle!!, summary = prefixed("cold process — updated"))

        assertTrue(
            result is ServiceResult.Success,
            "a fresh process with an empty cache must resolve an existing event by " +
                "its handle rather than phantom-404, got: $result"
        )
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
