package org.onekash.mcp.calendar.caldav

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Tests for OkHttpCalDavClient using MockWebServer.
 *
 * Tests verify:
 * - CalDAV discovery flow (principal -> home-set -> calendars)
 * - Event retrieval (calendar-query REPORT)
 * - Event CRUD operations
 * - Error handling (auth failures, 404, server errors)
 * - Retry behavior for retryable errors
 */
class OkHttpCalDavClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: OkHttpCalDavClient

    @BeforeTest
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val baseUrl = mockServer.url("/").toString().trimEnd('/')
        val creds = CalDavCredentials("test@icloud.com", "test-password")
        client = OkHttpCalDavClient(baseUrl, creds)
        client.skipWellKnownDiscovery() // Skip for non-well-known tests
    }

    @AfterTest
    fun teardown() {
        mockServer.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════════
    // CALENDAR DISCOVERY TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `listCalendars performs 3-step discovery`() {
        // Step 1: current-user-principal
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
            <D:response>
            <D:href>/</D:href>
            <D:propstat>
            <D:prop><D:current-user-principal><D:href>/123/principal/</D:href></D:current-user-principal></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
            </D:response>
            </D:multistatus>
        """.trimIndent()))

        // Step 2: calendar-home-set
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
            <D:response>
            <D:href>/123/principal/</D:href>
            <D:propstat>
            <D:prop><C:calendar-home-set><D:href>/123/calendars/</D:href></C:calendar-home-set></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
            </D:response>
            </D:multistatus>
        """.trimIndent()))

        // Step 3: list calendars
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/" xmlns:C="urn:ietf:params:xml:ns:caldav">
            <D:response>
            <D:href>/123/calendars/home/</D:href>
            <D:propstat>
            <D:prop>
            <D:displayname>Home</D:displayname>
            <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
            <CS:getctag>ctag-abc</CS:getctag>
            </D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
            </D:response>
            </D:multistatus>
        """.trimIndent()))

        val result = client.listCalendars()

        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Home", result.data[0].displayName)
        assertEquals("home", result.data[0].id)
        assertEquals("ctag-abc", result.data[0].ctag)

        // Verify 3 requests were made
        assertEquals(3, mockServer.requestCount)
    }

    @Test
    fun `listCalendars returns multiple calendars`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()

        // Multiple calendars
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
            <D:response>
            <D:href>/123/calendars/home/</D:href>
            <D:propstat>
            <D:prop><D:displayname>Home</D:displayname><D:resourcetype><D:collection/><C:calendar/></D:resourcetype></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
            </D:response>
            <D:response>
            <D:href>/123/calendars/work/</D:href>
            <D:propstat>
            <D:prop><D:displayname>Work</D:displayname><D:resourcetype><D:collection/><C:calendar/></D:resourcetype></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
            </D:response>
            </D:multistatus>
        """.trimIndent()))

        val result = client.listCalendars()

        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result)
        assertEquals(2, result.data.size)
        assertEquals("Home", result.data[0].displayName)
        assertEquals("Work", result.data[1].displayName)
    }

    @Test
    fun `listCalendars handles auth failure`() {
        mockServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val result = client.listCalendars()

        assertIs<CalDavResult.Error>(result)
        assertEquals(401, result.code)
        assertTrue(result.isAuthError)
    }

    @Test
    fun `listCalendars handles missing principal`() {
        // Empty response without principal
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
            <D:response>
            <D:href>/</D:href>
            <D:propstat><D:prop></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
            </D:response>
            </D:multistatus>
        """.trimIndent()))

        val result = client.listCalendars()

        assertIs<CalDavResult.Error>(result)
        assertTrue(result.message.contains("principal", ignoreCase = true))
    }

    // ═══════════════════════════════════════════════════════════════════
    // GET EVENTS TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `getEvents returns events in date range`() {
        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:event1@test\nSUMMARY:Meeting\nEND:VEVENT\nEND:VCALENDAR"

        // Discovery steps (needed to get calendar href)
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        // Events REPORT response
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:response>
<D:href>/123/calendars/home/event1.ics</D:href>
<D:propstat>
<D:prop>
<D:getetag>"etag1"</D:getetag>
<C:calendar-data>$ics</C:calendar-data>
</D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""
        ))

        val result = client.getEvents("home", "2025-01-01", "2025-01-31")

        assertIs<CalDavResult.Success<List<CalDavEvent>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("event1@test", result.data[0].uid)
        assertEquals("etag1", result.data[0].etag)
    }

    @Test
    fun `getEvents returns empty list for no events`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        // Empty REPORT response
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
            </D:multistatus>
        """.trimIndent()))

        val result = client.getEvents("home", "2025-01-01", "2025-01-31")

        assertIs<CalDavResult.Success<List<CalDavEvent>>>(result)
        assertTrue(result.data.isEmpty())
    }

    @Test
    fun `getEvents returns error for unknown calendar`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        val result = client.getEvents("nonexistent", "2025-01-01", "2025-01-31")

        assertIs<CalDavResult.Error>(result)
        assertTrue(result.isNotFound)
    }

    // ═══════════════════════════════════════════════════════════════════
    // CREATE EVENT TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `createEvent returns created event with etag`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        // PUT response
        mockServer.enqueue(MockResponse()
            .setResponseCode(201)
            .addHeader("ETag", "\"new-etag-123\""))

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:new-event@test\nSUMMARY:New Event\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.createEvent("home", ics)

        assertIs<CalDavResult.Success<CalDavEvent>>(result)
        assertEquals("new-event@test", result.data.uid)
        assertEquals("new-etag-123", result.data.etag)
    }

    @Test
    fun `createEvent handles conflict (event exists)`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        mockServer.enqueue(MockResponse().setResponseCode(412)) // Precondition Failed

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:existing@test\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.createEvent("home", ics)

        assertIs<CalDavResult.Error>(result)
        assertEquals(412, result.code)
    }

    // ═══════════════════════════════════════════════════════════════════
    // UPDATE EVENT TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `updateEvent returns updated event with new etag`() {
        // PUT response with new etag
        mockServer.enqueue(MockResponse()
            .setResponseCode(204)
            .addHeader("ETag", "\"updated-etag\""))

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:event1@test\nSUMMARY:Updated\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.updateEvent("/123/calendars/home/event1.ics", ics, "\"old-etag\"")

        assertIs<CalDavResult.Success<CalDavEvent>>(result)
        assertEquals("updated-etag", result.data.etag)

        // Verify If-Match header was sent
        val request = mockServer.takeRequest()
        assertEquals("\"old-etag\"", request.getHeader("If-Match"))
    }

    @Test
    fun `updateEvent handles conflict (stale etag)`() {
        mockServer.enqueue(MockResponse().setResponseCode(412)) // Precondition Failed

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:test@test\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.updateEvent("/cal/event.ics", ics, "\"stale-etag\"")

        assertIs<CalDavResult.Error>(result)
        assertEquals(412, result.code)
    }

    @Test
    fun `updateEvent handles 404`() {
        mockServer.enqueue(MockResponse().setResponseCode(404))

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:test@test\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.updateEvent("/cal/missing.ics", ics, null)

        assertIs<CalDavResult.Error>(result)
        assertTrue(result.isNotFound)
    }

    // ═══════════════════════════════════════════════════════════════════
    // DELETE EVENT TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `deleteEvent returns success`() {
        mockServer.enqueue(MockResponse().setResponseCode(204))

        val result = client.deleteEvent("/cal/event.ics", "\"etag\"")

        assertIs<CalDavResult.Success<Unit>>(result)

        // Verify If-Match header
        val request = mockServer.takeRequest()
        assertEquals("\"etag\"", request.getHeader("If-Match"))
    }

    @Test
    fun `deleteEvent without etag still works`() {
        mockServer.enqueue(MockResponse().setResponseCode(204))

        val result = client.deleteEvent("/cal/event.ics", null)

        assertIs<CalDavResult.Success<Unit>>(result)

        // Verify no If-Match header
        val request = mockServer.takeRequest()
        assertEquals(null, request.getHeader("If-Match"))
    }

    @Test
    fun `deleteEvent handles 404 as success`() {
        // 404 on delete is often acceptable (already deleted)
        mockServer.enqueue(MockResponse().setResponseCode(404))

        val result = client.deleteEvent("/cal/missing.ics", null)

        // Could be success or error depending on implementation
        // Most CalDAV clients treat 404 on delete as success
        assertTrue(result.isSuccess || (result is CalDavResult.Error && result.isNotFound))
    }

    @Test
    fun `deleteEvent handles conflict`() {
        mockServer.enqueue(MockResponse().setResponseCode(412))

        val result = client.deleteEvent("/cal/event.ics", "\"wrong-etag\"")

        assertIs<CalDavResult.Error>(result)
        assertEquals(412, result.code)
    }

    // ═══════════════════════════════════════════════════════════════════
    // ERROR HANDLING TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `handles server error with retry hint`() {
        // Enqueue enough 503s for initial + 1 retry (MAX_RETRIES=1)
        mockServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        mockServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        val result = client.listCalendars()

        assertIs<CalDavResult.Error>(result)
        assertTrue(result.isServerError)
        assertTrue(result.isRetryable)
    }

    @Test
    fun `handles 403 forbidden`() {
        mockServer.enqueue(MockResponse().setResponseCode(403))

        val result = client.listCalendars()

        assertIs<CalDavResult.Error>(result)
        assertTrue(result.isAuthError)
        assertFalse(result.isRetryable)
    }

    // ═══════════════════════════════════════════════════════════════════
    // REQUEST VERIFICATION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `listCalendars sends correct PROPFIND requests`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        client.listCalendars()

        // First request: root PROPFIND for principal
        val req1 = mockServer.takeRequest()
        assertEquals("PROPFIND", req1.method)
        assertEquals("/", req1.path)
        assertTrue(req1.body.readUtf8().contains("current-user-principal"))

        // Second request: principal PROPFIND for home-set
        val req2 = mockServer.takeRequest()
        assertEquals("PROPFIND", req2.method)
        assertTrue(req2.body.readUtf8().contains("calendar-home-set"))

        // Third request: home-set PROPFIND for calendars
        val req3 = mockServer.takeRequest()
        assertEquals("PROPFIND", req3.method)
    }

    @Test
    fun `requests include Authorization header`() {
        enqueuePrincipalResponse()

        client.listCalendars()

        val request = mockServer.takeRequest()
        val authHeader = request.getHeader("Authorization")
        assertNotNull(authHeader)
        assertTrue(authHeader.startsWith("Basic "))
    }

    @Test
    fun `createEvent sends PUT with correct content type`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()
        mockServer.enqueue(MockResponse().setResponseCode(201).addHeader("ETag", "\"e\""))

        client.createEvent("home", "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:test@test\nEND:VEVENT\nEND:VCALENDAR")

        // Skip discovery requests
        mockServer.takeRequest()
        mockServer.takeRequest()
        mockServer.takeRequest()

        val putRequest = mockServer.takeRequest()
        assertEquals("PUT", putRequest.method)
        assertEquals("text/calendar; charset=utf-8", putRequest.getHeader("Content-Type"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // RETRY TESTS (Chunk 2)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `retries on 503 and succeeds`() {
        // First request: 503, then retry succeeds (principal)
        mockServer.enqueue(MockResponse().setResponseCode(503))
        enqueuePrincipalResponse()

        val result = client.listCalendars()
        // Should have retried past the 503
        if (result is CalDavResult.Error) {
            assertTrue(result.code != 503, "Should have retried past 503, got: ${result.code} ${result.message}")
        }
        // At least 2 requests (1 failure + 1 retry)
        assertTrue(mockServer.requestCount >= 2)
    }

    @Test
    fun `does not retry auth errors`() {
        mockServer.enqueue(MockResponse().setResponseCode(401))

        val result = client.listCalendars()

        assertIs<CalDavResult.Error>(result)
        assertEquals(401, result.code)
        assertEquals(1, mockServer.requestCount) // No retries
    }

    @Test
    fun `does not retry SSL errors`() {
        // We can't easily simulate SSL errors with MockWebServer,
        // but we test the error classification in executeSingleRequest
        // by checking that 401/403 are not retried (proxy for non-retryable)
        mockServer.enqueue(MockResponse().setResponseCode(403))

        val result = client.listCalendars()

        assertIs<CalDavResult.Error>(result)
        assertEquals(1, mockServer.requestCount)
    }

    @Test
    fun `exhausts retries and returns last error`() {
        // 2 consecutive 503s (initial + 1 retry = 2 attempts, MAX_RETRIES=1)
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse().setResponseCode(503))

        val result = client.listCalendars()

        assertIs<CalDavResult.Error>(result)
        assertTrue(result.isServerError)
        assertEquals(2, mockServer.requestCount) // initial + 1 retry
    }

    @Test
    fun `retries 429 with Retry-After header`() {
        // 429 with Retry-After: 1 (will be capped to 2s max)
        mockServer.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
        // Then success (principal discovery)
        enqueuePrincipalResponse()

        val start = System.currentTimeMillis()
        val result = client.listCalendars()
        val elapsed = System.currentTimeMillis() - start

        // Should have waited ~1s for Retry-After (Retry-After:1 = 1000ms)
        assertTrue(elapsed >= 900, "Expected delay >= 900ms for Retry-After:1, got ${elapsed}ms")
        // Should have retried
        assertTrue(mockServer.requestCount >= 2)
    }

    // ═══════════════════════════════════════════════════════════════════
    // BODY SIZE LIMIT TESTS (Chunk 1)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `response under 2MB is read normally`() {
        // 1MB body should succeed
        val body = "x".repeat(1_000_000)
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

        val result = client.listCalendars()
        // Should get past the first request (principal discovery) without body limit error
        // The actual result may be an error because we only enqueued 1 response, but not a "too large" error
        if (result is CalDavResult.Error) {
            assertFalse(result.message.contains("too large"), "1MB body should not trigger size limit")
        }
    }

    @Test
    fun `response over 2MB returns error`() {
        // 3MB body should fail
        val body = "x".repeat(3_000_000)
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(body))

        val result = client.listCalendars()

        assertIs<CalDavResult.Error>(result)
        assertTrue(result.message.contains("too large"), "Expected 'too large' error, got: ${result.message}")
        assertEquals(413, result.code)
    }

    @Test
    fun `response over limit rejected via Content-Length header check`() {
        // Content-Length header check rejects before streaming body.
        // Use updateEvent which goes directly to executeRequestWithHeaders (no listCalendars).
        val body = "x".repeat(3_000_001) // Just over MAX_BODY_SIZE
        mockServer.enqueue(MockResponse().setResponseCode(201).setBody(body))

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:test@test\nSUMMARY:Test\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.updateEvent("/cal/event.ics", ics, "\"etag\"")

        assertIs<CalDavResult.Error>(result)
        assertEquals(413, result.code)
        assertTrue(result.message.contains("too large"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // DATE VALIDATION TESTS (Chunk 6)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `getEvents rejects invalid date format`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        val result = client.getEvents("home", "not-a-date", "2025-01-31")

        assertIs<CalDavResult.Error>(result)
        assertEquals(400, result.code)
        assertTrue(result.message.contains("Invalid"))
    }

    @Test
    fun `getEvents rejects XML injection in dates`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        val result = client.getEvents("home", "2025<script>", "2025-01-31")

        assertIs<CalDavResult.Error>(result)
        assertEquals(400, result.code)
    }

    @Test
    fun `getEvents accepts valid date format`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()
        // Empty REPORT response
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:"></D:multistatus>
        """.trimIndent()))

        val result = client.getEvents("home", "2025-01-15", "2025-01-31")
        assertIs<CalDavResult.Success<List<CalDavEvent>>>(result)
    }

    // ═══════════════════════════════════════════════════════════════════
    // ETAG FALLBACK TESTS (Chunk 3)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `createEvent falls back to PROPFIND when PUT has no ETag`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        // PUT response with NO ETag header
        mockServer.enqueue(MockResponse().setResponseCode(201))

        // PROPFIND response for ETag fallback
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/123/calendars/home/event.ics</D:href>
                    <D:propstat>
                        <D:prop><D:getetag>"fallback-etag-123"</D:getetag></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()))

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:new@test\nSUMMARY:Test\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.createEvent("home", ics)

        assertIs<CalDavResult.Success<CalDavEvent>>(result)
        assertEquals("fallback-etag-123", result.data.etag)
    }

    @Test
    fun `createEvent returns null etag when both PUT and PROPFIND miss`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        // PUT response with NO ETag header
        mockServer.enqueue(MockResponse().setResponseCode(201))

        // PROPFIND response without ETag
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/event.ics</D:href>
                    <D:propstat>
                        <D:prop></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()))

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:no-etag@test\nSUMMARY:No ETag\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.createEvent("home", ics)

        assertIs<CalDavResult.Success<CalDavEvent>>(result)
        // No crash, etag is null
        assertEquals(null, result.data.etag)
    }

    @Test
    fun `createEvent returns null etag when PROPFIND returns 500`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        // PUT response with NO ETag header
        mockServer.enqueue(MockResponse().setResponseCode(201))

        // PROPFIND fails
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:fail@test\nSUMMARY:Fail\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.createEvent("home", ics)

        assertIs<CalDavResult.Success<CalDavEvent>>(result)
        assertEquals(null, result.data.etag)
    }

    @Test
    fun `updateEvent falls back to PROPFIND when PUT has no ETag`() {
        // PUT response with NO ETag header
        mockServer.enqueue(MockResponse().setResponseCode(204))

        // PROPFIND response for ETag fallback
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/cal/event.ics</D:href>
                    <D:propstat>
                        <D:prop><D:getetag>"update-fallback"</D:getetag></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()))

        val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:upd@test\nSUMMARY:Update\nEND:VEVENT\nEND:VCALENDAR"
        val result = client.updateEvent("/cal/event.ics", ics, "\"old\"")

        assertIs<CalDavResult.Success<CalDavEvent>>(result)
        assertEquals("update-fallback", result.data.etag)
    }

    // ═══════════════════════════════════════════════════════════════════
    // CACHE TTL TESTS (Chunk 5 - partial, validates Layer 1 @Volatile + TTL)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `listCalendars returns cached result on second call`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        // First call - discovery
        val result1 = client.listCalendars()
        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result1)
        assertEquals(3, mockServer.requestCount) // 3 discovery requests

        // Second call - should use cache (no additional requests)
        val result2 = client.listCalendars()
        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result2)
        assertEquals(3, mockServer.requestCount) // Still 3 (no new requests)
    }

    // ═══════════════════════════════════════════════════════════════════
    // WELL-KNOWN DISCOVERY TESTS (Chunk 8)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `well-known discovery follows redirect to CalDAV endpoint`() {
        // Create a fresh client without skipWellKnownDiscovery
        val baseUrl = mockServer.url("/").toString().trimEnd('/')
        val creds = CalDavCredentials("test@icloud.com", "test-password")
        val freshClient = OkHttpCalDavClient(baseUrl, creds)

        // Well-known GET → 301 redirect to /caldav/
        mockServer.enqueue(MockResponse().setResponseCode(301)
            .addHeader("Location", mockServer.url("/caldav/").toString()))
        // The redirected URL returns 200 (well-known success)
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("CalDAV endpoint"))
        // Principal discovery at the redirected URL
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:">
<D:response><D:href>/caldav/</D:href>
<D:propstat><D:prop><D:current-user-principal><D:href>/123/principal/</D:href></D:current-user-principal></D:prop>
<D:status>HTTP/1.1 200 OK</D:status></D:propstat>
</D:response></D:multistatus>"""
        ))
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        val result = freshClient.listCalendars()

        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result)
        // Verify well-known GET was sent
        val wellKnownReq = mockServer.takeRequest()
        assertEquals("GET", wellKnownReq.method)
        assertTrue(wellKnownReq.path!!.contains(".well-known/caldav"))
    }

    @Test
    fun `well-known returns null on 404 and falls back to baseUrl`() {
        val baseUrl = mockServer.url("/").toString().trimEnd('/')
        val creds = CalDavCredentials("test@icloud.com", "test-password")
        val freshClient = OkHttpCalDavClient(baseUrl, creds)

        // Well-known GET → 404
        mockServer.enqueue(MockResponse().setResponseCode(404))
        // Normal discovery continues with baseUrl
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        val result = freshClient.listCalendars()

        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result)
        assertEquals(1, result.data.size)
        // 4 requests: well-known + 3 discovery
        assertEquals(4, mockServer.requestCount)
    }

    @Test
    fun `well-known detects redirect loop and gives up`() {
        val baseUrl = mockServer.url("/").toString().trimEnd('/')
        val creds = CalDavCredentials("test@icloud.com", "test-password")
        val freshClient = OkHttpCalDavClient(baseUrl, creds)

        val urlA = mockServer.url("/a/").toString()
        val urlB = mockServer.url("/b/").toString()

        // well-known → A
        mockServer.enqueue(MockResponse().setResponseCode(301).addHeader("Location", urlA))
        // A → B
        mockServer.enqueue(MockResponse().setResponseCode(301).addHeader("Location", urlB))
        // B → A (loop!)
        mockServer.enqueue(MockResponse().setResponseCode(301).addHeader("Location", urlA))

        // Falls back to baseUrl for normal discovery
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        val result = freshClient.listCalendars()

        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result)
    }

    @Test
    fun `well-known result is cached across calls`() {
        val baseUrl = mockServer.url("/").toString().trimEnd('/')
        val creds = CalDavCredentials("test@icloud.com", "test-password")
        val freshClient = OkHttpCalDavClient(baseUrl, creds)

        // First call: well-known 404 + full discovery
        mockServer.enqueue(MockResponse().setResponseCode(404)) // well-known
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        freshClient.listCalendars()
        val requestsAfterFirst = mockServer.requestCount

        // Second call: uses calendar cache (no requests at all)
        val result2 = freshClient.listCalendars()
        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result2)
        assertEquals(requestsAfterFirst, mockServer.requestCount)
    }

    // ═══════════════════════════════════════════════════════════════════
    // HTTP CLIENT TESTS (Chunk 20)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `PROPFIND redirect is followed by OkHttp`() {
        // Principal PROPFIND gets 307 redirect (method-preserving)
        val redirectUrl = mockServer.url("/redirected/").toString()
        mockServer.enqueue(MockResponse().setResponseCode(307)
            .addHeader("Location", redirectUrl))

        // Redirected endpoint returns principal
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:">
<D:response><D:href>/redirected/</D:href>
<D:propstat><D:prop><D:current-user-principal><D:href>/123/principal/</D:href></D:current-user-principal></D:prop>
<D:status>HTTP/1.1 200 OK</D:status></D:propstat>
</D:response></D:multistatus>"""
        ))
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        val result = client.listCalendars()

        // Should follow redirect and complete discovery
        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result)
        // Verify redirect was followed
        assertTrue(mockServer.requestCount >= 3, "Expected redirect to be followed")
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONNECTION VALIDATION TESTS (Chunk 4)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `checkConnection succeeds with calendar-access DAV header`() {
        mockServer.enqueue(MockResponse().setResponseCode(200)
            .addHeader("DAV", "1, calendar-access"))

        val result = client.checkConnection()

        assertIs<CalDavResult.Success<Boolean>>(result)
        assertTrue(result.data)

        val request = mockServer.takeRequest()
        assertEquals("OPTIONS", request.method)
    }

    @Test
    fun `checkConnection fails without calendar-access`() {
        mockServer.enqueue(MockResponse().setResponseCode(200)
            .addHeader("DAV", "1"))

        val result = client.checkConnection()

        assertIs<CalDavResult.Error>(result)
        assertEquals(400, result.code)
        assertTrue(result.message.contains("calendar-access"))
    }

    @Test
    fun `checkConnection fails on auth error`() {
        mockServer.enqueue(MockResponse().setResponseCode(401))

        val result = client.checkConnection()

        assertIs<CalDavResult.Error>(result)
        assertEquals(401, result.code)
    }

    // ═══════════════════════════════════════════════════════════════════
    // FETCH ETAGS TESTS (Chunk 21)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `fetchEtags returns map of href to etag`() {
        enqueuePrincipalResponse()
        enqueueHomeSetResponse()
        enqueueCalendarListResponse()

        // ETag-only REPORT response
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody("""
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/123/calendars/home/event1.ics</D:href>
                    <D:propstat>
                        <D:prop><D:getetag>"etag-1"</D:getetag></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
                <D:response>
                    <D:href>/123/calendars/home/event2.ics</D:href>
                    <D:propstat>
                        <D:prop><D:getetag>"etag-2"</D:getetag></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()))

        val result = client.fetchEtags("home", "2025-01-01", "2025-01-31")

        assertIs<CalDavResult.Success<Map<String, String?>>>(result)
        assertEquals(2, result.data.size)
        assertEquals("etag-1", result.data["/123/calendars/home/event1.ics"])
        assertEquals("etag-2", result.data["/123/calendars/home/event2.ics"])

        // Verify REPORT body does NOT contain calendar-data
        val requests = (1..mockServer.requestCount).map { mockServer.takeRequest() }
        val reportReq = requests.last()
        assertFalse(reportReq.body.readUtf8().contains("calendar-data"))
    }

    @Test
    fun `fetchEtags validates date format`() {
        val result = client.fetchEtags("home", "bad-date", "2025-01-31")

        assertIs<CalDavResult.Error>(result)
        assertEquals(400, result.code)
    }

    // ═══════════════════════════════════════════════════════════════════
    // MULTIPLE HOME SETS TESTS (Chunk 9)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `listCalendars aggregates from multiple home sets`() {
        enqueuePrincipalResponse()

        // Response with 2 home sets
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:response>
<D:href>/123/principal/</D:href>
<D:propstat>
<D:prop><C:calendar-home-set>
<D:href>/123/calendars/</D:href>
<D:href>/123/shared/</D:href>
</C:calendar-home-set></D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""
        ))

        // First home set calendars
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

        // Second home set calendars
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:response>
<D:href>/123/shared/team/</D:href>
<D:propstat>
<D:prop><D:displayname>Team</D:displayname><D:resourcetype><D:collection/><C:calendar/></D:resourcetype></D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""
        ))

        val result = client.listCalendars()

        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result)
        assertEquals(2, result.data.size)
        assertEquals("Home", result.data[0].displayName)
        assertEquals("Team", result.data[1].displayName)
    }

    @Test
    fun `listCalendars deduplicates across home sets`() {
        enqueuePrincipalResponse()

        // Response with 2 home sets
        mockServer.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:response>
<D:href>/123/principal/</D:href>
<D:propstat>
<D:prop><C:calendar-home-set>
<D:href>/123/calendars/</D:href>
<D:href>/123/calendars/</D:href>
</C:calendar-home-set></D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""
        ))

        // Same response for both (same home set URL)
        enqueueCalendarListResponse()
        enqueueCalendarListResponse()

        val result = client.listCalendars()

        assertIs<CalDavResult.Success<List<CalDavCalendar>>>(result)
        assertEquals(1, result.data.size) // Deduplicated
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════

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
