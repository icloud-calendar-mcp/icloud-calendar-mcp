package org.onekash.mcp.calendar.error

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.onekash.mcp.calendar.security.CredentialManager

/**
 * Secure error handling for MCP tool responses.
 *
 * Security requirements (from MCP spec):
 * - Don't leak sensitive information in error messages
 * - Don't expose internal paths or stack traces
 * - Provide helpful but safe error messages
 */
object SecureErrorHandler {

    /**
     * Error codes for categorized responses.
     */
    enum class ErrorCode(val code: String, val httpStatus: Int) {
        VALIDATION_ERROR("VALIDATION_ERROR", 400),
        AUTHENTICATION_ERROR("AUTH_ERROR", 401),
        AUTHORIZATION_ERROR("FORBIDDEN", 403),
        NOT_FOUND("NOT_FOUND", 404),
        CONFLICT("CONFLICT", 409),
        RATE_LIMITED("RATE_LIMITED", 429),
        CALDAV_ERROR("CALDAV_ERROR", 502),
        INTERNAL_ERROR("INTERNAL_ERROR", 500)
    }

    /**
     * Hierarchical error types with context, retryability, and suggested actions.
     * Kotlin `when` exhaustiveness enforced at compile time.
     */
    sealed class CalendarError(
        val code: String,
        val httpStatus: Int,
        val isRetryable: Boolean,
        val suggestedAction: String
    ) {
        data class Validation(
            val field: String? = null,
            val detail: String = "Invalid input"
        ) : CalendarError("VALIDATION_ERROR", 400, false, "Check input parameters and retry")

        data class Authentication(
            val reason: String = "Authentication failed"
        ) : CalendarError("AUTH_ERROR", 401, false, "Verify credentials and retry")

        data class Authorization(
            val calendarName: String? = null,
            val reason: String = "Access denied"
        ) : CalendarError("FORBIDDEN", 403, false, "Check calendar sharing permissions")

        data class NotFound(
            val resourceType: String = "resource",
            val resourceId: String? = null
        ) : CalendarError("NOT_FOUND", 404, false, "Verify the resource exists")

        data class Conflict(
            val eventTitle: String? = null,
            val reason: String = "Resource was modified"
        ) : CalendarError("CONFLICT", 409, false, "Refresh and retry with updated data")

        data class RateLimited(
            val retryAfterMs: Long = 0
        ) : CalendarError("RATE_LIMITED", 429, true, "Wait and retry after the indicated delay")

        data class Network(
            val reason: String = "Network error"
        ) : CalendarError("NETWORK_ERROR", 0, true, "Check network connection and retry")

        data class Timeout(
            val timeoutMs: Long = 0
        ) : CalendarError("TIMEOUT", 408, true, "Retry the request")

        data class ServerError(
            val serverCode: Int = 500,
            val reason: String = "Server error"
        ) : CalendarError("SERVER_ERROR", serverCode, true, "Retry after a short delay")

        data class CalDav(
            val reason: String = "Calendar service error"
        ) : CalendarError("CALDAV_ERROR", 502, true, "Retry or check server status")

        data class Internal(
            val reason: String = "Internal error"
        ) : CalendarError("INTERNAL_ERROR", 500, false, "Contact support if the issue persists")

        /** Convert to ErrorCode for backward compatibility */
        fun toErrorCode(): ErrorCode = when (this) {
            is Validation -> ErrorCode.VALIDATION_ERROR
            is Authentication -> ErrorCode.AUTHENTICATION_ERROR
            is Authorization -> ErrorCode.AUTHORIZATION_ERROR
            is NotFound -> ErrorCode.NOT_FOUND
            is Conflict -> ErrorCode.CONFLICT
            is RateLimited -> ErrorCode.RATE_LIMITED
            is Network, is Timeout, is ServerError, is CalDav -> ErrorCode.CALDAV_ERROR
            is Internal -> ErrorCode.INTERNAL_ERROR
        }

        /** Safe message for external display */
        fun safeMessage(): String = when (this) {
            is Validation -> detail
            is Authentication -> reason
            is Authorization -> reason
            is NotFound -> "$resourceType not found"
            is Conflict -> reason
            is RateLimited -> "Rate limit exceeded"
            is Network -> reason
            is Timeout -> "Request timed out"
            is ServerError -> reason
            is CalDav -> reason
            is Internal -> "An internal error occurred"
        }
    }

