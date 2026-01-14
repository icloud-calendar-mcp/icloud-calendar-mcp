package org.onekash.mcp.calendar.logging

import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContains

/**
 * Tests for McpLogger.
 */
class McpLoggerTest {

    private lateinit var mockServer: Server
    private lateinit var logger: McpLogger

    @BeforeEach
    fun setup() {
        mockServer = mockk(relaxed = true)
        logger = McpLogger(mockServer, "test-session", "test-logger")
    }

    @Test
    fun `sanitize removes passwords`() {
        val input = """{"password": "secret123", "user": "test"}"""
        val sanitized = logger.sanitize(input)

        assertFalse(sanitized.contains("secret123"), "Password should be removed")
        assertContains(sanitized, "[REDACTED]")
    }

    @Test
    fun `sanitize removes API keys`() {
        val input = """api_key: my-secret-key-12345"""
        val sanitized = logger.sanitize(input)

        assertFalse(sanitized.contains("my-secret-key"), "API key should be removed")
        assertContains(sanitized, "[REDACTED]")
    }

    @Test
    fun `sanitize removes email addresses`() {
        val input = "User email: user@example.com logged in"
        val sanitized = logger.sanitize(input)

        assertFalse(sanitized.contains("user@example.com"), "Email should be removed")
        assertContains(sanitized, "[REDACTED]")
    }

    @Test
    fun `sanitize removes authorization tokens`() {
        val input = """Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"""
        val sanitized = logger.sanitize(input)

        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), "Token should be removed")
        assertContains(sanitized, "[REDACTED]")
    }

    @Test
    fun `setLevel changes minimum log level`() {
        logger.setLevel(LoggingLevel.Warning)
        assertEquals(LoggingLevel.Warning, logger.getLevel())

        logger.setLevel(LoggingLevel.Debug)
        assertEquals(LoggingLevel.Debug, logger.getLevel())
    }

    @Test
    fun `parseLevel converts string to LoggingLevel`() {
        assertEquals(LoggingLevel.Debug, McpLogger.parseLevel("debug"))
        assertEquals(LoggingLevel.Info, McpLogger.parseLevel("info"))
        assertEquals(LoggingLevel.Notice, McpLogger.parseLevel("notice"))
        assertEquals(LoggingLevel.Warning, McpLogger.parseLevel("warning"))
        assertEquals(LoggingLevel.Warning, McpLogger.parseLevel("warn"))
        assertEquals(LoggingLevel.Error, McpLogger.parseLevel("error"))
        assertEquals(LoggingLevel.Critical, McpLogger.parseLevel("critical"))
        assertEquals(LoggingLevel.Alert, McpLogger.parseLevel("alert"))
        assertEquals(LoggingLevel.Emergency, McpLogger.parseLevel("emergency"))
        assertEquals(null, McpLogger.parseLevel("invalid"))
    }

    @Test
    fun `parseLevel is case insensitive`() {
        assertEquals(LoggingLevel.Debug, McpLogger.parseLevel("DEBUG"))
        assertEquals(LoggingLevel.Info, McpLogger.parseLevel("INFO"))
        assertEquals(LoggingLevel.Error, McpLogger.parseLevel("Error"))
    }

    @Test
    fun `rate limiter allows requests within limit`() {
        val logger = McpLogger(mockServer, rateLimit = 5, burstLimit = 10)

        // Should allow 5 logs
        repeat(5) {
            logger.info("Message $it")
        }

        // Verify sendLoggingMessage was called 5 times
        coVerify(exactly = 5) { mockServer.sendLoggingMessage(any(), any()) }
    }

    @Test
    fun `resetRateLimit clears rate limiting state`() {
        val logger = McpLogger(mockServer, rateLimit = 2, burstLimit = 2)

        // Use up the limit
        repeat(10) { logger.info("Message") }

        // Reset
        logger.resetRateLimit()

        // Clear mock counts
        clearMocks(mockServer, answers = false)

        // Should allow more logs after reset
        logger.info("After reset")
        coVerify(atLeast = 1) { mockServer.sendLoggingMessage(any(), any()) }
    }
}
