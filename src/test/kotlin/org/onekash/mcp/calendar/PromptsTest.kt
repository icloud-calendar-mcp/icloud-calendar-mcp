package org.onekash.mcp.calendar

import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hermetic tests for the user-initiated MCP prompts.
 *
 * Prompts are UX templates a client surfaces in a menu — they are NOT consulted
 * by the model during tool use. Each prompt, given its arguments, must expand to
 * a GetPromptResult whose message steers the client through the correct tool
 * workflow (create_event / get_events -> update_event handle round-trip /
 * get_events + conflict reasoning). These tests exercise the registered
 * message providers directly, mirroring StructuredContentTest's harness.
 */
class PromptsTest {

    private lateinit var server: Server

    @BeforeEach
    fun setUp() {
        server = Server(
            serverInfo = Implementation(name = "test-server", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    prompts = ServerCapabilities.Prompts(listChanged = false)
                )
            )
        )
        registerPrompts(server)
    }

    private fun getPrompt(name: String, args: Map<String, String>): GetPromptResult = runBlocking {
        val registered = server.prompts[name] ?: error("prompt $name not registered")
        val connection = mockk<ClientConnection>(relaxed = true)
        registered.messageProvider(
            connection,
            GetPromptRequest(GetPromptRequestParams(name = name, arguments = args))
        )
    }

    /** Concatenated text of every message in the result (all are TextContent). */
    private fun GetPromptResult.text(): String =
        messages.joinToString("\n") { (it.content as TextContent).text ?: "" }

    @Test
    fun `all three prompts are registered`() {
        assertTrue(server.prompts.containsKey("schedule_meeting"), "schedule_meeting registered")
        assertTrue(server.prompts.containsKey("reschedule"), "reschedule registered")
        assertTrue(server.prompts.containsKey("find_conflicts"), "find_conflicts registered")
    }

    @Test
    fun `schedule_meeting declares its expected arguments`() {
        val prompt = server.prompts["schedule_meeting"]!!.prompt
        val argNames = prompt.arguments?.map { it.name }?.toSet() ?: emptySet()
        assertTrue(argNames.containsAll(setOf("title", "attendees", "duration")), "args: $argNames")
        assertNotNull(prompt.description, "schedule_meeting should have a description")
    }

    @Test
    fun `schedule_meeting expands to a create_event workflow referencing its args`() {
        val result = getPrompt(
            "schedule_meeting",
            mapOf("title" to "Design Review", "attendees" to "sam@example.com", "duration" to "45m")
        )
        assertTrue(result.messages.isNotEmpty(), "must emit at least one message")
        val text = result.text()
        assertTrue(text.contains("create_event"), "should steer toward create_event: $text")
        assertTrue(text.contains("Design Review"), "should include the title argument")
        assertTrue(text.contains("sam@example.com"), "should include the attendees argument")
        assertTrue(text.contains("45m"), "should include the duration argument")
    }

    @Test
    fun `reschedule walks the get_events to update_event handle round-trip`() {
        val result = getPrompt(
            "reschedule",
            mapOf("event" to "Design Review", "new_time" to "2026-02-01T15:00:00Z")
        )
        val text = result.text()
        assertTrue(text.contains("get_events"), "should reference get_events: $text")
        assertTrue(text.contains("update_event"), "should reference update_event")
        assertTrue(text.contains("handle"), "should mention the handle round-trip")
        assertTrue(text.contains("2026-02-01T15:00:00Z"), "should include the new_time argument")
    }

    @Test
    fun `find_conflicts expands to a get_events conflict-reasoning template for the date`() {
        val result = getPrompt("find_conflicts", mapOf("date" to "2026-02-01"))
        val text = result.text()
        assertTrue(text.contains("get_events"), "should reference get_events: $text")
        assertTrue(text.contains("2026-02-01"), "should include the date argument")
        assertTrue(
            text.contains("overlap", ignoreCase = true) || text.contains("conflict", ignoreCase = true),
            "should ask the model to reason about overlaps/conflicts: $text"
        )
    }

    @Test
    fun `missing optional arguments do not break expansion`() {
        // A client may invoke a prompt before filling every field; expansion must
        // still produce a usable template rather than throwing.
        val result = getPrompt("schedule_meeting", emptyMap())
        assertTrue(result.messages.isNotEmpty())
        assertTrue(result.text().contains("create_event"))
    }
}