    /**
     * Create a secure error result for MCP tools.
     */
    fun createErrorResult(
        code: ErrorCode,
        message: String,
        details: String? = null
    ): CallToolResult {
        val safeMessage = sanitizeErrorMessage(message)
        val safeDetails = details?.let { sanitizeErrorMessage(it) }

        val errorJson = buildString {
            append("""{"success": false, "error": {""")
            append(""""code": "${code.code}", """)
            append(""""message": "$safeMessage"""")
            if (safeDetails != null) {
                append(""", "details": "$safeDetails"""")
            }
            append("}}")
        }

        return CallToolResult(
            content = listOf(TextContent(text = errorJson)),
            isError = true
        )
    }

    /**
     * Create error result for validation failures.
     */
    fun validationError(errors: List<String>): CallToolResult {
        val message = if (errors.size == 1) {
            errors.first()
        } else {
            "Multiple validation errors"
        }

        val details = if (errors.size > 1) {
            errors.joinToString("; ")
        } else {
            null
        }

        return createErrorResult(ErrorCode.VALIDATION_ERROR, message, details)
    }

    /**
     * Create error result for authentication failures.
     */
    fun authenticationError(reason: String = "Authentication failed"): CallToolResult {
        return createErrorResult(
            ErrorCode.AUTHENTICATION_ERROR,
            reason
        )
    }

    /**
     * Create error result for service layer errors.
     * Maps HTTP-like status codes to error types.
     */
    fun serviceError(code: Int, message: String): CallToolResult {
        val errorCode = when (code) {
            400 -> ErrorCode.VALIDATION_ERROR
            401 -> ErrorCode.AUTHENTICATION_ERROR
            403 -> ErrorCode.AUTHORIZATION_ERROR
            404 -> ErrorCode.NOT_FOUND
            409 -> ErrorCode.CONFLICT
            429 -> ErrorCode.RATE_LIMITED
            in 500..599 -> ErrorCode.CALDAV_ERROR
            else -> ErrorCode.INTERNAL_ERROR
        }
        return createErrorResult(errorCode, message)
    }

    /**
     * Create error result for CalDAV/network errors.
     */
    fun caldavError(exception: Exception): CallToolResult {
        // Don't expose internal details - just the error type
        val safeMessage = when {
            exception.message?.contains("401") == true -> "Authentication failed with iCloud"
            exception.message?.contains("403") == true -> "Access denied to calendar"
            exception.message?.contains("404") == true -> "Calendar or event not found"
            exception.message?.contains("timeout", ignoreCase = true) == true -> "Connection to iCloud timed out"
            exception.message?.contains("connect", ignoreCase = true) == true -> "Unable to connect to iCloud"
            else -> "Calendar service error"
        }

        return createErrorResult(ErrorCode.CALDAV_ERROR, safeMessage)
    }

    /**
     * Create error result for rate limiting.
     */
    fun rateLimitError(retryAfterMs: Long): CallToolResult {
        val retrySeconds = (retryAfterMs / 1000).coerceAtLeast(1)
        return createErrorResult(
            ErrorCode.RATE_LIMITED,
            "Rate limit exceeded. Please retry in $retrySeconds seconds."
        )
    }

    /**
     * Create error result for internal errors.
     * Logs the full exception but returns a safe message.
     */
    fun internalError(exception: Exception): CallToolResult {
        // Log full exception for debugging (in production, use proper logging)
        System.err.println("Internal error: ${exception.javaClass.simpleName}")
        // Don't log the message as it might contain sensitive data

        return createErrorResult(
            ErrorCode.INTERNAL_ERROR,
            "An internal error occurred. Please try again."
        )
    }

    /**
     * Map exception to CalendarError type.
     */
    fun fromException(exception: Exception): CalendarError {
        return when (exception) {
            is java.net.SocketTimeoutException -> CalendarError.Timeout()
            is java.net.UnknownHostException -> CalendarError.Network(reason = "Unknown host: ${exception.message}")
            is javax.net.ssl.SSLException -> CalendarError.Authentication(reason = "Certificate error")
            is java.net.ConnectException -> CalendarError.Network(reason = "Connection refused")
            else -> CalendarError.Internal(reason = exception.message ?: "Unknown error")
        }
    }

    /**
     * Map HTTP status code to CalendarError type.
     */
    fun fromHttpCode(code: Int, message: String = ""): CalendarError {
        return when (code) {
            401 -> CalendarError.Authentication()
            403 -> CalendarError.Authorization()
            404 -> CalendarError.NotFound()
            409 -> CalendarError.Conflict()
            429 -> CalendarError.RateLimited()
            in 500..599 -> CalendarError.ServerError(serverCode = code, reason = message.ifBlank { "Server error" })
            else -> CalendarError.Internal(reason = message.ifBlank { "Unexpected error ($code)" })
        }
    }

    /**
     * Mask email address for safe display.
     * "john.doe@icloud.com" -> "joh***@***.com"
     */
    fun maskEmail(email: String): String {
        val atIdx = email.indexOf('@')
        if (atIdx < 0) {
            // No @ sign, mask most of the string
            return if (email.length <= 3) "***" else email.take(3) + "***"
        }

        val local = email.substring(0, atIdx)
        val domain = email.substring(atIdx + 1)

        val maskedLocal = if (local.length <= 3) "***" else local.take(3) + "***"

        // Show only TLD
        val dotIdx = domain.lastIndexOf('.')
        val maskedDomain = if (dotIdx >= 0) {
            "***" + domain.substring(dotIdx)
        } else {
            "***"
        }

        return "$maskedLocal@$maskedDomain"
    }

    // Maximum message length to prevent DoS via regex processing
    private const val MAX_MESSAGE_LENGTH = 2_000

    // Precompiled regex patterns for performance
    private val CREDENTIAL_PATTERNS = listOf(
        Regex("""password[=:]\s*\S+""", RegexOption.IGNORE_CASE) to "password=****",
        Regex("""passwd[=:]\s*\S+""", RegexOption.IGNORE_CASE) to "passwd=****",
        Regex("""(?<![a-z])pass[=:]\s*\S+""", RegexOption.IGNORE_CASE) to "pass=****",
        Regex("""(?<![a-z])pwd[=:]\s*\S+""", RegexOption.IGNORE_CASE) to "pwd=****",
        Regex("""token[=:]\s*\S+""", RegexOption.IGNORE_CASE) to "token=****",
        Regex("""secret[=:]\s*\S+""", RegexOption.IGNORE_CASE) to "secret=****",
        Regex("""api[_-]?key[=:]\s*\S+""", RegexOption.IGNORE_CASE) to "api_key=****"
    )

    private val PATH_PATTERNS = listOf(
        Regex("""/Users/\w+/""") to "/****/",
        Regex("""/home/\w+/""") to "/****/",
        Regex("""C:\\Users\\\w+\\""") to "C:\\****\\"
    )

    private val EMAIL_PATTERN = Regex("""([a-zA-Z0-9]{1,3})[a-zA-Z0-9._+-]*@[\w.-]+\.\w+""")

    /**
     * Sanitize error message to remove sensitive information.
     */
    private fun sanitizeErrorMessage(message: String): String {
        // Truncate very long messages to prevent DoS
        var sanitized = if (message.length > MAX_MESSAGE_LENGTH) {
            message.take(MAX_MESSAGE_LENGTH) + "... [truncated]"
        } else {
            message
        }

        // Remove potential credentials using precompiled patterns
        CREDENTIAL_PATTERNS.forEach { (pattern, replacement) ->
            sanitized = pattern.replace(sanitized, replacement)
        }

        // Remove potential file paths
        PATH_PATTERNS.forEach { (pattern, replacement) ->
            sanitized = pattern.replace(sanitized, Regex.escapeReplacement(replacement))
        }

        // Remove email addresses (except first 3 chars)
        sanitized = EMAIL_PATTERN.replace(sanitized) { match ->
            "${match.groupValues[1]}***@***.***"
        }

        // Escape JSON special characters (must be last to not interfere with regex)
        sanitized = sanitized
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", "")
            .replace("\t", " ")

        return sanitized
    }

    /**
     * Wrap a tool execution with error handling.
     */
    inline fun <T> safeExecute(
        operation: String,
        block: () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (e: Exception) {
            System.err.println("Error in $operation: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }
}