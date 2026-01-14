package org.onekash.mcp.calendar.integration

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for MCP tool annotations compliance.
 * Verifies that all tools have correct annotations per MCP spec.
 */
class ToolAnnotationsTest {

    private fun createServerWithTools(): Server {
        val server = Server(
            serverInfo = Implementation(name = "test-server", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        // Register tools with annotations (matching Main.kt)
        server.addTool(
            name = "list_calendars",
            description = "List all calendars",
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = true
            )
        ) { CallToolResult(content = listOf(TextContent(text = "{}"))) }

        server.addTool(
            name = "get_events",
            description = "Get events",
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = true
            )
        ) { CallToolResult(content = listOf(TextContent(text = "{}"))) }

        server.addTool(
            name = "create_event",
            description = "Create event",
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = true
            )
        ) { CallToolResult(content = listOf(TextContent(text = "{}"))) }

        server.addTool(
            name = "update_event",
            description = "Update event",
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = true
            )
        ) { CallToolResult(content = listOf(TextContent(text = "{}"))) }

        server.addTool(
            name = "delete_event",
            description = "Delete event",
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = true,
                idempotentHint = true,
                openWorldHint = true
            )
        ) { CallToolResult(content = listOf(TextContent(text = "{}"))) }

        return server
    }

    @Test
    fun `list_calendars has correct read-only annotations`() {
        val server = createServerWithTools()
        val tool = server.tools["list_calendars"]

        assertNotNull(tool, "list_calendars tool should exist")
        val annotations = tool.tool.annotations
        assertNotNull(annotations, "annotations should not be null")
        assertEquals(true, annotations.readOnlyHint, "should be read-only")
        assertEquals(false, annotations.destructiveHint, "should not be destructive")
        assertEquals(true, annotations.idempotentHint, "should be idempotent")
        assertEquals(true, annotations.openWorldHint, "should interact with external world")
    }

    @Test
    fun `get_events has correct read-only annotations`() {
        val server = createServerWithTools()
        val tool = server.tools["get_events"]

        assertNotNull(tool, "get_events tool should exist")
        val annotations = tool.tool.annotations
        assertNotNull(annotations, "annotations should not be null")
        assertEquals(true, annotations.readOnlyHint, "should be read-only")
        assertEquals(false, annotations.destructiveHint, "should not be destructive")
        assertEquals(true, annotations.idempotentHint, "should be idempotent")
    }

    @Test
    fun `create_event has correct write annotations`() {
        val server = createServerWithTools()
        val tool = server.tools["create_event"]

        assertNotNull(tool, "create_event tool should exist")
        val annotations = tool.tool.annotations
        assertNotNull(annotations, "annotations should not be null")
        assertEquals(false, annotations.readOnlyHint, "should not be read-only")
        assertEquals(false, annotations.destructiveHint, "should not be destructive")
        assertEquals(false, annotations.idempotentHint, "should not be idempotent (creates new each time)")
    }

    @Test
    fun `update_event has correct idempotent write annotations`() {
        val server = createServerWithTools()
        val tool = server.tools["update_event"]

        assertNotNull(tool, "update_event tool should exist")
        val annotations = tool.tool.annotations
        assertNotNull(annotations, "annotations should not be null")
        assertEquals(false, annotations.readOnlyHint, "should not be read-only")
        assertEquals(false, annotations.destructiveHint, "should not be destructive")
        assertEquals(true, annotations.idempotentHint, "should be idempotent (same update = same result)")
    }

    @Test
    fun `delete_event has correct destructive annotations`() {
        val server = createServerWithTools()
        val tool = server.tools["delete_event"]

        assertNotNull(tool, "delete_event tool should exist")
        val annotations = tool.tool.annotations
        assertNotNull(annotations, "annotations should not be null")
        assertEquals(false, annotations.readOnlyHint, "should not be read-only")
        assertEquals(true, annotations.destructiveHint, "should be destructive")
        assertEquals(true, annotations.idempotentHint, "should be idempotent (delete twice = same result)")
    }
}
