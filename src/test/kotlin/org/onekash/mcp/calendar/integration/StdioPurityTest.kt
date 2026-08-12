package org.onekash.mcp.calendar.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The MCP stdio transport reserves stdout for JSON-RPC frames — a single non-JSON line
 * there fails the client's parse of the whole stream.
 *
 * Regression guard for the kotlin-logging startup banner ("kotlin-logging: initializing...
 * active logger factory: Slf4jLoggerFactory"), which the MCP SDK's logging dependency
 * writes to System.out unless suppressed, landing as line 1 of stdout.
 *
 * Boots the real server in a child JVM instead of asserting on the suppression flag, so
 * any future stdout writer is caught too, not just this one. No iCloud credentials are
 * needed: tools register regardless, and initialize never touches the network.
 */
class StdioPurityTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `first stdout line is a JSON-RPC message`() {
        val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val process = ProcessBuilder(
            javaBin,
            "-cp",
            System.getProperty("java.class.path"),
            "org.onekash.mcp.calendar.MainKt"
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

        val firstLine = try {
            process.outputStream.writer().apply {
                write(INITIALIZE_REQUEST)
                write("\n")
                flush()
            }
            process.inputStream.bufferedReader().readLine()
        } finally {
            process.destroyForcibly()
        }

        assertNotNull(firstLine, "server wrote nothing to stdout")
        // Throws if the line is not JSON — that is the failure this test exists to catch.
        val message = Json.parseToJsonElement(firstLine).jsonObject
        assertEquals("2.0", message["jsonrpc"]?.jsonPrimitive?.content, "stdout line 1: $firstLine")
    }

    private companion object {
        const val INITIALIZE_REQUEST =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"stdio-purity-test","version":"1.0.0"}}}"""
    }
}
