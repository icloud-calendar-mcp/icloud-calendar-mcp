package org.onekash.mcp.calendar.integration

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.onekash.mcp.calendar.logging.McpLogger
import org.onekash.mcp.calendar.registerTools
import org.onekash.mcp.calendar.service.EventInfo
import org.onekash.mcp.calendar.service.ServiceResult
import org.onekash.mcp.calendar.validation.EventScope
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MCP-layer wiring for scoped writes and occurrence identity (C7):
 *  - get_events emits `recurrenceId` per event when the service reports one;
 *  - update_event / delete_event accept and validate a `scope` enum and thread it
 *    to the service;
 *  - an invalid scope, or series-level fields under an occurrence scope, are
 *    rejected at the MCP layer before any service call.
 */
class ScopeMcpWiringTest {

    private lateinit var server: Server
    private lateinit var service: org.onekash.mcp.calendar.service.CalendarService

    @BeforeEach
    fun setUp() {
        server = Server(
            serverInfo = Implementation(name = "test-server", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
            )
        )
        service = mockk()
        registerTools(server, service, McpLogger(server))
    }

    private fun callTool(name: String, args: JsonObject): CallToolResult = runBlocking {
        val registered = server.tools[name] ?: error("tool $name not registered")
        val connection = mockk<ClientConnection>(relaxed = true)
        registered.handler(connection, CallToolRequest(CallToolRequestParams(name = name, arguments = args)))
    }

    private fun textOf(result: CallToolResult): String = (result.content.first() as TextContent).text

    private fun occurrenceEvent() = EventInfo(
        uid = "series-1",
        href = "/cal/series.ics",
        etag = "\"e1\"",
        handle = "evt2_handle",
        summary = "Standup",
        description = null,
        location = null,
        isAllDay = false,
        startTime = "2026-01-15T09:00:00Z",
        endTime = "2026-01-15T09:15:00Z",
        startDate = null,
        endDate = null,
        rrule = "FREQ=DAILY",
        recurrenceId = "20260115T090000Z"
    )

    private fun standaloneEvent() = EventInfo(
        uid = "single-1",
        href = "/cal/single.ics",
        etag = "\"e2\"",
        handle = "evt1_handle",
        summary = "One-off",
        description = null,
        location = null,
        isAllDay = false,
        startTime = "2026-01-15T10:00:00Z",
        endTime = "2026-01-15T11:00:00Z",
        startDate = null,
        endDate = null,
        rrule = null,
        recurrenceId = null
    )

    // ── get_events emits recurrenceId ────────────────────────────────────────

    @Test
    fun `get_events emits recurrenceId for a series occurrence and omits it for a standalone`() {
        every { service.getEvents(any(), any(), any()) } returns
            ServiceResult.Success(listOf(occurrenceEvent(), standaloneEvent()))

        val result = callTool("get_events", buildJsonObject {
            put("calendar_id", "cal-1")
            put("start_date", "2026-01-15")
            put("end_date", "2026-01-15")
        })

        assertNull(result.isError, "get_events should succeed: ${textOf(result)}")
        val events = Json.parseToJsonElement(textOf(result)).jsonObject["events"]!!.jsonArray
        val occurrence = events[0].jsonObject
        val standalone = events[1].jsonObject
        assertEquals("20260115T090000Z", occurrence["recurrenceId"]?.jsonPrimitive?.content)
        assertEquals("FREQ=DAILY", occurrence["rrule"]?.jsonPrimitive?.content, "rrule still emitted")
        assertFalse(standalone.containsKey("recurrenceId"), "standalone event carries no recurrenceId")
    }

    // ── scope validation at the MCP layer ────────────────────────────────────

    @Test
    fun `update_event rejects an invalid scope before calling the service`() {
        val result = callTool("update_event", buildJsonObject {
            put("event_id", "evt2_handle")
            put("title", "X")
            put("scope", "the_whole_universe")
        })

        assertEquals(true, result.isError)
        assertTrue(textOf(result).contains("this_occurrence"), "error lists the allowed scopes: ${textOf(result)}")
    }

    @Test
    fun `update_event rejects series-level fields under an occurrence scope before calling the service`() {
        val result = callTool("update_event", buildJsonObject {
            put("event_id", "evt2_handle")
            put("rrule", "FREQ=WEEKLY")
            put("scope", "this_occurrence")
        })

        assertEquals(true, result.isError)
        assertTrue(textOf(result).contains("rrule"), "error names the offending field: ${textOf(result)}")
    }

    @Test
    fun `delete_event rejects an invalid scope before calling the service`() {
        val result = callTool("delete_event", buildJsonObject {
            put("event_id", "evt2_handle")
            put("scope", "nope")
        })

        assertEquals(true, result.isError)
        assertTrue(textOf(result).contains("this_occurrence"), "error lists the allowed scopes: ${textOf(result)}")
    }

    // ── scope threaded to the service ────────────────────────────────────────

    @Test
    fun `update_event threads the parsed scope to the service`() {
        val scopeSlot = slot<EventScope?>()
        every {
            service.updateEvent(
                eventId = any(), summary = any(), startTime = any(), endTime = any(),
                startDate = any(), endDate = any(), isAllDay = any(), description = any(),
                location = any(), timezone = any(), rrule = any(), endTimezone = any(),
                rdates = any(), exdates = any(), alarms = any(), scope = captureNullable(scopeSlot)
            )
        } returns ServiceResult.Success(occurrenceEvent())

        callTool("update_event", buildJsonObject {
            put("event_id", "evt2_handle")
            put("title", "Renamed")
            put("scope", "this_occurrence")
        })

        assertEquals(EventScope.THIS_OCCURRENCE, scopeSlot.captured)
    }

    @Test
    fun `delete_event threads the parsed scope to the service`() {
        val scopeSlot = slot<EventScope?>()
        every { service.deleteEvent(any(), captureNullable(scopeSlot)) } returns ServiceResult.Success(Unit)

        callTool("delete_event", buildJsonObject {
            put("event_id", "evt2_handle")
            put("scope", "all_events")
        })

        assertEquals(EventScope.ALL_EVENTS, scopeSlot.captured)
    }
}
