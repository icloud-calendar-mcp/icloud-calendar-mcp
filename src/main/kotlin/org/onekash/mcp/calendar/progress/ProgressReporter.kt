package org.onekash.mcp.calendar.progress

import org.onekash.mcp.calendar.logging.McpLogger
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import java.util.concurrent.atomic.AtomicReference

/**
 * Progress reporter for long-running operations.
 *
 * MCP supports progress reporting via notifications/progress with progressToken.
 * SDK v0.8.1 doesn't have direct progress notification support, so this implementation:
 * 1. Tracks progress internally for monitoring
 * 2. Logs progress updates via McpLogger as a fallback
 * 3. Is designed to be extensible when SDK adds progress support
 *
 * Usage:
 * ```kotlin
 * val reporter = ProgressReporter(progressToken = "request-123", total = 100.0, logger = logger)
 * reporter.report(25.0, "Fetching calendars...")
 * reporter.report(50.0, "Parsing events...")
 * reporter.complete("Done!")
 * ```
 */
class ProgressReporter(
    private val progressToken: String? = null,
    private val total: Double? = null,
    private val logger: McpLogger? = null,
    private val operationName: String = "operation"
) {
    // Current progress state
    private val currentProgress = AtomicReference(ProgressState(0.0, null))

    // Callback for custom progress handling
    private var progressCallback: ((Double, Double?, String?) -> Unit)? = null

    /**
     * Progress state snapshot.
     */
    data class ProgressState(
        val progress: Double,
        val message: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Report progress update.
     *
     * @param progress Current progress value (must increase with each call)
     * @param message Optional human-readable progress message
     */
    fun report(progress: Double, message: String? = null) {
        val state = ProgressState(progress, message)
        currentProgress.set(state)

        // Invoke callback if set
        progressCallback?.invoke(progress, total, message)

        // Log progress if logger available and token provided
        if (logger != null && progressToken != null) {
            val percentStr = total?.let { " (${(progress / it * 100).toInt()}%)" } ?: ""
            logger.log(
                LoggingLevel.Debug,
                "Progress$percentStr: ${message ?: operationName}",
                mapOf(
                    "progressToken" to progressToken,
                    "progress" to progress,
                    "total" to total,
                    "operation" to operationName
                )
            )
        }
    }

    /**
     * Report completion.
     *
     * @param message Optional completion message
     */
    fun complete(message: String? = null) {
        val finalProgress = total ?: currentProgress.get().progress
        report(finalProgress, message ?: "Complete")

        if (logger != null && progressToken != null) {
            logger.log(
                LoggingLevel.Info,
                "Operation complete: ${message ?: operationName}",
                mapOf(
                    "progressToken" to progressToken,
                    "operation" to operationName
                )
            )
        }
    }

    /**
     * Report an error that stopped progress.
     *
     * @param error Error message
     */
    fun error(error: String) {
        // Update state with error message
        val state = ProgressState(currentProgress.get().progress, error)
        currentProgress.set(state)

        if (logger != null && progressToken != null) {
            logger.log(
                LoggingLevel.Error,
                "Operation failed: $error",
                mapOf(
                    "progressToken" to progressToken,
                    "operation" to operationName,
                    "lastProgress" to currentProgress.get().progress
                )
            )
        }
    }

    /**
     * Get current progress state.
     */
    fun getState(): ProgressState = currentProgress.get()

    /**
     * Set a callback for progress updates.
     * Useful for integrating with other systems.
     */
    fun onProgress(callback: (progress: Double, total: Double?, message: String?) -> Unit) {
        this.progressCallback = callback
    }

    /**
     * Check if this reporter has an active progress token.
     */
    fun hasToken(): Boolean = progressToken != null

    /**
     * Get the progress token if available.
     */
    fun getToken(): String? = progressToken

    companion object {
        /**
         * Create a no-op reporter that doesn't track or log anything.
         * Useful when no progress tracking is needed.
         */
        fun noop(): ProgressReporter = ProgressReporter()

        /**
         * Create a reporter from MCP request metadata.
         *
         * @param meta The _meta object from a CallToolRequest
         * @param logger Optional logger for progress updates
         * @param operationName Name of the operation for logging
         */
        fun fromMeta(
            meta: Map<String, Any?>?,
            logger: McpLogger? = null,
            operationName: String = "operation",
            total: Double? = null
        ): ProgressReporter {
            val token = meta?.get("progressToken")?.toString()
            return ProgressReporter(
                progressToken = token,
                total = total,
                logger = logger,
                operationName = operationName
            )
        }
    }
}

/**
 * Extension function to create a progress reporter for a specific operation.
 */
fun McpLogger.progressReporter(
    progressToken: String? = null,
    total: Double? = null,
    operationName: String = "operation"
): ProgressReporter = ProgressReporter(
    progressToken = progressToken,
    total = total,
    logger = this,
    operationName = operationName
)
