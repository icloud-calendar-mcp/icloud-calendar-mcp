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