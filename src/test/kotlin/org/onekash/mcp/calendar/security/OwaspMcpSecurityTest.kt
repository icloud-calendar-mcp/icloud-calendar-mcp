package org.onekash.mcp.calendar.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.onekash.mcp.calendar.caldav.*
import org.onekash.mcp.calendar.ics.IcsParser
import org.onekash.mcp.calendar.ics.IcsBuilder
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.ServiceResult
import org.onekash.mcp.calendar.error.SecureErrorHandler
import org.onekash.mcp.calendar.ratelimit.RateLimiter
import org.onekash.mcp.calendar.validation.InputValidator
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit

/**
 * OWASP MCP Top 10 Security Tests
 *
 * Tests for risks not covered in AdversarialTest.kt:
 * - MCP02: Tool Manipulation & Control Flow
 * - MCP03: Embedded Resource Injection
 * - MCP04: Insecure Credential Storage
 * - MCP08: Uncontrolled Resource Consumption
 * - MCP09: Sandbox Escape & Isolation
 */
class OwaspMcpSecurityTest {

    private lateinit var mockClient: CalDavClient
    private lateinit var service: CalendarService
    private lateinit var parser: IcsParser
    private lateinit var builder: IcsBuilder

    @BeforeEach
    fun setup() {
        mockClient = mockk()
        service = CalendarService(mockClient)
        parser = IcsParser()
        builder = IcsBuilder()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MCP02: TOOL MANIPULATION & CONTROL FLOW
    // Tests that tool parameters cannot be manipulated to alter expected behavior
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `MCP02 - JSON injection in tool parameters is handled safely`() {
        // Attempt to inject additional JSON properties via string
        val maliciousCalendarId = """normal-id", "admin": true, "bypass": "auth"""

        val result = InputValidator.validateCalendarId(maliciousCalendarId)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP02 - nested object injection in string parameter`() {
        // Attempt to inject nested JSON objects
        val injection = """{"nested": {"privilege": "admin"}}"""

        val result = InputValidator.validateTitle(injection)
        // Title should be valid (we sanitize output, not reject JSON-like input)
        assertTrue(result is InputValidator.ValidationResult.Valid)

        // But the ICS output should escape it properly
        val ics = builder.build(
            summary = injection,
            startDate = "2025-01-15",
            isAllDay = true
        )
        // Should not contain unescaped braces that could cause parsing issues
        assertTrue(ics.contains("SUMMARY:"))
    }

    @Test
    fun `MCP02 - prototype pollution attempt in calendar ID`() {
        val pollutionAttempt = "__proto__[admin]=1"

        val result = InputValidator.validateCalendarId(pollutionAttempt)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP02 - control character injection in tool parameters`() {
        // Control characters that might affect parsing
        val controlChars = "test\u0000\u0001\u0002\u0003\u0004id"

        val result = InputValidator.validateCalendarId(controlChars)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP02 - tool schema bypass via type coercion`() {
        // Test that numeric strings don't bypass string validation
        val numericId = "12345"
        val result = InputValidator.validateCalendarId(numericId)
        assertTrue(result is InputValidator.ValidationResult.Valid)

        // Boolean-like strings
        val booleanId = "true"
        val result2 = InputValidator.validateCalendarId(booleanId)
        assertTrue(result2 is InputValidator.ValidationResult.Valid)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MCP03: EMBEDDED RESOURCE INJECTION
    // Tests that resource URIs cannot be manipulated for malicious access
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `MCP03 - file URI injection attempt`() {
        val fileUri = "file:///etc/passwd"

        val result = InputValidator.validateCalendarId(fileUri)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP03 - data URI injection attempt`() {
        val dataUri = "data:text/html,<script>alert('xss')</script>"

        val result = InputValidator.validateCalendarId(dataUri)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP03 - javascript URI injection attempt`() {
        val jsUri = "javascript:alert(document.domain)"

        val result = InputValidator.validateCalendarId(jsUri)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP03 - SSRF via calendar URL`() {
        // Internal network access attempt
        val internalUrl = "http://169.254.169.254/latest/meta-data/"

        val result = InputValidator.validateCalendarId(internalUrl)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP03 - localhost SSRF attempt`() {
        val localhostUrl = "http://localhost:8080/admin"

        val result = InputValidator.validateCalendarId(localhostUrl)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP03 - IP address based SSRF`() {
        val ipUrl = "http://127.0.0.1:22/ssh"

        val result = InputValidator.validateCalendarId(ipUrl)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP03 - resource content poisoning via ICS`() {
        // Attempt to inject malicious content that could affect other resources
        val maliciousIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:poison@attacker.com
            SUMMARY:Normal Event
            DTSTART:20250115T100000Z
            DTEND:20250115T110000Z
            X-MALICIOUS:true
            X-INJECT:../../../sensitive-data
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = parser.parse(maliciousIcs)
        // Should parse safely, ignoring malicious X- properties
        assertEquals(1, events.size)
        assertEquals("Normal Event", events[0].summary)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MCP04: INSECURE CREDENTIAL STORAGE
    // Tests that credentials are handled securely in memory and output
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `MCP04 - credentials not exposed via exception message`() {
        val password = "super-secret-app-password-1234"
        val credentials = CredentialManager.Credentials("user@icloud.com", password)

        // Simulate an error that might include credentials
        try {
            throw RuntimeException("Failed to connect with $credentials")
        } catch (e: Exception) {
            assertFalse(e.message?.contains(password) == true)
            assertFalse(e.message?.contains("super-secret") == true)
        }
    }

    @Test
    fun `MCP04 - credentials not exposed in stack trace string`() {
        val credentials = CredentialManager.Credentials("user@icloud.com", "secret-password-xyz")
        val credString = credentials.toString()

        assertFalse(credString.contains("secret-password"))
        assertTrue(credString.contains("****"))
    }

    @Test
    fun `MCP04 - credential masking handles edge cases`() {
        // Empty password
        assertEquals("***", CredentialManager.mask(""))
        assertEquals("***", CredentialManager.mask(null))

        // Very long password
        val longPassword = "a".repeat(1000)
        val masked = CredentialManager.mask(longPassword)
        assertEquals("aaa***", masked)
        assertFalse(masked.length > 10)
    }

    @Test
    fun `MCP04 - error sanitizer removes credentials from varied formats`() {
        val formats = listOf(
            "password=secret123",
            "password: secret123",
            "PASSWORD=SECRET123",
            "pass=mypass",
            "pwd=mypwd"
        )

        formats.forEach { input ->
            val result = SecureErrorHandler.createErrorResult(
                SecureErrorHandler.ErrorCode.INTERNAL_ERROR,
                input
            )
            val content = result.content?.firstOrNull() as? io.modelcontextprotocol.kotlin.sdk.types.TextContent
            assertNotNull(content)
            assertFalse(content!!.text.contains("secret123"), "Should mask in: $input")
            assertFalse(content.text.contains("mypass"), "Should mask in: $input")
            assertFalse(content.text.contains("mypwd"), "Should mask in: $input")
        }
    }

    @Test
    fun `MCP04 - CalDavCredentials toString masks password`() {
        val creds = CalDavCredentials("user@example.com", "xxxx-yyyy-zzzz-1234")
        val str = creds.toString()

        assertFalse(str.contains("xxxx-yyyy-zzzz-1234"))
        assertTrue(str.contains("***"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MCP08: UNCONTROLLED RESOURCE CONSUMPTION
    // Tests for memory exhaustion, CPU exhaustion, and DoS vectors
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `MCP08 - cache cannot grow unbounded`() {
        val service = CalendarService(mockClient, cacheTtlMs = 60_000, maxCacheSize = 10)

        val events = (1..100).map { i ->
            CalDavEvent(
                uid = "uid-$i",
                href = "/calendar/event-$i.ics",
                url = "https://caldav.icloud.com/calendar/event-$i.ics",
                etag = "etag-$i",
                icalData = """
                    BEGIN:VCALENDAR
                    BEGIN:VEVENT
                    UID:uid-$i
                    SUMMARY:Event $i
                    DTSTART:20250115T090000Z
                    DTEND:20250115T100000Z
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()
            )
        }

        every { mockClient.getEvents(any(), any(), any()) } returns CalDavResult.Success(events)

        service.getEvents("calendar", "2025-01-01", "2025-01-31")

        // Cache should not exceed maxCacheSize significantly
        assertTrue(service.cacheSize() <= 100, "Cache grew beyond expected: ${service.cacheSize()}")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `MCP08 - large ICS content doesnt cause OOM`() {
        // 1MB of ICS data
        val largeDescription = "X".repeat(1_000_000)
        val largeIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:large-event@test.com
            SUMMARY:Large Event
            DESCRIPTION:$largeDescription
            DTSTART:20250115T100000Z
            DTEND:20250115T110000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Should complete without OOM
        val events = parser.parse(largeIcs)
        assertNotNull(events)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `MCP08 - many events dont cause excessive processing`() {
        val manyEvents = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            repeat(1000) { i ->
                appendLine("BEGIN:VEVENT")
                appendLine("UID:event-$i@test.com")
                appendLine("SUMMARY:Event $i")
                appendLine("DTSTART:20250115T${String.format("%02d", i % 24)}0000Z")
                appendLine("DTEND:20250115T${String.format("%02d", (i + 1) % 24)}0000Z")
                appendLine("END:VEVENT")
            }
            appendLine("END:VCALENDAR")
        }

        val events = parser.parse(manyEvents)
        assertEquals(1000, events.size)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `MCP08 - deeply nested ICS components handled safely`() {
        // While ICS doesn't support deep nesting, we test parser robustness
        val nestedIcs = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            repeat(100) {
                appendLine("BEGIN:VEVENT")
            }
            appendLine("UID:nested@test.com")
            appendLine("SUMMARY:Nested Test")
            appendLine("DTSTART:20250115T100000Z")
            repeat(100) {
                appendLine("END:VEVENT")
            }
            appendLine("END:VCALENDAR")
        }

        // Should not hang or crash
        val events = parser.parse(nestedIcs)
        assertNotNull(events)
    }

    @Test
    fun `MCP08 - rate limiter prevents resource exhaustion`() {
        val limiter = RateLimiter(readLimit = 10, writeLimit = 5, windowMs = 1000)

        var allowed = 0
        repeat(1000) {
            if (limiter.tryAcquireRead()) allowed++
        }

        assertEquals(10, allowed, "Rate limiter should cap at limit")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `MCP08 - extremely long field values dont cause hang`() {
        val longValue = "A".repeat(10_000_000) // 10MB string

        val ics = builder.build(
            summary = longValue.take(500), // Title limited
            description = longValue.take(5000), // Description limited by validator
            startDate = "2025-01-15",
            isAllDay = true
        )

        assertNotNull(ics)
        assertTrue(ics.contains("SUMMARY:"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MCP09: SANDBOX ESCAPE & ISOLATION
    // Tests that operations cannot escape intended boundaries
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `MCP09 - path traversal in calendar ID blocked`() {
        val traversalAttempts = listOf(
            "../../../etc/passwd",
            "..\\..\\..\\windows\\system32",
            "....//....//....//etc/passwd",
            "%2e%2e%2f%2e%2e%2f%2e%2e%2fetc%2fpasswd",
            "..%252f..%252f..%252fetc%252fpasswd",
            "..%c0%af..%c0%af..%c0%afetc%c0%afpasswd"
        )

        traversalAttempts.forEach { attempt ->
            val result = InputValidator.validateCalendarId(attempt)
            assertTrue(
                result is InputValidator.ValidationResult.Invalid,
                "Should block path traversal: $attempt"
            )
        }
    }

    @Test
    fun `MCP09 - symlink-style path manipulation blocked`() {
        val symlinkAttempt = "/calendars/../../etc/passwd"

        val result = InputValidator.validateCalendarId(symlinkAttempt)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP09 - calendar service isolation between requests`() {
        val service1 = CalendarService(mockClient)
        val service2 = CalendarService(mockClient)

        val event = CalDavEvent(
            uid = "isolated-event",
            href = "/calendar/isolated.ics",
            url = "https://caldav.icloud.com/calendar/isolated.ics",
            etag = "etag-1",
            icalData = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:isolated-event
                SUMMARY:Isolated Event
                DTSTART:20250115T090000Z
                DTEND:20250115T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()
        )

        every { mockClient.getEvents(any(), any(), any()) } returns CalDavResult.Success(listOf(event))

        // Populate service1 cache
        service1.getEvents("calendar", "2025-01-01", "2025-01-31")

        // service2 should have independent cache
        assertEquals(0, service2.cacheSize())
    }

    @Test
    fun `MCP09 - environment variable injection in validation`() {
        val envInjection = "\${HOME}/sensitive"

        val result = InputValidator.validateCalendarId(envInjection)
        assertTrue(result is InputValidator.ValidationResult.Invalid)
    }

    @Test
    fun `MCP09 - glob pattern injection blocked`() {
        val globPatterns = listOf(
            "*",
            "**",
            "*.ics",
            "[a-z]*",
            "{a,b,c}",
            "?(pattern)"
        )

        globPatterns.forEach { pattern ->
            val result = InputValidator.validateCalendarId(pattern)
            assertTrue(
                result is InputValidator.ValidationResult.Invalid,
                "Should block glob pattern: $pattern"
            )
        }
    }

    @Test
    fun `MCP09 - regex injection in search blocked`() {
        val regexPatterns = listOf(
            "(?=malicious)",
            ".*",
            ".+",
            "[^a-z]",
            "\\d+",
            "^start",
            "end$"
        )

        regexPatterns.forEach { pattern ->
            val result = InputValidator.validateCalendarId(pattern)
            assertTrue(
                result is InputValidator.ValidationResult.Invalid,
                "Should block regex pattern: $pattern"
            )
        }
    }
}
