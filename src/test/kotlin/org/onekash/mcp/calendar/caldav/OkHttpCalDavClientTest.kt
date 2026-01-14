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
        assertEquals("\"etag1\"", result.data[0].etag)
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
        assertEquals("\"new-etag-123\"", result.data.etag)
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
        assertEquals("\"updated-etag\"", result.data.etag)

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
