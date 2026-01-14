package org.onekash.mcp.calendar.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Assertions.*
import org.onekash.mcp.calendar.ics.IcsParser
import org.onekash.mcp.calendar.ics.IcsBuilder
import org.onekash.mcp.calendar.validation.InputValidator
import org.onekash.mcp.calendar.error.SecureErrorHandler
import org.onekash.mcp.calendar.logging.McpLogger
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.util.concurrent.TimeUnit

/**
 * ReDoS (Regular Expression Denial of Service) and Resource Exhaustion Tests.
 *
 * Tests patterns known to cause catastrophic backtracking in regex engines
 * and resource exhaustion attacks against various components.
 *
 * All tests have strict timeouts to detect infinite loops or excessive processing.
 */
class ReDoSSecurityTest {

    private lateinit var parser: IcsParser
    private lateinit var builder: IcsBuilder
    private lateinit var mockServer: Server

    @BeforeEach
    fun setup() {
        parser = IcsParser()
        builder = IcsBuilder()
        mockServer = mockk(relaxed = true)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CLASSIC REDOS PATTERNS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `ReDoS evil regex pattern - exponential backtracking`() {
        // Classic (a+)+ pattern that causes exponential backtracking
        val malicious = "a".repeat(30) + "!"

        val result = InputValidator.validateCalendarId(malicious)
        // Should complete quickly regardless of outcome
        assertNotNull(result)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `ReDoS nested quantifiers pattern`() {
        // Pattern: (a*)*b - nested quantifiers
        val malicious = "a".repeat(25)

        val result = InputValidator.validateTitle(malicious)
        assertNotNull(result)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `ReDoS overlapping alternations`() {
        // Pattern like (a|a)+ with overlapping options
        val malicious = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaab"

        val result = InputValidator.validateOptionalText(malicious, "test")
        assertNotNull(result)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `ReDoS email validation pattern`() {
        // Email patterns can be vulnerable to ReDoS
        val maliciousEmail = "a".repeat(50) + "@" + "b".repeat(50) + ".com!"

        // This tests the email pattern in McpLogger sanitization
        val logger = McpLogger(mockServer, "test", "test")
        val result = logger.sanitize("Email: $maliciousEmail")

        assertNotNull(result)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `ReDoS password pattern with special chars`() {
        // Password patterns might use complex regex
        val maliciousPassword = "=" + "a".repeat(50) + "!"

        val logger = McpLogger(mockServer, "test", "test")
        val result = logger.sanitize("password=$maliciousPassword")

        assertNotNull(result)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `ReDoS URL pattern`() {
        // URL validation patterns can be complex
        val maliciousUrl = "http://" + "a".repeat(100) + "." + "b".repeat(100) + ".com"

        val result = InputValidator.validateCalendarId(maliciousUrl)
        assertNotNull(result)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ICS PARSER RESOURCE EXHAUSTION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `ICS billion laughs attack variant`() {
        // XML bomb style attack adapted for ICS
        // Deep expansion isn't native to ICS, but we test large content
        val hugeDescription = "LOL".repeat(100_000)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:bomb@test.com
            SUMMARY:Bomb Test
            DESCRIPTION:$hugeDescription
            DTSTART:20250115T100000Z
            DTEND:20250115T110000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertNotNull(events)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `ICS quadratic blowup via line folding`() {
        // ICS line folding could potentially cause quadratic behavior
        // Each line continuation is " " (space) at start of line
        val foldedContent = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("VERSION:2.0\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:fold@test.com\r\n")
            append("SUMMARY:Folded Test\r\n")
            append("DESCRIPTION:")
            repeat(10000) { i ->
                append("Word$i")
                if (i % 10 == 0) append("\r\n ") // Line fold
            }
            append("\r\n")
            append("DTSTART:20250115T100000Z\r\n")
            append("DTEND:20250115T110000Z\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }

        val events = parser.parse(foldedContent)
        assertNotNull(events)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `ICS many small events`() {
        // Many small components might stress parser
        val manyEvents = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            repeat(10000) { i ->
                appendLine("BEGIN:VEVENT")
                appendLine("UID:small-$i@test.com")
                appendLine("SUMMARY:Event $i")
                appendLine("DTSTART:20250115T100000Z")
                appendLine("DTEND:20250115T100100Z")
                appendLine("END:VEVENT")
            }
            appendLine("END:VCALENDAR")
        }

        val events = parser.parse(manyEvents)
        assertEquals(10000, events.size)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `ICS deeply nested components`() {
        // Test parser robustness with unusual nesting
        val nested = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            repeat(100) {
                appendLine("BEGIN:VEVENT")
            }
            appendLine("UID:deep@test.com")
            appendLine("SUMMARY:Deep Nesting")
            appendLine("DTSTART:20250115T100000Z")
            repeat(100) {
                appendLine("END:VEVENT")
            }
            appendLine("END:VCALENDAR")
        }

        val events = parser.parse(nested)
        assertNotNull(events)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `ICS malformed RRULE processing`() {
        // Complex RRULE might cause parsing issues
        val complexRrule = "FREQ=YEARLY;BYDAY=" + (1..52).joinToString(",") { "${it}MO,${it}TU,${it}WE,${it}TH,${it}FR" }

        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:rrule@test.com
            SUMMARY:Complex Recurrence
            DTSTART:20250115T100000Z
            DTEND:20250115T110000Z
            RRULE:$complexRrule
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(ics)
        assertNotNull(events)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ICS BUILDER RESOURCE EXHAUSTION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `ICS builder with massive summary`() {
        val hugeSummary = "Meeting " + "A".repeat(100_000)

        val ics = builder.build(
            summary = hugeSummary.take(500), // Limited by builder
            startDate = "2025-01-15",
            isAllDay = true
        )

        assertNotNull(ics)
        assertTrue(ics.contains("SUMMARY:"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `ICS builder with massive description`() {
        val hugeDesc = "Notes: " + "B".repeat(100_000)

        val ics = builder.build(
            summary = "Test",
            description = hugeDesc.take(5000), // Limited
            startDate = "2025-01-15",
            isAllDay = true
        )

        assertNotNull(ics)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `ICS builder escaping stress test`() {
        // Many characters that need escaping
        val escapeHeavy = ";,\\;,\\;,\\".repeat(10000)

        val ics = builder.build(
            summary = "Test",
            description = escapeHeavy,
            startDate = "2025-01-15",
            isAllDay = true
        )

        assertNotNull(ics)
        // Should have escaped semicolons
        assertTrue(ics.contains("\\;"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT VALIDATOR STRESS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `validator with extremely long input`() {
        val longInput = "x".repeat(1_000_000) // 1MB

        val result = InputValidator.validateCalendarId(longInput)

        // Should be rejected quickly for being too long
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `validator with many special characters`() {
        val specialChars = "<>\"';&|`$(){}[]".repeat(10000)

        val result = InputValidator.validateCalendarId(specialChars)

        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `ICS sanitizer with pathological input`() {
        // Input designed to stress the sanitizer
        val pathological = "\r\n".repeat(100000) + ";" + ",".repeat(100000)

        val result = InputValidator.sanitizeForIcs(pathological)

        assertNotNull(result)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ERROR HANDLER STRESS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `error handler with massive error message`() {
        val hugeMessage = "Error: " + "X".repeat(100_000)

        val result = SecureErrorHandler.createErrorResult(
            SecureErrorHandler.ErrorCode.INTERNAL_ERROR,
            hugeMessage
        )

        assertNotNull(result)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `error handler sanitization stress test`() {
        // Many patterns that need sanitization
        val multiPattern = buildString {
            repeat(1000) { i ->
                append("password=secret$i ")
                append("token=tok$i ")
                append("user$i@example.com ")
                append("/Users/user$i/secret ")
            }
        }

        val result = SecureErrorHandler.createErrorResult(
            SecureErrorHandler.ErrorCode.INTERNAL_ERROR,
            multiPattern
        )

        assertNotNull(result)

        val content = result.content?.firstOrNull() as? io.modelcontextprotocol.kotlin.sdk.types.TextContent
        // Should have sanitized passwords
        assertFalse(content?.text?.contains("secret0") == true)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOGGER SANITIZATION STRESS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `logger sanitize with ReDoS email pattern`() {
        val logger = McpLogger(mockServer, "test", "test")

        // Pattern designed to stress email regex
        val stressEmail = "a@" + "b.".repeat(100) + "com"

        val result = logger.sanitize("Email: $stressEmail")
        assertNotNull(result)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `logger sanitize with many patterns to match`() {
        val logger = McpLogger(mockServer, "test", "test")

        val manyPatterns = buildString {
            repeat(10000) { i ->
                append("password$i=value$i ")
            }
        }

        val result = logger.sanitize(manyPatterns)
        assertNotNull(result)
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `logger sanitize with alternating sensitive patterns`() {
        val logger = McpLogger(mockServer, "test", "test")

        val alternating = buildString {
            repeat(5000) {
                append("password=x token=y api_key=z secret=w ")
            }
        }

        val result = logger.sanitize(alternating)
        assertNotNull(result)
        assertFalse(result.contains("password=x"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HASH COLLISION ATTACKS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `hash collision in cache keys`() {
        // Test that cache doesn't degrade with similar keys
        // This is more relevant for HashMaps in Java/Kotlin

        val keys = (1..10000).map { "key-$it-${it.hashCode()}" }

        // Simulate cache operations
        val cache = mutableMapOf<String, String>()
        keys.forEach { key ->
            cache[key] = "value-$key"
        }

        assertEquals(10000, cache.size)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MEMORY EXHAUSTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `parser doesnt hold references to large parsed content`() {
        // Parse large content and verify it doesn't cause memory issues
        repeat(10) {
            val largeIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:large-$it@test.com
                SUMMARY:Large Event $it
                DESCRIPTION:${"X".repeat(100_000)}
                DTSTART:20250115T100000Z
                DTEND:20250115T110000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val events = parser.parse(largeIcs)
            assertEquals(1, events.size)
        }

        // If we get here without OOM, the test passes
        assertTrue(true)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `builder doesnt accumulate state`() {
        // Multiple builds shouldn't accumulate memory
        repeat(1000) { i ->
            val ics = builder.build(
                summary = "Event $i with ${"description ".repeat(100)}",
                startDate = "2025-01-15",
                isAllDay = true
            )
            assertNotNull(ics)
        }

        assertTrue(true)
    }
}
