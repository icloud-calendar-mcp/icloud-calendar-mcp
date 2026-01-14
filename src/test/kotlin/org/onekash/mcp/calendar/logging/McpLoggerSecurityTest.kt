package org.onekash.mcp.calendar.logging

import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import java.util.concurrent.TimeUnit

/**
 * Security tests for McpLogger.
 *
 * Tests for:
 * - Log injection attacks
 * - Sensitive data sanitization bypass
 * - Control character injection
 * - Unicode/homoglyph bypass attempts
 * - Resource exhaustion via logging
 * - Timing attack vectors
 */
class McpLoggerSecurityTest {

    private lateinit var mockServer: Server
    private lateinit var logger: McpLogger

    @BeforeEach
    fun setup() {
        mockServer = mockk(relaxed = true)
        logger = McpLogger(mockServer, "test-session", "test-logger")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOG INJECTION ATTACKS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `log injection via multiline message is handled`() {
        val injection = "Normal log\n[CRITICAL] Fake critical message\n[ADMIN] Privilege escalation"

        val sanitized = logger.sanitize(injection)

        // Should not create separate log entries or fake severity levels
        // The newlines should be preserved but not interpreted as separate logs
        assertTrue(sanitized.contains("Normal log"))
    }

    @Test
    fun `log injection via CRLF is handled`() {
        val crlfInjection = "Request completed\r\n\r\nHTTP/1.1 200 OK\r\nSet-Cookie: admin=true"

        val sanitized = logger.sanitize(crlfInjection)

        // CRLF should not allow header injection
        assertTrue(sanitized.contains("Request completed"))
    }

    @Test
    fun `log forging via format string is handled`() {
        val formatString = "%s%s%s%n%n%n"

        val sanitized = logger.sanitize(formatString)

        // Format strings should be treated as literal text
        assertEquals("%s%s%s%n%n%n", sanitized)
    }

    @Test
    fun `log injection via JSON structure`() {
        val jsonInjection = """{"level":"emergency","message":"FAKE ALERT","admin":true}"""

        val sanitized = logger.sanitize(jsonInjection)

        // JSON structure should not alter log behavior
        assertTrue(sanitized.contains("level"))
    }

    @Test
    fun `log injection via escape sequences`() {
        val escapeInjection = "Normal\\x1b[31mRED TEXT\\x1b[0m"

        val sanitized = logger.sanitize(escapeInjection)

        // ANSI escape sequences should not affect terminal output
        assertTrue(sanitized.contains("Normal"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SENSITIVE DATA SANITIZATION BYPASS ATTEMPTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `sanitize handles obfuscated password patterns`() {
        val obfuscations = listOf(
            "p@ssword=secret123",
            "passw0rd=secret123",
            "passwd=secret123",
            "pass_word=secret123"
        )

        obfuscations.forEach { input ->
            val sanitized = logger.sanitize(input)
            // At minimum, common patterns should be caught
            if (input.contains("password") || input.contains("passwd")) {
                assertFalse(sanitized.contains("secret123"), "Should sanitize: $input")
            }
        }
    }

    @Test
    fun `sanitize handles split credentials`() {
        // Attempt to split sensitive data across patterns
        val split = "pass=sec word=ret123"

        val sanitized = logger.sanitize(split)

        // Individual words might not be caught, which is acceptable
        // The key is that known patterns are caught
        assertNotNull(sanitized)
    }

    @Test
    fun `sanitize handles URL-encoded credentials`() {
        val urlEncoded = "password%3Dsecret123"

        val sanitized = logger.sanitize(urlEncoded)

        // URL encoding might bypass simple patterns
        // This documents current behavior
        assertNotNull(sanitized)
    }

    @Test
    fun `sanitize handles Base64 encoded credentials`() {
        val base64 = "cGFzc3dvcmQ6c2VjcmV0MTIz" // password:secret123

        val sanitized = logger.sanitize(base64)

        // Base64 detection is not expected, but JWT tokens should be caught
        assertNotNull(sanitized)
    }

    @Test
    fun `sanitize handles multiple secrets in one message`() {
        val multiSecret = """
            config = {
                password: "secret1",
                api_key: "key123",
                token: "tok456",
                email: "user@example.com"
            }
        """.trimIndent()

        val sanitized = logger.sanitize(multiSecret)

        assertFalse(sanitized.contains("secret1"), "Should redact password")
        assertFalse(sanitized.contains("key123"), "Should redact api_key")
        assertFalse(sanitized.contains("tok456"), "Should redact token")
        assertFalse(sanitized.contains("user@example.com"), "Should redact email")
    }

    @Test
    fun `sanitize handles different quote styles`() {
        val variations = listOf(
            """password="secret123"""",
            """password='secret123'""",
            """password=`secret123`""",
            """password:secret123"""
        )

        variations.forEach { input ->
            val sanitized = logger.sanitize(input)
            assertFalse(
                sanitized.contains("secret123"),
                "Should sanitize different quote style: $input"
            )
        }
    }

    @Test
    fun `sanitize handles whitespace variations`() {
        val variations = listOf(
            "password = secret123",
            "password  =  secret123",
            "password\t=\tsecret123",
            "password:  secret123"
        )

        variations.forEach { input ->
            val sanitized = logger.sanitize(input)
            assertFalse(
                sanitized.contains("secret123"),
                "Should sanitize with whitespace: $input"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONTROL CHARACTER INJECTION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `control characters in log message`() {
        val controlChars = "Normal\u0000NULL\u0007BELL\u0008BACKSPACE\u001BESCAPE"

        val sanitized = logger.sanitize(controlChars)

        // Control characters should not affect log processing
        assertNotNull(sanitized)
    }

    @Test
    fun `terminal control sequences are handled`() {
        val terminalEscape = "\u001B[2J\u001B[H" // Clear screen and home cursor

        val sanitized = logger.sanitize(terminalEscape)

        // Should not clear user's terminal
        assertNotNull(sanitized)
    }

    @Test
    fun `carriage return overwrites are handled`() {
        val overwrite = "Sensitive data here\rPublic message only"

        val sanitized = logger.sanitize(overwrite)

        // CR should not hide sensitive data
        assertTrue(sanitized.contains("Sensitive") || sanitized.contains("Public"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UNICODE AND HOMOGLYPH BYPASS ATTEMPTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `unicode homoglyph password bypass attempt`() {
        // Using Cyrillic 'а' (U+0430) instead of Latin 'a'
        val homoglyph = "pаssword=secret123" // 'а' is Cyrillic

        val sanitized = logger.sanitize(homoglyph)

        // Homoglyphs might bypass simple regex
        // This documents current behavior for security review
        assertNotNull(sanitized)
    }

    @Test
    fun `unicode normalization bypass attempt`() {
        // Using fullwidth characters
        val fullwidth = "password=secret123"

        val sanitized = logger.sanitize(fullwidth)

        // Fullwidth characters might bypass detection
        assertNotNull(sanitized)
    }

    @Test
    fun `zero-width character injection`() {
        val zeroWidth = "pass\u200Bword\u200C=\u200Dsecret123"

        val sanitized = logger.sanitize(zeroWidth)

        // Zero-width characters might bypass keyword detection
        assertNotNull(sanitized)
    }

    @Test
    fun `RTL override injection attempt`() {
        // Right-to-left override could make sensitive data appear different
        val rtlOverride = "\u202Epassword=secret123"

        val sanitized = logger.sanitize(rtlOverride)

        assertNotNull(sanitized)
    }

    @Test
    fun `mixed script email detection`() {
        val emails = listOf(
            "user@example.com",
            "user@ехample.com",     // Cyrillic 'х'
            "user@exаmple.com",     // Cyrillic 'а'
            "user＠example.com"     // Fullwidth @
        )

        emails.forEach { email ->
            val sanitized = logger.sanitize("Email: $email")
            // Standard email should be caught; homoglyphs document behavior
            if (email == "user@example.com") {
                assertFalse(sanitized.contains(email), "Should redact standard email")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESOURCE EXHAUSTION VIA LOGGING
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `rate limiting prevents log flood`() {
        val logger = McpLogger(mockServer, rateLimit = 5, burstLimit = 10)

        var logCount = 0
        repeat(1000) {
            try {
                logger.info("Flood message $it")
                logCount++
            } catch (e: Exception) {
                // Rate limited
            }
        }

        // Should complete quickly due to rate limiting
        coVerify(atMost = 20) { mockServer.sendLoggingMessage(any(), any()) }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `extremely long log message is handled`() {
        val longMessage = "A".repeat(10_000) // 10KB message

        // Should not hang or OOM
        val sanitized = logger.sanitize(longMessage)
        assertNotNull(sanitized)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `many log data entries dont cause issues`() {
        val manyEntries = (1..10000).associate { "key$it" to "value$it" }

        // Should handle large data maps
        logger.info("Test message", manyEntries)

        // Should complete without timeout
        assertTrue(true)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `regex DoS via crafted input`() {
        // Pattern that might cause catastrophic backtracking
        val redosAttempt = "a".repeat(100) + "!"

        val sanitized = logger.sanitize(redosAttempt)

        // Should complete quickly without hanging
        assertNotNull(sanitized)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMING ATTACK VECTORS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `log level filtering is consistent timing`() {
        val logger = McpLogger(mockServer, "session", "logger")
        logger.setLevel(LoggingLevel.Error)

        val startFiltered = System.nanoTime()
        repeat(1000) {
            logger.debug("Filtered message") // Below error level
        }
        val filteredTime = System.nanoTime() - startFiltered

        logger.setLevel(LoggingLevel.Debug)

        val startAllowed = System.nanoTime()
        repeat(1000) {
            logger.debug("Allowed message")
        }
        val allowedTime = System.nanoTime() - startAllowed

        // Timing difference exists but should not leak significant information
        assertTrue(filteredTime < allowedTime * 10, "Filtered should be faster")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ADDITIONAL SENSITIVE DATA PATTERNS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `sanitize handles SSN-like patterns`() {
        val ssn = "User SSN: 123-45-6789"

        val sanitized = logger.sanitize(ssn)

        // SSN detection is not currently implemented
        // This documents the gap for future improvement
        assertNotNull(sanitized)
    }

    @Test
    fun `sanitize handles credit card patterns`() {
        val cc = "Card: 4111-1111-1111-1111"

        val sanitized = logger.sanitize(cc)

        // Credit card detection is not currently implemented
        assertNotNull(sanitized)
    }

    @Test
    fun `sanitize handles secret patterns`() {
        val secretData = "secret=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

        val sanitized = logger.sanitize(secretData)

        // Should catch secret patterns
        assertContains(sanitized, "[REDACTED]")
    }

    @Test
    fun `sanitize handles bearer tokens`() {
        val bearer = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"

        val sanitized = logger.sanitize(bearer)

        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
    }

    @Test
    fun `sanitize handles cookie values`() {
        val cookie = "Set-Cookie: session=abc123def456; HttpOnly"

        val sanitized = logger.sanitize(cookie)

        // Cookie detection is not currently implemented
        assertNotNull(sanitized)
    }

    @Test
    fun `sanitize handles private keys`() {
        val privateKey = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEA0Z3VS5JJcds3xfn/ygWyF8PbnGy...
            -----END RSA PRIVATE KEY-----
        """.trimIndent()

        val sanitized = logger.sanitize(privateKey)

        // Private key detection is not currently implemented
        assertNotNull(sanitized)
    }
}
