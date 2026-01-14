package org.onekash.mcp.calendar.cancellation

import io.modelcontextprotocol.kotlin.sdk.types.CancelledNotification
import org.onekash.mcp.calendar.logging.McpLogger
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages cancellation of in-progress operations.
 *
 * MCP supports cancellation via notifications/cancelled. When a client sends this notification,
 * the server should:
 * 1. Stop processing the cancelled request
 * 2. Free associated resources
 * 3. Not send a response for the cancelled request
 *
 * This manager tracks active operations and provides a way to check if an operation
 * has been cancelled.
 *
 * Usage:
 * ```kotlin
 * val manager = CancellationManager()
 * val token = manager.startOperation("request-123")
 *
 * // In processing loop:
 * if (token.isCancelled()) {
 *     // Clean up and return
 * }
 *
 * // When done:
 * manager.completeOperation("request-123")
 * ```
 */
class CancellationManager(
    private val logger: McpLogger? = null
) {
    // Active operations keyed by request ID
    private val activeOperations = ConcurrentHashMap<String, CancellationToken>()

    // Completed operations (for handling race conditions)
    private val completedOperations = ConcurrentHashMap<String, Long>()

    // Cleanup interval for completed operations (5 minutes)
    private val completedRetentionMs = 5 * 60 * 1000L

    /**
     * Start tracking an operation.
     *
     * @param requestId The unique request ID
     * @return A CancellationToken that can be checked for cancellation
     */
    fun startOperation(requestId: String): CancellationToken {
        val token = CancellationToken(requestId)
        activeOperations[requestId] = token

        logger?.log(
            LoggingLevel.Debug,
            "Started tracking operation: $requestId",
            mapOf("requestId" to requestId)
        )

        return token
    }

    /**
     * Handle a cancellation notification from the client.
     *
     * @param requestId The ID of the request to cancel
     * @param reason Optional reason for cancellation
     */
    fun handleCancellation(requestId: String, reason: String?) {
        // Check if already completed (race condition)
        if (completedOperations.containsKey(requestId)) {
            logger?.log(
                LoggingLevel.Debug,
                "Ignoring cancellation for completed operation: $requestId",
                mapOf("requestId" to requestId, "reason" to reason)
            )
            return
        }

        // Check if operation is active
        val token = activeOperations[requestId]
        if (token == null) {
            logger?.log(
                LoggingLevel.Debug,
                "Ignoring cancellation for unknown operation: $requestId",
                mapOf("requestId" to requestId, "reason" to reason)
            )
            return
        }

        // Mark as cancelled
        token.cancel()

        logger?.log(
            LoggingLevel.Info,
            "Operation cancelled: $requestId",
            mapOf("requestId" to requestId, "reason" to reason)
        )
    }

    /**
     * Handle a CancelledNotification from the SDK.
     */
    fun handleNotification(notification: CancelledNotification) {
        val requestId = notification.requestId.toString()
        val reason = notification.reason
        handleCancellation(requestId, reason)
    }

    /**
     * Mark an operation as completed.
     * Should be called when the operation finishes successfully or with an error.
     *
     * @param requestId The request ID that completed
     */
    fun completeOperation(requestId: String) {
        activeOperations.remove(requestId)
        completedOperations[requestId] = System.currentTimeMillis()

        logger?.log(
            LoggingLevel.Debug,
            "Operation completed: $requestId",
            mapOf("requestId" to requestId)
        )

        // Cleanup old completed operations
        cleanupCompleted()
    }

    /**
     * Check if an operation has been cancelled.
     *
     * @param requestId The request ID to check
     * @return true if cancelled, false otherwise
     */
    fun isCancelled(requestId: String): Boolean {
        return activeOperations[requestId]?.isCancelled() ?: false
    }

    /**
     * Get the cancellation token for an operation.
     *
     * @param requestId The request ID
     * @return The CancellationToken, or null if not found
     */
    fun getToken(requestId: String): CancellationToken? {
        return activeOperations[requestId]
    }

    /**
     * Get count of active operations.
     */
    fun activeCount(): Int = activeOperations.size

    /**
     * Clean up old completed operation records.
     */
    private fun cleanupCompleted() {
        val now = System.currentTimeMillis()
        completedOperations.entries.removeIf { (_, completedAt) ->
            now - completedAt > completedRetentionMs
        }
    }

    /**
     * Clear all tracked operations. Useful for testing.
     */
    fun clear() {
        activeOperations.clear()
        completedOperations.clear()
    }
}

/**
 * Token that can be checked for cancellation status.
 * Thread-safe for concurrent access.
 */
class CancellationToken(
    val requestId: String
) {
    private val cancelled = AtomicBoolean(false)
    private val createdAt = System.currentTimeMillis()

    /**
     * Check if this operation has been cancelled.
     */
    fun isCancelled(): Boolean = cancelled.get()

    /**
     * Mark this operation as cancelled.
     * Internal use only - called by CancellationManager.
     */
    internal fun cancel() {
        cancelled.set(true)
    }

    /**
     * Get the age of this token in milliseconds.
     */
    fun ageMs(): Long = System.currentTimeMillis() - createdAt

    /**
     * Throw CancellationException if cancelled.
     * Convenient for checking in loops.
     */
    fun checkCancelled() {
        if (cancelled.get()) {
            throw OperationCancelledException(requestId)
        }
    }
}

/**
 * Exception thrown when an operation is cancelled.
 */
class OperationCancelledException(
    val requestId: String,
    message: String = "Operation cancelled: $requestId"
) : Exception(message)
