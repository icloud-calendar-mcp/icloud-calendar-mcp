package org.onekash.mcp.calendar.logging

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP-compliant logger that sends log messages to connected clients.
 *
 * Features:
 * - 8 standard syslog severity levels (RFC 5424)
 * - Rate limiting (10 logs/sec, 50 burst)
 * - Sensitive data sanitization
 * - Dynamic level control via logging/setLevel
 * - Thread-safe
 */
class McpLogger(
    private val server: Server,
    private val sessionId: String = "",
    private val loggerName: String = "icloud-calendar-mcp",
    private val rateLimit: Int = 10,
    private val burstLimit: Int = 50,
    private val windowMs: Long = 1000
) {
    // Minimum log level (can be changed at runtime)
    private val minLevel = AtomicReference(LoggingLevel.Info)

    // Rate limiting state
    private val logCount = AtomicInteger(0)
    private val burstCount = AtomicInteger(0)
    private val windowStart = AtomicLong(System.currentTimeMillis())

    // Patterns for sensitive data that should be sanitized
    private val keyValuePatterns = listOf(
        "password" to Regex("""password["']?\s*[:=]\s*["']?([^"'\s,}]+)["']?""", RegexOption.IGNORE_CASE),
        "passwd" to Regex("""passwd["']?\s*[:=]\s*["']?([^"'\s,}]+)["']?""", RegexOption.IGNORE_CASE),
        "pass" to Regex("""(?<![a-z])pass["']?\s*[:=]\s*["']?([^"'\s,}]+)["']?""", RegexOption.IGNORE_CASE),
        "pwd" to Regex("""(?<![a-z])pwd["']?\s*[:=]\s*["']?([^"'\s,}]+)["']?""", RegexOption.IGNORE_CASE),
        "token" to Regex("""token["']?\s*[:=]\s*["']?([^"'\s,}]+)["']?""", RegexOption.IGNORE_CASE),
        "api_key" to Regex("""api[_-]?key["']?\s*[:=]\s*["']?([^"'\s,}]+)["']?""", RegexOption.IGNORE_CASE),
        "secret" to Regex("""secret["']?\s*[:=]\s*["']?([^"'\s,}]+)["']?""", RegexOption.IGNORE_CASE),
        "authorization" to Regex("""authorization["']?\s*[:=]\s*["']?([^"'\s,}]+(?:\s+[^"'\s,}]+)*)["']?""", RegexOption.IGNORE_CASE)
    )
    private val emailPattern = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")

    /**
     * Set minimum log level. Messages below this level will be dropped.
     */
    fun setLevel(level: LoggingLevel) {
        minLevel.set(level)
    }

    /**
     * Get current minimum log level.
     */
    fun getLevel(): LoggingLevel = minLevel.get()

    // Convenience methods for each log level
    fun debug(message: String, data: Map<String, Any?>? = null) = log(LoggingLevel.Debug, message, data)
    fun info(message: String, data: Map<String, Any?>? = null) = log(LoggingLevel.Info, message, data)
    fun notice(message: String, data: Map<String, Any?>? = null) = log(LoggingLevel.Notice, message, data)
    fun warning(message: String, data: Map<String, Any?>? = null) = log(LoggingLevel.Warning, message, data)
    fun error(message: String, data: Map<String, Any?>? = null) = log(LoggingLevel.Error, message, data)
    fun critical(message: String, data: Map<String, Any?>? = null) = log(LoggingLevel.Critical, message, data)
    fun alert(message: String, data: Map<String, Any?>? = null) = log(LoggingLevel.Alert, message, data)
    fun emergency(message: String, data: Map<String, Any?>? = null) = log(LoggingLevel.Emergency, message, data)

    /**
     * Log a message at the specified level.
     */
    fun log(level: LoggingLevel, message: String, data: Map<String, Any?>? = null) {
        // Check if level is high enough
        if (level.ordinal < minLevel.get().ordinal) {
            return
        }

        // Check rate limit
        if (!acquireLogToken()) {
            return
        }

        // Sanitize message and data
        val sanitizedMessage = sanitize(message)
        val jsonData = buildJsonObject {
            put("message", sanitizedMessage)
            data?.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, sanitize(value))
                    is Number -> put(key, value)
                    is Boolean -> put(key, value)
                    null -> put(key, null as String?)
                    else -> put(key, sanitize(value.toString()))
                }
            }
        }

        // Send notification
        val notification = LoggingMessageNotification(
            params = LoggingMessageNotificationParams(
                level = level,
                data = jsonData,
                logger = loggerName
            )
        )

        try {
            runBlocking {
                server.sendLoggingMessage(sessionId, notification)
            }
        } catch (e: Exception) {
            // Silently drop failed log messages to prevent cascading failures
            System.err.println("Failed to send log message: ${e.message}")
        }
    }

    /**
     * Sanitize a string by removing sensitive data patterns.
     */
    fun sanitize(input: String): String {
        var result = input

        // Handle key-value patterns (password:, token:, etc.)
        keyValuePatterns.forEach { (name, pattern) ->
            result = pattern.replace(result) { match ->
                // Replace the matched value with [REDACTED]
                val fullMatch = match.value
                val captured = match.groupValues.getOrNull(1) ?: ""
                if (captured.isNotEmpty()) {
                    fullMatch.replace(captured, "[REDACTED]")
                } else {
                    "$name:[REDACTED]"
                }
            }
        }

        // Handle email pattern separately
        result = emailPattern.replace(result, "[REDACTED]")

        return result
    }

    /**
     * Try to acquire a log token (rate limiting).
     * Returns true if logging is allowed, false if rate limited.
     */
    private fun acquireLogToken(): Boolean {
        val now = System.currentTimeMillis()
        val start = windowStart.get()

        // Check if window has expired
        if (now - start >= windowMs) {
            if (windowStart.compareAndSet(start, now)) {
                logCount.set(0)
                burstCount.set(0)
            }
        }

        // Check burst limit first
        val currentBurst = burstCount.incrementAndGet()
        if (currentBurst > burstLimit) {
            return false
        }

        // Check rate limit
        val currentRate = logCount.incrementAndGet()
        return currentRate <= rateLimit || currentBurst <= burstLimit
    }

    /**
     * Reset rate limiting counters. Useful for testing.
     */
    fun resetRateLimit() {
        logCount.set(0)
        burstCount.set(0)
        windowStart.set(System.currentTimeMillis())
    }

    companion object {
        /**
         * Convert string to LoggingLevel.
         */
        fun parseLevel(level: String): LoggingLevel? {
            return when (level.lowercase()) {
                "debug" -> LoggingLevel.Debug
                "info" -> LoggingLevel.Info
                "notice" -> LoggingLevel.Notice
                "warning", "warn" -> LoggingLevel.Warning
                "error" -> LoggingLevel.Error
                "critical" -> LoggingLevel.Critical
                "alert" -> LoggingLevel.Alert
                "emergency" -> LoggingLevel.Emergency
                else -> null
            }
        }
    }
}
