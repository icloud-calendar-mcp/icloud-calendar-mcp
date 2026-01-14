package org.onekash.mcp.calendar.progress

import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.onekash.mcp.calendar.logging.McpLogger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Tests for ProgressReporter.
 */
class ProgressReporterTest {

    private lateinit var mockServer: Server
    private lateinit var mockLogger: McpLogger

    @BeforeEach
    fun setup() {
        mockServer = mockk(relaxed = true)
        mockLogger = mockk(relaxed = true)
    }

    @Test
    fun `report updates progress state`() {
        val reporter = ProgressReporter(
            progressToken = "test-token",
            total = 100.0
        )

        reporter.report(25.0, "Step 1")
        assertEquals(25.0, reporter.getState().progress)
        assertEquals("Step 1", reporter.getState().message)

        reporter.report(75.0, "Step 2")
        assertEquals(75.0, reporter.getState().progress)
        assertEquals("Step 2", reporter.getState().message)
    }

    @Test
    fun `complete sets final progress`() {
        val reporter = ProgressReporter(
            progressToken = "test-token",
            total = 100.0
        )

        reporter.report(50.0)
        reporter.complete("Done")

        assertEquals(100.0, reporter.getState().progress)
        assertEquals("Done", reporter.getState().message)
    }

    @Test
    fun `hasToken returns correct value`() {
        val withToken = ProgressReporter(progressToken = "token-123")
        val withoutToken = ProgressReporter()

        assertTrue(withToken.hasToken())
        assertFalse(withoutToken.hasToken())
    }

    @Test
    fun `getToken returns token when present`() {
        val reporter = ProgressReporter(progressToken = "my-token")
        assertEquals("my-token", reporter.getToken())

        val noTokenReporter = ProgressReporter()
        assertNull(noTokenReporter.getToken())
    }

    @Test
    fun `onProgress callback is invoked`() {
        val reporter = ProgressReporter(total = 100.0)

        var callbackProgress = 0.0
        var callbackMessage: String? = null

        reporter.onProgress { progress, _, message ->
            callbackProgress = progress
            callbackMessage = message
        }

        reporter.report(50.0, "Halfway")

        assertEquals(50.0, callbackProgress)
        assertEquals("Halfway", callbackMessage)
    }

    @Test
    fun `noop reporter does not throw`() {
        val reporter = ProgressReporter.noop()

        // Should not throw
        reporter.report(50.0, "Test")
        reporter.complete("Done")
        reporter.error("Error")

        assertFalse(reporter.hasToken())
    }

    @Test
    fun `fromMeta extracts progressToken`() {
        val meta = mapOf<String, Any?>(
            "progressToken" to "extracted-token"
        )

        val reporter = ProgressReporter.fromMeta(
            meta = meta,
            operationName = "test-op"
        )

        assertEquals("extracted-token", reporter.getToken())
        assertTrue(reporter.hasToken())
    }

    @Test
    fun `fromMeta handles null meta`() {
        val reporter = ProgressReporter.fromMeta(
            meta = null,
            operationName = "test-op"
        )

        assertNull(reporter.getToken())
        assertFalse(reporter.hasToken())
    }

    @Test
    fun `progressReporter extension creates reporter with logger`() {
        val logger = McpLogger(mockServer, "session", "test-logger")
        val reporter = logger.progressReporter(
            progressToken = "ext-token",
            total = 50.0,
            operationName = "extension-test"
        )

        assertTrue(reporter.hasToken())
        assertEquals("ext-token", reporter.getToken())
    }
}
