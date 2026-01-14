package org.onekash.mcp.calendar.ratelimit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for RateLimiter - enforces per-minute limits on operations.
 */
class RateLimiterTest {

    private lateinit var rateLimiter: RateLimiter

    @BeforeEach
    fun setup() {
        rateLimiter = RateLimiter(
            readLimit = 60,
            writeLimit = 20,
            windowMs = 60_000
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // READ OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `allow read operations under limit`() {
        repeat(60) {
            assertTrue(rateLimiter.tryAcquireRead())
        }
    }

    @Test
    fun `deny read operations over limit`() {
        repeat(60) {
            assertTrue(rateLimiter.tryAcquireRead())
        }
        // 61st should be denied
        assertFalse(rateLimiter.tryAcquireRead())
    }

    @Test
    fun `read operations return remaining count`() {
        val result = rateLimiter.acquireReadWithStatus()
        assertTrue(result.allowed)
        assertEquals(59, result.remaining)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WRITE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `allow write operations under limit`() {
        repeat(20) {
            assertTrue(rateLimiter.tryAcquireWrite())
        }
    }

    @Test
    fun `deny write operations over limit`() {
        repeat(20) {
            assertTrue(rateLimiter.tryAcquireWrite())
        }
        // 21st should be denied
        assertFalse(rateLimiter.tryAcquireWrite())
    }

    @Test
    fun `write operations return remaining count`() {
        val result = rateLimiter.acquireWriteWithStatus()
        assertTrue(result.allowed)
        assertEquals(19, result.remaining)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WINDOW RESET
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `reset clears all counts`() {
        repeat(60) { rateLimiter.tryAcquireRead() }
        repeat(20) { rateLimiter.tryAcquireWrite() }

        assertFalse(rateLimiter.tryAcquireRead())
        assertFalse(rateLimiter.tryAcquireWrite())

        rateLimiter.reset()

        assertTrue(rateLimiter.tryAcquireRead())
        assertTrue(rateLimiter.tryAcquireWrite())
    }

    @Test
    fun `window expires after time passes`() {
        val shortWindow = RateLimiter(readLimit = 5, writeLimit = 2, windowMs = 100)

        repeat(5) { assertTrue(shortWindow.tryAcquireRead()) }
        assertFalse(shortWindow.tryAcquireRead())

        // Wait for window to expire
        Thread.sleep(150)

        assertTrue(shortWindow.tryAcquireRead())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STATUS INFORMATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `get status shows current counts`() {
        repeat(10) { rateLimiter.tryAcquireRead() }
        repeat(5) { rateLimiter.tryAcquireWrite() }

        val status = rateLimiter.getStatus()

        assertEquals(10, status.readCount)
        assertEquals(5, status.writeCount)
        assertEquals(50, status.readRemaining)
        assertEquals(15, status.writeRemaining)
    }

    @Test
    fun `status includes retry after when limited`() {
        repeat(60) { rateLimiter.tryAcquireRead() }

        val result = rateLimiter.acquireReadWithStatus()

        assertFalse(result.allowed)
        assertTrue(result.retryAfterMs > 0)
        assertTrue(result.retryAfterMs <= 60_000)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // THREAD SAFETY
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `concurrent reads respect limit`() {
        val threads = (1..100).map {
            Thread { rateLimiter.tryAcquireRead() }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val status = rateLimiter.getStatus()
        assertEquals(60, status.readCount)  // At most 60 should succeed
    }

    @Test
    fun `concurrent writes respect limit`() {
        val threads = (1..50).map {
            Thread { rateLimiter.tryAcquireWrite() }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val status = rateLimiter.getStatus()
        assertEquals(20, status.writeCount)  // At most 20 should succeed
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `zero limit always denies`() {
        val strictLimiter = RateLimiter(readLimit = 0, writeLimit = 0, windowMs = 60_000)

        assertFalse(strictLimiter.tryAcquireRead())
        assertFalse(strictLimiter.tryAcquireWrite())
    }

    @Test
    fun `high limit works correctly`() {
        val generousLimiter = RateLimiter(readLimit = 10000, writeLimit = 1000, windowMs = 60_000)

        repeat(1000) { assertTrue(generousLimiter.tryAcquireRead()) }
        repeat(500) { assertTrue(generousLimiter.tryAcquireWrite()) }
    }

    @Test
    fun `read and write limits are independent`() {
        // Exhaust reads
        repeat(60) { rateLimiter.tryAcquireRead() }
        assertFalse(rateLimiter.tryAcquireRead())

        // Writes should still work
        assertTrue(rateLimiter.tryAcquireWrite())
    }
}
