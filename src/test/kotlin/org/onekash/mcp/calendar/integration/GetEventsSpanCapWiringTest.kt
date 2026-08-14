package org.onekash.mcp.calendar.integration

import io.mockk.mockk
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MCP-layer wiring for the get_events span cap (US1): an over-wide (> 366 days) or
 * inverted (end before start) range must be rejected by the handler's validation block
 * BEFORE any service call. The service is a strict mock left unstubbed, so if the
 * validateDateSpan wire were removed the handler would call service.getEvents, the mock
 * would throw, and the error text would be a generic failure rather than the span
 * message. That divergence is what proves the wire is present.
 */
class GetEventsSpanCapWiringTest {

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

    @Test
    fun `get_events rejects an over-wide range before calling the service`() {
        val result = callTool("get_events", buildJsonObject {
            put("calendar_id", "cal-1")
            put("start_date", "2026-01-01")
            put("end_date", "2027-06-01") // 516 days, past the 366-day cap
        })

        assertEquals(true, result.isError)
        assertTrue(
            textOf(result).contains("exceeds the maximum"),
            "error should name the span cap, not a downstream failure: ${textOf(result)}"
        )
    }

    @Test
    fun `get_events rejects an inverted range before calling the service`() {
        val result = callTool("get_events", buildJsonObject {
            put("calendar_id", "cal-1")
            put("start_date", "2026-06-01")
            put("end_date", "2026-05-01") // end precedes start
        })

        assertEquals(true, result.isError)
        assertTrue(
            textOf(result).contains("must not precede"),
            "error should name the inverted range, not a downstream failure: ${textOf(result)}"
        )
    }
}
