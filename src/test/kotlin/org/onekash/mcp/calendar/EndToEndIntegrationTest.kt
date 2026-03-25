package org.onekash.mcp.calendar

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.onekash.mcp.calendar.caldav.*
import kotlin.test.*

/**
 * End-to-end integration test (Chunk 24).
 *
 * Exercises the full HTTP stack via MockWebServer:
 *   create → get → update (with property preservation) → delete
 *
 * Verifies:
 * - Extended fields (status, categories, location, rrule) in ICS sent to server
 * - All fields round-trip through get_events parsing
 * - Property preservation on update (VALARM, ATTENDEE, ORGANIZER, X-* kept)
 * - DELETE sent with correct href and If-Match etag
 */
class EndToEndIntegrationTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: OkHttpCalDavClient

    @BeforeTest
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val baseUrl = mockServer.url("/").toString().trimEnd('/')
        val creds = CalDavCredentials("test@icloud.com", "test-password-1234")
        client = OkHttpCalDavClient(baseUrl, creds)
        client.skipWellKnownDiscovery()
    }

    @AfterTest
    fun teardown() {
        mockServer.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════════
    // MAIN E2E: create → get → update → delete
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `full create-get-update-delete flow with extended fields and property preservation`() {
        val eventIcs = buildRichIcs()

        // Enqueue ALL responses up front (MockWebServer serves them in order)
        // Step 1 (create): 3 discovery + 1 PUT = 4 responses
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()
        mockServer.enqueue(MockResponse().setResponseCode(201).addHeader("ETag", "\"etag-v1\""))

        // Step 2 (get): 1 REPORT (discovery cached)
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(
            buildReportResponse(eventIcs, "etag-v1")))

        // Step 3 (update): 1 PUT (discovery cached)
        mockServer.enqueue(MockResponse().setResponseCode(204).addHeader("ETag", "\"etag-v2\""))

        // Step 4 (delete): 1 DELETE (discovery cached)
        mockServer.enqueue(MockResponse().setResponseCode(204))

        // ─── Step 1: CREATE ─────────────────────────────────────────────

        val createResult = client.createEvent("home", eventIcs)

        assertIs<CalDavResult.Success<CalDavEvent>>(createResult)
        val created = createResult.data
        assertEquals("e2e-test-001@icloud-calendar-mcp", created.uid)
        assertEquals("etag-v1", created.etag)
        assertEquals(4, mockServer.requestCount, "3 discovery + 1 PUT")

        // Verify PUT request
        // Skip 3 discovery requests
        mockServer.takeRequest() // principal
        mockServer.takeRequest() // home-set
        mockServer.takeRequest() // calendars
        val putRequest = mockServer.takeRequest() // PUT
        assertEquals("PUT", putRequest.method)
        assertEquals("*", putRequest.getHeader("If-None-Match"))
        val putBody = putRequest.body.readUtf8()
        assertTrue(putBody.contains("SUMMARY:E2E Meeting"), "PUT has SUMMARY")
        assertTrue(putBody.contains("LOCATION:Conference Room B"), "PUT has LOCATION")
        assertTrue(putBody.contains("STATUS:CONFIRMED"), "PUT has STATUS")
        assertTrue(putBody.contains("CATEGORIES:Work,Testing"), "PUT has CATEGORIES")
        assertTrue(putBody.contains("RRULE:FREQ=WEEKLY"), "PUT has RRULE")
        assertTrue(putBody.contains("ORGANIZER"), "PUT has ORGANIZER")
        assertTrue(putBody.contains("bob@example.com"), "PUT has ATTENDEE Bob")
        assertTrue(putBody.contains("BEGIN:VALARM"), "PUT has VALARM")

        // ─── Step 2: GET ────────────────────────────────────────────────

        val getResult = client.getEvents("home", "2025-01-15", "2025-01-31")

        assertIs<CalDavResult.Success<List<CalDavEvent>>>(getResult)
        assertEquals(1, getResult.data.size, "Should return 1 event")
        val fetched = getResult.data[0]
        assertEquals("e2e-test-001@icloud-calendar-mcp", fetched.uid)
        assertEquals("etag-v1", fetched.etag)
        assertEquals(5, mockServer.requestCount, "4 prev + 1 REPORT")

        // Verify ICS fields survived round-trip
        assertTrue(fetched.icalData.contains("SUMMARY:E2E Meeting"))
        assertTrue(fetched.icalData.contains("LOCATION:Conference Room B"))
        assertTrue(fetched.icalData.contains("STATUS:CONFIRMED"))
        assertTrue(fetched.icalData.contains("CATEGORIES:Work,Testing"))
        assertTrue(fetched.icalData.contains("RRULE:FREQ=WEEKLY"))
        assertTrue(fetched.icalData.contains("alice@example.com"))
        assertTrue(fetched.icalData.contains("bob@example.com"))
        assertTrue(fetched.icalData.contains("carol@example.com"))
        assertTrue(fetched.icalData.contains("BEGIN:VALARM"))
        assertTrue(fetched.icalData.contains("X-APPLE-TRAVEL-ADVISORY"))

        val reportRequest = mockServer.takeRequest() // REPORT
        assertEquals("REPORT", reportRequest.method)
        assertTrue(reportRequest.body.readUtf8().contains("calendar-data"))

        // ─── Step 3: UPDATE (title only — everything else preserved) ────

        val updatedIcs = eventIcs
            .replace("SUMMARY:E2E Meeting", "SUMMARY:Updated E2E Meeting")
            .let { ics ->
                if (ics.contains("SEQUENCE:")) {
                    ics.replace(Regex("SEQUENCE:\\d+"), "SEQUENCE:1")
                } else {
                    ics.replace("END:VEVENT", "SEQUENCE:1\nEND:VEVENT")
                }
            }

        val updateResult = client.updateEvent(
            href = fetched.href,
            icalData = updatedIcs,
            etag = "\"etag-v1\""
        )

        assertIs<CalDavResult.Success<CalDavEvent>>(updateResult)
        assertEquals("etag-v2", updateResult.data.etag)
        assertEquals(6, mockServer.requestCount, "5 prev + 1 PUT")

        val updateRequest = mockServer.takeRequest() // PUT update
        assertEquals("PUT", updateRequest.method)
        assertEquals("\"etag-v1\"", updateRequest.getHeader("If-Match"))
        val updateBody = updateRequest.body.readUtf8()
        assertTrue(updateBody.contains("SUMMARY:Updated E2E Meeting"), "Title updated")
        assertTrue(updateBody.contains("LOCATION:Conference Room B"), "Location preserved")
        assertTrue(updateBody.contains("STATUS:CONFIRMED"), "Status preserved")
        assertTrue(updateBody.contains("CATEGORIES:Work,Testing"), "Categories preserved")
        assertTrue(updateBody.contains("RRULE:FREQ=WEEKLY"), "RRULE preserved")
        assertTrue(updateBody.contains("alice@example.com"), "Organizer preserved")
        assertTrue(updateBody.contains("bob@example.com"), "Attendee Bob preserved")
        assertTrue(updateBody.contains("carol@example.com"), "Attendee Carol preserved")
        assertTrue(updateBody.contains("X-APPLE-TRAVEL-ADVISORY"), "X-APPLE prop preserved")
        assertTrue(updateBody.contains("BEGIN:VALARM"), "VALARM preserved")
        assertTrue(updateBody.contains("TRIGGER:-PT15M"), "Alarm trigger preserved")
        assertTrue(updateBody.contains("SEQUENCE:1"), "SEQUENCE incremented")

        // ─── Step 4: DELETE ─────────────────────────────────────────────

        val deleteResult = client.deleteEvent(
            href = fetched.href,
            etag = "\"etag-v2\""
        )

        assertIs<CalDavResult.Success<Unit>>(deleteResult)
        assertEquals(7, mockServer.requestCount, "6 prev + 1 DELETE")

        val deleteRequest = mockServer.takeRequest() // DELETE
        assertEquals("DELETE", deleteRequest.method)
        assertEquals("\"etag-v2\"", deleteRequest.getHeader("If-Match"))
        assertTrue(deleteRequest.path!!.contains("e2e-test-001"),
            "DELETE targets correct resource")
    }

    // ═══════════════════════════════════════════════════════════════════
    // ETAG-ONLY OPTIMIZATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `fetchEtags sends REPORT without calendar-data`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
            <D:response>
                <D:href>/123/calendars/home/event1.ics</D:href>
                <D:propstat>
                    <D:prop><D:getetag>"etag-abc"</D:getetag></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
            <D:response>
                <D:href>/123/calendars/home/event2.ics</D:href>
                <D:propstat>
                    <D:prop><D:getetag>"etag-def"</D:getetag></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
            </D:multistatus>
        """.trimIndent()))

        val result = client.fetchEtags("home", "2025-01-01", "2025-01-31")

        assertIs<CalDavResult.Success<Map<String, String?>>>(result)
        assertEquals(2, result.data.size)
        assertEquals("etag-abc", result.data["/123/calendars/home/event1.ics"])
        assertEquals("etag-def", result.data["/123/calendars/home/event2.ics"])

        // Verify REPORT body does NOT contain calendar-data
        repeat(3) { mockServer.takeRequest() } // skip discovery
        val reportReq = mockServer.takeRequest()
        assertFalse(reportReq.body.readUtf8().contains("calendar-data"),
            "ETag-only REPORT must not request calendar-data")
    }

    // ═══════════════════════════════════════════════════════════════════
    // ETAG CONFLICT
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `update with stale ETag returns 412 conflict`() {
        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:conflict@test\nSUMMARY:Conflict\nEND:VEVENT\nEND:VCALENDAR"

        mockServer.enqueue(MockResponse().setResponseCode(412))

        val result = client.updateEvent(
            href = "/123/calendars/home/conflict.ics",
            icalData = ics,
            etag = "\"stale-etag\""
        )

        assertIs<CalDavResult.Error>(result)
        assertTrue(result.isConflict)
        assertEquals(412, result.code)

        val request = mockServer.takeRequest()
        assertEquals("\"stale-etag\"", request.getHeader("If-Match"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // ETAG FALLBACK VIA PROPFIND
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `create with missing ETag header falls back to PROPFIND`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        // PUT returns 201 with NO ETag header
        mockServer.enqueue(MockResponse().setResponseCode(201))

        // PROPFIND fallback returns ETag
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
            <D:response>
            <D:href>/123/calendars/home/fallback.ics</D:href>
            <D:propstat>
            <D:prop><D:getetag>"fallback-etag"</D:getetag></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
            </D:response>
            </D:multistatus>
        """.trimIndent()))

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:fallback@test\nSUMMARY:Fallback\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.createEvent("home", ics)

        assertIs<CalDavResult.Success<CalDavEvent>>(result)
        assertEquals("fallback-etag", result.data.etag)

        // Verify: 3 discovery + 1 PUT + 1 PROPFIND = 5
        assertEquals(5, mockServer.requestCount)
        repeat(3) { mockServer.takeRequest() } // discovery
        mockServer.takeRequest() // PUT
        val propfindReq = mockServer.takeRequest() // PROPFIND
        assertEquals("PROPFIND", propfindReq.method)
        assertTrue(propfindReq.body.readUtf8().contains("getetag"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun buildRichIcs(): String = buildString {
        appendLine("BEGIN:VCALENDAR")
        appendLine("VERSION:2.0")
        appendLine("PRODID:-//Test//Test//EN")
        appendLine("BEGIN:VEVENT")
        appendLine("UID:e2e-test-001@icloud-calendar-mcp")
        appendLine("DTSTAMP:20250115T100000Z")
        appendLine("DTSTART:20250115T100000Z")
        appendLine("DTEND:20250115T110000Z")
        appendLine("SUMMARY:E2E Meeting")
        appendLine("DESCRIPTION:End-to-end test event")
        appendLine("LOCATION:Conference Room B")
        appendLine("STATUS:CONFIRMED")
        appendLine("CATEGORIES:Work,Testing")
        appendLine("RRULE:FREQ=WEEKLY;BYDAY=WE")
        appendLine("ORGANIZER;CN=Alice:mailto:alice@example.com")
        appendLine("ATTENDEE;CN=Bob:mailto:bob@example.com")
        appendLine("ATTENDEE;CN=Carol:mailto:carol@example.com")
        appendLine("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC")
        appendLine("BEGIN:VALARM")
        appendLine("ACTION:DISPLAY")
        appendLine("TRIGGER:-PT15M")
        appendLine("DESCRIPTION:15 min reminder")
        appendLine("END:VALARM")
        appendLine("END:VEVENT")
        appendLine("END:VCALENDAR")
    }.trimEnd()

    private fun buildReportResponse(ics: String, etag: String): String {
        // Escape ICS for XML embedding (replace & and < if present)
        val safeIcs = ics.replace("&", "&amp;").replace("<", "&lt;")
        return """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:response>
<D:href>/123/calendars/home/e2e-test-001.ics</D:href>
<D:propstat>
<D:prop>
<D:getetag>"$etag"</D:getetag>
<C:calendar-data>$safeIcs</C:calendar-data>
</D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""
    }

    private fun enqueuePrincipalResponse() {
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:">
<D:response>
<D:href>/</D:href>
<D:propstat>
<D:prop><D:current-user-principal><D:href>/123/principal/</D:href></D:current-user-principal></D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""
        ))
    }

    private fun enqueueHomeSetResponse() {
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:response>
<D:href>/123/principal/</D:href>
<D:propstat>
<D:prop><C:calendar-home-set><D:href>/123/calendars/</D:href></C:calendar-home-set></D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""
        ))
    }

    private fun enqueueCalendarListResponse() {
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:response>
<D:href>/123/calendars/home/</D:href>
<D:propstat>
<D:prop><D:displayname>Home</D:displayname><D:resourcetype><D:collection/><C:calendar/></D:resourcetype></D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""
        ))
    }
}
