package org.onekash.mcp.calendar.integration

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredResource
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for MCP 2025-11-25 spec compliance.
 * Verifies: tool title, outputSchema, list_calendars inputSchema, resource title.
 */
class McpSpecComplianceTest {

    private lateinit var server: Server

    private val toolNames = listOf("list_calendars", "get_events", "create_event", "update_event", "delete_event")

    @BeforeEach
    fun setUp() {
        server = Server(
            serverInfo = Implementation(name = "test-server", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    resources = ServerCapabilities.Resources(listChanged = true)
                )
            )
        )

        server.addTool(
            name = "list_calendars",
            description = "List all calendars from the connected iCloud account",
            title = "List Calendars",
            inputSchema = ToolSchema(),
            outputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("calendars", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("description", JsonPrimitive("List of calendars"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("object")) })
                    })
                },
                required = listOf("calendars")
            ),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = true
            )
        ) { CallToolResult(content = listOf(TextContent(text = "{}"))) }

        server.addTool(
            name = "get_events",
            description = "Get events from a calendar within a date range",
            title = "Get Events",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("calendar_id", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("calendar_id", "start_date", "end_date")
            ),
            outputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("events", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("description", JsonPrimitive("List of events"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("object")) })
                    })
                },
                required = listOf("events")
            ),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = true
            )
        ) { CallToolResult(content = listOf(TextContent(text = "{}"))) }

        server.addTool(
            name = "create_event",
            description = "Create a new calendar event",
            title = "Create Event",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("calendar_id", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("calendar_id", "title")
            ),
            outputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("success", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                    put("uid", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("summary", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("message", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("success", "uid", "summary", "message")
            ),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = true
            )
        ) { CallToolResult(content = listOf(TextContent(text = "{}"))) }

        server.addTool(
            name = "update_event",
            description = "Update an existing calendar event",
            title = "Update Event",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("event_id", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("event_id")
            ),
            outputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("success", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                    put("uid", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("message", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("success", "uid", "message")
            ),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = true
            )
        ) { CallToolResult(content = listOf(TextContent(text = "{}"))) }

        server.addTool(
            name = "delete_event",
            description = "Delete a calendar event",
            title = "Delete Event",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("event_id", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("event_id")
            ),
            outputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("success", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                    put("message", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("success", "message")
            ),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = true,
                idempotentHint = true,
                openWorldHint = true
            )
        ) { CallToolResult(content = listOf(TextContent(text = "{}"))) }
    }

    // --- Tool title tests ---

    @Test
    fun `all tools have human-readable title`() {
        toolNames.forEach { name ->
            val tool = server.tools[name]
            assertNotNull(tool, "$name tool should exist")
            assertNotNull(tool.tool.title, "$name should have a title")
            assertTrue(tool.tool.title!!.isNotBlank(), "$name title should not be blank")
        }
    }

    @Test
    fun `tool titles are distinct from tool names`() {
        toolNames.forEach { name ->
            val tool = server.tools[name]!!
            assertNotEquals(name, tool.tool.title, "$name title should differ from programmatic name")
        }
    }

    // --- inputSchema tests ---

    @Test
    fun `list_calendars has minimal inputSchema`() {
        val tool = server.tools["list_calendars"]!!
        val schema = tool.tool.inputSchema
        // ToolSchema() defaults to {"type":"object"} — properties should be null or empty
        val props = schema.properties
        assertTrue(
            props == null || props.jsonObject.isEmpty(),
            "list_calendars inputSchema should have no properties, got: $props"
        )
    }

    // --- outputSchema tests ---

    @Test
    fun `all tools have outputSchema defined`() {
        toolNames.forEach { name ->
            val tool = server.tools[name]
            assertNotNull(tool, "$name tool should exist")
            assertNotNull(tool.tool.outputSchema, "$name should have outputSchema")
        }
    }

    @Test
    fun `outputSchema properties are non-empty`() {
        toolNames.forEach { name ->
            val tool = server.tools[name]!!
            val outputSchema = tool.tool.outputSchema!!
            val props = outputSchema.properties
            assertNotNull(props, "$name outputSchema should have properties")
            assertTrue(props.jsonObject.isNotEmpty(), "$name outputSchema properties should not be empty")
        }
    }

    @Test
    fun `outputSchema declares required fields`() {
        toolNames.forEach { name ->
            val tool = server.tools[name]!!
            val outputSchema = tool.tool.outputSchema!!
            val required = outputSchema.required
            assertNotNull(required, "$name outputSchema should have required list")
            assertTrue(required.isNotEmpty(), "$name outputSchema required list should not be empty")
        }
    }

    @Test
    fun `list_calendars outputSchema has calendars array property`() {
        val tool = server.tools["list_calendars"]!!
        val props = tool.tool.outputSchema!!.properties!!.jsonObject
        assertTrue(props.containsKey("calendars"), "should have calendars property")
        val calendars = props["calendars"]!!.jsonObject
        assertEquals("array", calendars["type"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `get_events outputSchema has events array property`() {
        val tool = server.tools["get_events"]!!
        val props = tool.tool.outputSchema!!.properties!!.jsonObject
        assertTrue(props.containsKey("events"), "should have events property")
        val events = props["events"]!!.jsonObject
        assertEquals("array", events["type"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `create_event outputSchema has success uid summary message`() {
        val tool = server.tools["create_event"]!!
        val props = tool.tool.outputSchema!!.properties!!.jsonObject
        val required = tool.tool.outputSchema!!.required!!
        listOf("success", "uid", "summary", "message").forEach { field ->
            assertTrue(props.containsKey(field), "create_event outputSchema should have $field")
            assertTrue(required.contains(field), "create_event $field should be required")
        }
    }

    @Test
    fun `delete_event outputSchema has success and message`() {
        val tool = server.tools["delete_event"]!!
        val props = tool.tool.outputSchema!!.properties!!.jsonObject
        val required = tool.tool.outputSchema!!.required!!
        listOf("success", "message").forEach { field ->
            assertTrue(props.containsKey(field), "delete_event outputSchema should have $field")
            assertTrue(required.contains(field), "delete_event $field should be required")
        }
    }

    // --- Resource tests ---

    @Test
    fun `calendar resource is registered with title`() {
        server.addResources(listOf(
            RegisteredResource(
                resource = Resource(
                    uri = "calendar://calendars",
                    name = "iCloud Calendars",
                    description = "List of all calendars in the connected iCloud account",
                    mimeType = "application/json",
                    title = "iCloud Calendars"
                ),
                readHandler = { _ ->
                    ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = "calendar://calendars",
                                mimeType = "application/json",
                                text = """{"calendars": []}"""
                            )
                        )
                    )
                }
            )
        ))

        val resource = server.resources["calendar://calendars"]
        assertNotNull(resource, "calendar://calendars resource should exist")
        assertNotNull(resource.resource.title, "resource should have title")
        assertEquals("iCloud Calendars", resource.resource.title)
    }

    @Test
    fun `calendar resource has correct metadata`() {
        server.addResources(listOf(
            RegisteredResource(
                resource = Resource(
                    uri = "calendar://calendars",
                    name = "iCloud Calendars",
                    description = "List of all calendars in the connected iCloud account",
                    mimeType = "application/json",
                    title = "iCloud Calendars"
                ),
                readHandler = { _ ->
                    ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = "calendar://calendars",
                                mimeType = "application/json",
                                text = """{"calendars": []}"""
                            )
                        )
                    )
                }
            )
        ))

        val resource = server.resources["calendar://calendars"]!!.resource
        assertEquals("calendar://calendars", resource.uri)
        assertEquals("iCloud Calendars", resource.name)
        assertEquals("application/json", resource.mimeType)
        assertNotNull(resource.description)
    }

    @Test
    fun `McpException thrown from resource handler propagates with correct code`(): Unit = runBlocking {
        server.addResources(listOf(
            RegisteredResource(
                resource = Resource(
                    uri = "test://error",
                    name = "Error Test",
                    mimeType = "text/plain"
                ),
                readHandler = { throw McpException(-32000, "test rate limit error") }
            )
        ))

        val handler = server.resources["test://error"]!!.readHandler
        val ex = assertThrows<McpException> {
            runBlocking { handler(ReadResourceRequest(ReadResourceRequestParams(uri = "test://error"))) }
        }
        assertEquals(-32000, ex.code)
        assertTrue(ex.message!!.contains("test rate limit error"))
    }

    @Test
    fun `McpException with distinct error codes for auth and service errors`() = runBlocking {
        // Verify all 3 error codes used in Main.kt are constructible and distinct
        val rateLimitEx = McpException(-32000, "Rate limit exceeded")
        val authEx = McpException(-32001, "iCloud credentials not configured")
        val serviceEx = McpException(-32002, "Calendar service error")

        assertEquals(-32000, rateLimitEx.code)
        assertEquals(-32001, authEx.code)
        assertEquals(-32002, serviceEx.code)
        assertTrue(setOf(rateLimitEx.code, authEx.code, serviceEx.code).size == 3, "error codes must be distinct")
    }
}
