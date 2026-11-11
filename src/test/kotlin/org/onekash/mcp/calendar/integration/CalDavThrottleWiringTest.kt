package org.onekash.mcp.calendar.integration

import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.onekash.mcp.calendar.caldav.*
import org.onekash.mcp.calendar.error.SecureErrorHandler
import org.onekash.mcp.calendar.logging.McpLogger
import org.onekash.mcp.calendar.registerTools
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.EventHandle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class CalDavThrottleWiringTest {
    @Test
    fun `list_calendars carries the shared throttle deadline and never caches the error`() {
        MockWebServer().use { http ->
            val clock = CalDavTestClock(Instant.now())
            val deadline = clock.instant().plusSeconds(1595)
            var throttle = true
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "OPTIONS" -> MockResponse().addHeader("DAV", "calendar-access")
                    request.path == "/" -> xml(
                        "<D:current-user-principal><D:href>/p/</D:href></D:current-user-principal>")
                    request.path == "/p/" -> xml("<C:calendar-home-set><D:href>/cal/</D:href></C:calendar-home-set>")
                    request.path == "/cal/" && throttle ->
                        MockResponse().setResponseCode(503).addHeader("Retry-After", "1595")
                    request.path == "/cal/" -> MockResponse().setResponseCode(207).setBody("""
                        <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                        <D:response><D:href>/cal/home/</D:href><D:propstat><D:prop>
                        <D:displayname>Home</D:displayname><D:resourcetype><C:calendar/></D:resourcetype>
                        </D:prop></D:propstat></D:response></D:multistatus>
                    """.trimIndent())
                    else -> MockResponse().setResponseCode(404)
                }
            }
            OkHttpCalDavClient(http.url("/").toString().trimEnd('/'),
                CalDavCredentials("test", "password"), clock = clock, sleep = clock::sleep).use { client ->
                client.skipWellKnownDiscovery()
                val server = Server(Implementation("test", "1"), ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))))
                registerTools(server, CalendarService(client), McpLogger(server))
                fun call(
                    name: String = "list_calendars",
                    args: JsonObject = buildJsonObject {}
                ): CallToolResult = runBlocking {
                    server.tools[name]!!.handler(mockk<ClientConnection>(relaxed = true),
                        CallToolRequest(CallToolRequestParams(name, args)))
                }
                repeat(2) {
                    val result = call()
                    assertEquals(true, result.isError)
                    val error = Json.parseToJsonElement((result.content.first() as TextContent).text)
                        .jsonObject["error"]!!.jsonObject
                    assertEquals("RATE_LIMITED", error["code"]!!.jsonPrimitive.content)
                    assertEquals(deadline.toString(), error["retryAt"]!!.jsonPrimitive.content)
                    assertTrue(error["retryAfterSeconds"]!!.jsonPrimitive.long in 1..1595)
                    assertEquals(4, http.requestCount)
                }
                val handle = EventHandle.encode("/cal/home/event.ics", "etag")
                for ((name, args) in listOf(
                    "get_events" to buildJsonObject {
                        put("calendar_id", "home"); put("start_date", "2027-01-15"); put("end_date", "2027-01-16")
                    },
                    "create_event" to buildJsonObject {
                        put("calendar_id", "home"); put("title", "Test")
                        put("start_time", "2027-01-15T13:00:00Z"); put("end_time", "2027-01-15T14:00:00Z")
                    },
                    "update_event" to buildJsonObject { put("event_id", handle); put("title", "Changed") },
                    "delete_event" to buildJsonObject { put("event_id", handle) }
                )) {
                    val result = call(name, args)
                    assertEquals(true, result.isError, name)
                    val error = Json.parseToJsonElement((result.content.first() as TextContent).text)
                        .jsonObject["error"]!!.jsonObject
                    assertEquals("RATE_LIMITED", error["code"]!!.jsonPrimitive.content, name)
                    assertEquals(deadline.toString(), error["retryAt"]!!.jsonPrimitive.content, name)
                    assertTrue(error["retryAfterSeconds"]!!.jsonPrimitive.long >= 1, name)
                    assertEquals(4, http.requestCount, name)
                }
                throttle = false
                clock.advance(1595)
                val result = call()
                assertNotEquals(true, result.isError)
                assertTrue((result.content.first() as TextContent).text.contains("Home"))
                assertEquals(7, http.requestCount)
            }
        }
    }

    private fun xml(properties: String) = MockResponse().setResponseCode(207).setBody("""
        <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
        <D:response><D:propstat><D:prop>$properties</D:prop></D:propstat></D:response></D:multistatus>
    """.trimIndent())

    @Test
    fun `error deadlines use UTC and round remaining seconds up with a floor of one`() {
        val now = Instant.parse("2026-11-10T12:00:00Z")
        for ((code, deadline, expected) in listOf(
            Triple(429, now.plusMillis(1595001), 1596L),
            Triple(503, now.plusSeconds(30), 30L),
            Triple(429, now.minusSeconds(1), 1L)
        )) {
            val result = SecureErrorHandler.serviceError(code, "Calendar temporarily unavailable", deadline,
                Clock.fixed(now, ZoneOffset.UTC))
            val error = Json.parseToJsonElement((result.content.first() as TextContent).text)
                .jsonObject["error"]!!.jsonObject
            assertEquals(if (code == 429) "RATE_LIMITED" else "CALDAV_ERROR", error["code"]!!.jsonPrimitive.content)
            assertEquals(deadline.toString(), error["retryAt"]!!.jsonPrimitive.content)
            assertEquals(expected, error["retryAfterSeconds"]!!.jsonPrimitive.long)
        }
    }
}
