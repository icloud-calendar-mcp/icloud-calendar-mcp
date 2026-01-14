package org.onekash.mcp.calendar.ratelimit

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe rate limiter with separate limits for read and write operations.
 *
 * Uses a sliding window approach where counters reset when the window expires.
 * Default limits per MCP security guidelines: 60 reads/min, 20 writes/min.
 *
 * @param readLimit Maximum read operations per window
 * @param writeLimit Maximum write operations per window
 * @param windowMs Window duration in milliseconds
 */
class RateLimiter(
    private val readLimit: Int = 60,
    private val writeLimit: Int = 20,
    private val windowMs: Long = 60_000
) {
    private val readCount = AtomicInteger(0)
    private val writeCount = AtomicInteger(0)
    private val windowStart = AtomicLong(System.currentTimeMillis())

    /**
     * Try to acquire a read token.
     * @return true if allowed, false if rate limited
     */
    fun tryAcquireRead(): Boolean {
        checkAndResetWindow()
        return if (readCount.get() < readLimit) {
            readCount.incrementAndGet() <= readLimit
        } else {
            false
        }
    }

    /**
     * Try to acquire a write token.
     * @return true if allowed, false if rate limited
     */
    fun tryAcquireWrite(): Boolean {
        checkAndResetWindow()
        return if (writeCount.get() < writeLimit) {
            writeCount.incrementAndGet() <= writeLimit
        } else {
            false
        }
    }

    /**
     * Acquire read with detailed status.
     */
    fun acquireReadWithStatus(): RateLimitResult {
        checkAndResetWindow()
        val allowed = tryAcquireRead()
        val remaining = (readLimit - readCount.get()).coerceAtLeast(0)
        val retryAfter = if (!allowed) {
            windowMs - (System.currentTimeMillis() - windowStart.get())
        } else {
            0
        }
        return RateLimitResult(
            allowed = allowed,
            remaining = remaining,
            retryAfterMs = retryAfter.coerceAtLeast(0)
        )
    }

    /**
     * Acquire write with detailed status.
     */
    fun acquireWriteWithStatus(): RateLimitResult {
        checkAndResetWindow()
        val allowed = tryAcquireWrite()
        val remaining = (writeLimit - writeCount.get()).coerceAtLeast(0)
        val retryAfter = if (!allowed) {
            windowMs - (System.currentTimeMillis() - windowStart.get())
        } else {
            0
        }
        return RateLimitResult(
            allowed = allowed,
            remaining = remaining,
            retryAfterMs = retryAfter.coerceAtLeast(0)
        )
    }

    /**
     * Get current rate limit status.
     */
    fun getStatus(): RateLimitStatus {
        checkAndResetWindow()
        return RateLimitStatus(
            readCount = readCount.get(),
            writeCount = writeCount.get(),
            readRemaining = (readLimit - readCount.get()).coerceAtLeast(0),
            writeRemaining = (writeLimit - writeCount.get()).coerceAtLeast(0),
            windowResetMs = windowMs - (System.currentTimeMillis() - windowStart.get())
        )
    }

    /**
     * Reset all counters and window.
     */
    fun reset() {
        readCount.set(0)
        writeCount.set(0)
        windowStart.set(System.currentTimeMillis())
    }

    /**
     * Check if window has expired and reset if needed.
     */
    private fun checkAndResetWindow() {
        val now = System.currentTimeMillis()
        val start = windowStart.get()
        if (now - start >= windowMs) {
            // Window expired - try to reset
            if (windowStart.compareAndSet(start, now)) {
                readCount.set(0)
                writeCount.set(0)
            }
        }
    }
}

/**
 * Result of a rate limit check.
 */
data class RateLimitResult(
    val allowed: Boolean,
    val remaining: Int,
    val retryAfterMs: Long
)

/**
 * Current rate limit status.
 */
data class RateLimitStatus(
    val readCount: Int,
    val writeCount: Int,
    val readRemaining: Int,
    val writeRemaining: Int,
    val windowResetMs: Long
)
