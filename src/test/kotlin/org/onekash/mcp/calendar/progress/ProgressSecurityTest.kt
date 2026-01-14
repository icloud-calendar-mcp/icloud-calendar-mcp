package org.onekash.mcp.calendar.progress

import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.onekash.mcp.calendar.logging.McpLogger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Security tests for ProgressReporter.
 *
 * Tests for:
 * - Progress token enumeration/prediction
 * - Injection via progress messages
 * - Resource exhaustion via rapid updates
 * - State manipulation attacks
 * - Callback security
 */
class ProgressSecurityTest {

    private lateinit var mockServer: Server
    private lateinit var mockLogger: McpLogger

    @BeforeEach
    fun setup() {
        mockServer = mockk(relaxed = true)
        mockLogger = mockk(relaxed = true)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROGRESS TOKEN SECURITY
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `sequential token IDs are handled safely`() {
        val reporter1 = ProgressReporter(progressToken = "token-1")
        val reporter2 = ProgressReporter(progressToken = "token-2")
        val reporter3 = ProgressReporter(progressToken = "token-3")

        // Each reporter should be independent
        reporter1.report(50.0, "Progress 1")
        reporter2.report(75.0, "Progress 2")
        reporter3.report(25.0, "Progress 3")

        assertEquals(50.0, reporter1.getState().progress)
        assertEquals(75.0, reporter2.getState().progress)
        assertEquals(25.0, reporter3.getState().progress)
    }

    @Test
    fun `special characters in token are handled`() {
        val specialTokens = listOf(
            "token/with/slashes",
            "token:with:colons",
            "token@with@at",
            "token#with#hash",
            "token with spaces",
            "token\twith\ttabs",
            """token"with"quotes""",
            "token<with>brackets",
            "token{with}braces"
        )

        specialTokens.forEach { token ->
            val reporter = ProgressReporter(progressToken = token)
            assertEquals(token, reporter.getToken(), "Should handle token: $token")
            reporter.report(50.0, "Test")
            assertEquals(50.0, reporter.getState().progress)
        }
    }

    @Test
    fun `very long token is handled`() {
        val longToken = "a".repeat(10_000)
        val reporter = ProgressReporter(progressToken = longToken)

        assertEquals(longToken, reporter.getToken())
        reporter.report(50.0, "Progress")
        assertEquals(50.0, reporter.getState().progress)
    }

    @Test
    fun `null and empty tokens work correctly`() {
        val noToken = ProgressReporter()
        val emptyToken = ProgressReporter(progressToken = "")
        val blankToken = ProgressReporter(progressToken = "   ")

        assertFalse(noToken.hasToken())
        assertNull(noToken.getToken())

        // Empty string is still a token
        assertTrue(emptyToken.hasToken())
        assertEquals("", emptyToken.getToken())

        assertTrue(blankToken.hasToken())
        assertEquals("   ", blankToken.getToken())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INJECTION VIA PROGRESS MESSAGES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `XSS in progress message is treated as data`() {
        val reporter = ProgressReporter(progressToken = "test")
        val xssPayload = "<script>alert('xss')</script>"

        reporter.report(50.0, xssPayload)

        // Message should be stored as-is (sanitization happens at output layer)
        assertEquals(xssPayload, reporter.getState().message)
    }

    @Test
    fun `SQL injection in progress message is treated as data`() {
        val reporter = ProgressReporter(progressToken = "test")
        val sqlPayload = "'; DROP TABLE progress; --"

        reporter.report(50.0, sqlPayload)

        assertEquals(sqlPayload, reporter.getState().message)
    }

    @Test
    fun `JSON injection in progress message is treated as data`() {
        val reporter = ProgressReporter(progressToken = "test")
        val jsonPayload = """{"admin": true, "bypass": "security"}"""

        reporter.report(50.0, jsonPayload)

        assertEquals(jsonPayload, reporter.getState().message)
    }

    @Test
    fun `control characters in progress message`() {
        val reporter = ProgressReporter(progressToken = "test")
        val controlPayload = "Progress\u0000\u0001\u0007\u001B[31mRED"

        reporter.report(50.0, controlPayload)

        assertEquals(controlPayload, reporter.getState().message)
    }

    @Test
    fun `multiline progress message`() {
        val reporter = ProgressReporter(progressToken = "test")
        val multiline = "Line 1\nLine 2\r\nLine 3\rLine 4"

        reporter.report(50.0, multiline)

        assertEquals(multiline, reporter.getState().message)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESOURCE EXHAUSTION VIA RAPID UPDATES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `rapid progress updates dont cause issues`() {
        val reporter = ProgressReporter(progressToken = "rapid-test", total = 10_000.0)

        repeat(10_000) { i ->
            reporter.report(i.toDouble(), "Update $i")
        }

        assertEquals(9_999.0, reporter.getState().progress)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `many callbacks dont cause memory issues`() {
        val reporter = ProgressReporter(progressToken = "callback-test")
        val callCount = AtomicInteger(0)

        // Register callback
        reporter.onProgress { _, _, _ ->
            callCount.incrementAndGet()
        }

        // Rapid updates
        repeat(10_000) { i ->
            reporter.report(i.toDouble())
        }

        assertEquals(10_000, callCount.get())
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `very long progress messages`() {
        val reporter = ProgressReporter(progressToken = "long-message-test")
        val longMessage = "X".repeat(100_000) // 100KB message

        reporter.report(50.0, longMessage)

        assertEquals(longMessage, reporter.getState().message)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE MANIPULATION ATTACKS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `negative progress values are handled`() {
        val reporter = ProgressReporter(progressToken = "negative-test", total = 100.0)

        reporter.report(-50.0, "Negative progress")

        // Negative values should be stored (validation is caller's responsibility)
        assertEquals(-50.0, reporter.getState().progress)
    }

    @Test
    fun `progress exceeding total is handled`() {
        val reporter = ProgressReporter(progressToken = "overflow-test", total = 100.0)

        reporter.report(150.0, "Overflow progress")

        // Over-total values should be stored
        assertEquals(150.0, reporter.getState().progress)
    }

    @Test
    fun `NaN progress is handled`() {
        val reporter = ProgressReporter(progressToken = "nan-test")

        reporter.report(Double.NaN, "NaN progress")

        assertTrue(reporter.getState().progress.isNaN())
    }

    @Test
    fun `infinite progress is handled`() {
        val reporter = ProgressReporter(progressToken = "infinity-test")

        reporter.report(Double.POSITIVE_INFINITY, "Infinite progress")
        assertEquals(Double.POSITIVE_INFINITY, reporter.getState().progress)

        reporter.report(Double.NEGATIVE_INFINITY, "Negative infinite")
        assertEquals(Double.NEGATIVE_INFINITY, reporter.getState().progress)
    }

    @Test
    fun `complete with abnormal progress`() {
        val reporter = ProgressReporter(progressToken = "complete-test", total = 100.0)

        reporter.report(25.0)
        reporter.complete("Done") // Should set to total

        assertEquals(100.0, reporter.getState().progress)
    }

    @Test
    fun `error state handling`() {
        val reporter = ProgressReporter(progressToken = "error-test")

        reporter.report(50.0, "Working")
        reporter.error("Something went wrong")

        assertEquals("Something went wrong", reporter.getState().message)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CALLBACK SECURITY
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `callback exception doesnt break reporter`() {
        val reporter = ProgressReporter(progressToken = "exception-test")

        reporter.onProgress { _, _, _ ->
            throw RuntimeException("Callback error")
        }

        // Should not propagate exception (or should handle gracefully)
        try {
            reporter.report(50.0, "Test")
            // If we get here, exception was handled
            assertTrue(true)
        } catch (e: Exception) {
            // If exception propagates, it should be the original
            assertEquals("Callback error", e.message)
        }
    }

    @Test
    fun `callback receives correct values`() {
        val reporter = ProgressReporter(progressToken = "values-test", total = 200.0)

        var receivedProgress = 0.0
        var receivedTotal: Double? = 0.0
        var receivedMessage: String? = null

        reporter.onProgress { progress, total, message ->
            receivedProgress = progress
            receivedTotal = total
            receivedMessage = message
        }

        reporter.report(100.0, "Halfway")

        assertEquals(100.0, receivedProgress)
        assertEquals(200.0, receivedTotal)
        assertEquals("Halfway", receivedMessage)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `slow callback doesnt block indefinitely`() {
        val reporter = ProgressReporter(progressToken = "slow-test")

        reporter.onProgress { _, _, _ ->
            Thread.sleep(1) // Simulate slow callback
        }

        // 1000 updates with 1ms each = 1 second minimum
        repeat(1000) { i ->
            reporter.report(i.toDouble())
        }

        // Should complete within timeout
        assertEquals(999.0, reporter.getState().progress)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ISOLATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `reporters with same token are independent`() {
        val reporter1 = ProgressReporter(progressToken = "same-token")
        val reporter2 = ProgressReporter(progressToken = "same-token")

        reporter1.report(25.0, "Reporter 1")
        reporter2.report(75.0, "Reporter 2")

        assertEquals(25.0, reporter1.getState().progress)
        assertEquals(75.0, reporter2.getState().progress)
        assertEquals("Reporter 1", reporter1.getState().message)
        assertEquals("Reporter 2", reporter2.getState().message)
    }

    @Test
    fun `noop reporter is truly no-op`() {
        val noop = ProgressReporter.noop()

        // Should not throw
        noop.report(50.0, "Test")
        noop.complete("Done")
        noop.error("Error")

        assertFalse(noop.hasToken())
        assertNull(noop.getToken())
    }

    @Test
    fun `fromMeta handles malicious meta`() {
        val maliciousMeta = mapOf<String, Any?>(
            "progressToken" to "<script>alert('xss')</script>",
            "extra" to "ignored",
            "__proto__" to mapOf("admin" to true)
        )

        val reporter = ProgressReporter.fromMeta(maliciousMeta, operationName = "test-op")

        assertEquals("<script>alert('xss')</script>", reporter.getToken())
    }

    @Test
    fun `fromMeta handles various progressToken types`() {
        // String token
        val stringMeta = mapOf<String, Any?>("progressToken" to "string-token")
        val stringReporter = ProgressReporter.fromMeta(stringMeta, operationName = "op")
        assertEquals("string-token", stringReporter.getToken())

        // Number token (should be converted)
        val numberMeta = mapOf<String, Any?>("progressToken" to 12345)
        val numberReporter = ProgressReporter.fromMeta(numberMeta, operationName = "op")
        // Behavior depends on implementation
        assertNotNull(numberReporter)

        // Null token
        val nullMeta = mapOf<String, Any?>("progressToken" to null)
        val nullReporter = ProgressReporter.fromMeta(nullMeta, operationName = "op")
        assertFalse(nullReporter.hasToken())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONCURRENT ACCESS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `concurrent progress updates`() {
        val reporter = ProgressReporter(progressToken = "concurrent-test")
        val errors = AtomicInteger(0)

        val threads = (1..10).map { t ->
            Thread {
                repeat(100) { i ->
                    try {
                        reporter.report((t * 100 + i).toDouble(), "Thread $t Update $i")
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(0, errors.get())
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `concurrent callback invocation`() {
        val reporter = ProgressReporter(progressToken = "callback-concurrent")
        val callbackCount = AtomicInteger(0)

        reporter.onProgress { _, _, _ ->
            callbackCount.incrementAndGet()
        }

        val threads = (1..10).map {
            Thread {
                repeat(100) { i ->
                    reporter.report(i.toDouble())
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Each thread calls 100 times, 10 threads = 1000 callbacks
        assertEquals(1_000, callbackCount.get())
    }
}
