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
import org.onekash.mcp.calendar.service.CalendarInfo
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.EventInfo
import org.onekash.mcp.calendar.service.ServiceResult
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for MCP structured tool output.
 *
 * A tool that declares an outputSchema MUST also return structuredContent on
 * success — strict clients reject the response with
 * "-32600: Tool X has an output schema but did not return structured content"
 * when only the content text is present.
 *
 * Error responses (isError = true) are exempt, so only success paths are covered.
 */
class StructuredContentTest {

    private lateinit var server: Server
    private lateinit var service: CalendarService

    private val calendarId = "cal-123"
    private val eventId = "event-456"

    private val sampleCalendar = CalendarInfo(
        id = calendarId,
        name = "Privat",
        color = "#FF0000",
        readOnly = false,
        supportedComponents = setOf("VEVENT")
    )

    private val sampleEvent = EventInfo(
        uid = eventId,
        href = "/calendars/$calendarId/$eventId.ics",
        etag = "\"etag-1\"",
        summary = "Team Sync",
        description = "Weekly",
        location = "Room 1",
        isAllDay = false,
        startTime = "2026-01-15T09:00:00",
        endTime = "2026-01-15T10:00:00",
        startDate = null,
        endDate = null,
        rrule = null
    )

    @BeforeEach
    fun setUp() {
        server = Server(
            serverInfo = Implementation(name = "test-server", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
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

    /**
     * Asserts the result carries structuredContent that satisfies the tool's own
     * outputSchema: every required property present, with the declared JSON type.
     */
    private fun assertMatchesOutputSchema(name: String, result: CallToolResult) {
        assertEquals(null, result.isError, "$name should have succeeded, got: ${result.content}")
        val structured = result.structuredContent
        assertNotNull(structured, "$name must return structuredContent alongside content")

        val schema = server.tools[name]!!.tool.outputSchema!!
        val properties = schema.properties!!.jsonObject

        schema.required!!.forEach { field ->
            assertTrue(structured.containsKey(field), "$name structuredContent should contain '$field'")
        }

        structured.forEach { (key, value) ->
            when (properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content) {
                "array" -> assertTrue(value is JsonArray, "$name field '$key' should be an array")
                "object" -> assertTrue(value is JsonObject, "$name field '$key' should be an object")
                "boolean" -> assertNotNull(
                    (value as? JsonPrimitive)?.booleanOrNull,
                    "$name field '$key' should be a boolean"
                )
                "string" -> assertTrue(
                    value is JsonPrimitive && value.isString,
                    "$name field '$key' should be a string"
                )
                else -> {} // property not declared in the schema, or untyped
            }
        }
    }

    /** structuredContent must carry the same payload the text content does. */
    private fun assertMirrorsTextContent(name: String, result: CallToolResult) {
        val text = (result.content.first() as TextContent).text!!
        assertEquals(
            Json.parseToJsonElement(text).jsonObject,
            result.structuredContent,
            "$name structuredContent should mirror the text content"
        )
    }

    @Test
    fun `list_calendars returns structuredContent matching its outputSchema`() {
        every { service.listCalendars() } returns ServiceResult.Success(listOf(sampleCalendar))

        val result = callTool("list_calendars", buildJsonObject { })

        assertMatchesOutputSchema("list_calendars", result)
        assertMirrorsTextContent("list_calendars", result)
        assertEquals(1, result.structuredContent!!["calendars"]!!.jsonArray.size)
    }

    @Test
    fun `get_events returns structuredContent matching its outputSchema`() {
        every { service.getEvents(any(), any(), any()) } returns ServiceResult.Success(listOf(sampleEvent))

        val result = callTool("get_events", buildJsonObject {
            put("calendar_id", calendarId)
            put("start_date", "2026-01-01")
            put("end_date", "2026-01-31")
        })

        assertMatchesOutputSchema("get_events", result)
        assertMirrorsTextContent("get_events", result)
        assertEquals(1, result.structuredContent!!["events"]!!.jsonArray.size)
    }

    @Test
    fun `create_event returns structuredContent matching its outputSchema`() {
        every {
            service.createEvent(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()
            )
        } returns ServiceResult.Success(sampleEvent)

        val result = callTool("create_event", buildJsonObject {
            put("calendar_id", calendarId)
            put("title", "Team Sync")
            put("start_time", "2026-01-15T09:00:00")
            put("end_time", "2026-01-15T10:00:00")
        })

        assertMatchesOutputSchema("create_event", result)
        assertMirrorsTextContent("create_event", result)
        assertEquals(eventId, result.structuredContent!!["uid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create_event accepts summary as an alias for title`() {
        // get_events and the create response echo the field back as `summary`, so a
        // caller that round-trips a result must be able to recreate with `summary`.
        val titleSlot = slot<String>()
        every {
            service.createEvent(
                any(), capture(titleSlot), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()
            )
        } returns ServiceResult.Success(sampleEvent)

        val result = callTool("create_event", buildJsonObject {
            put("calendar_id", calendarId)
            put("summary", "Aliased Title")
            put("start_time", "2026-01-15T09:00:00")
            put("end_time", "2026-01-15T10:00:00")
        })

        assertEquals(null, result.isError, "summary-only create should succeed, got: ${result.content}")
        assertEquals("Aliased Title", titleSlot.captured, "summary should flow through as the event title")
    }

    @Test
    fun `update_event returns structuredContent matching its outputSchema`() {
        every {
            service.updateEvent(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()
            )
        } returns ServiceResult.Success(sampleEvent)

        val result = callTool("update_event", buildJsonObject {
            put("event_id", eventId)
            put("title", "Team Sync (moved)")
        })

        assertMatchesOutputSchema("update_event", result)
        assertMirrorsTextContent("update_event", result)
        assertEquals(eventId, result.structuredContent!!["uid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete_event returns structuredContent matching its outputSchema`() {
        every { service.deleteEvent(any()) } returns ServiceResult.Success(Unit)

        val result = callTool("delete_event", buildJsonObject {
            put("event_id", eventId)
        })

        assertMatchesOutputSchema("delete_event", result)
        assertMirrorsTextContent("delete_event", result)
        assertEquals(true, result.structuredContent!!["success"]!!.jsonPrimitive.boolean)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Negative paths: error responses are exempt from the structuredContent
    // requirement. They MUST set isError = true and MUST NOT carry
    // structuredContent — attaching it to an isError response would be the
    // mirror-image bug (a schema-less object on a response the client treats
    // as an error). These tests lock that invariant in.
    // ─────────────────────────────────────────────────────────────────────

    /** An error result must flag isError and omit structuredContent entirely. */
    private fun assertErrorHasNoStructuredContent(name: String, result: CallToolResult) {
        assertEquals(true, result.isError, "$name error path should set isError = true")
        assertEquals(
            null,
            result.structuredContent,
            "$name error response must not carry structuredContent"
        )
    }

    @Test
    fun `service error paths omit structuredContent and set isError`() {
        every { service.listCalendars() } returns ServiceResult.Error(500, "boom")
        every { service.getEvents(any(), any(), any()) } returns ServiceResult.Error(404, "no calendar")
        every {
            service.createEvent(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()
            )
        } returns ServiceResult.Error(403, "read only")
        every {
            service.updateEvent(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()
            )
        } returns ServiceResult.Error(404, "not found")
        every { service.deleteEvent(any()) } returns ServiceResult.Error(404, "not found")

        assertErrorHasNoStructuredContent("list_calendars", callTool("list_calendars", buildJsonObject { }))
        assertErrorHasNoStructuredContent("get_events", callTool("get_events", buildJsonObject {
            put("calendar_id", calendarId)
            put("start_date", "2026-01-01")
            put("end_date", "2026-01-31")
        }))
        assertErrorHasNoStructuredContent("create_event", callTool("create_event", buildJsonObject {
            put("calendar_id", calendarId)
            put("title", "Team Sync")
            put("start_time", "2026-01-15T09:00:00")
            put("end_time", "2026-01-15T10:00:00")
        }))
        assertErrorHasNoStructuredContent("update_event", callTool("update_event", buildJsonObject {
            put("event_id", eventId)
            put("title", "Renamed")
        }))
        assertErrorHasNoStructuredContent("delete_event", callTool("delete_event", buildJsonObject {
            put("event_id", eventId)
        }))
    }

    @Test
    fun `validation error paths omit structuredContent and set isError`() {
        // Malformed inputs are rejected before the service is ever called.
        assertErrorHasNoStructuredContent("get_events", callTool("get_events", buildJsonObject {
            put("calendar_id", calendarId)
            put("start_date", "not-a-date")
            put("end_date", "also-not-a-date")
        }))
        assertErrorHasNoStructuredContent("delete_event", callTool("delete_event", buildJsonObject {
            put("event_id", "")
        }))
    }
}
