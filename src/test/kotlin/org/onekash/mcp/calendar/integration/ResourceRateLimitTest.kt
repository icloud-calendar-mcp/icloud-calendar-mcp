package org.onekash.mcp.calendar.integration

import org.onekash.mcp.calendar.ratelimit.RateLimiter
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * Tests for resource rate limiting.
 */
class ResourceRateLimitTest {

    @Test
    fun `resource rate limiter allows requests within limit`() {
        val limiter = RateLimiter(readLimit = 5, writeLimit = 2, windowMs = 60_000)

        // Should allow 5 reads
        repeat(5) {
            assertTrue(limiter.tryAcquireRead(), "Read $it should be allowed")
        }
    }

    @Test
    fun `resource rate limiter blocks requests exceeding limit`() {
        val limiter = RateLimiter(readLimit = 3, writeLimit = 2, windowMs = 60_000)

        // Use up the limit
        repeat(3) { limiter.tryAcquireRead() }

        // 4th request should be blocked
        assertFalse(limiter.tryAcquireRead(), "4th read should be blocked")
    }

    @Test
    fun `resource rate limiter returns retry info when blocked`() {
        val limiter = RateLimiter(readLimit = 1, writeLimit = 1, windowMs = 60_000)

        // Use up the limit
        limiter.tryAcquireRead()

        // Get status on blocked request
        val result = limiter.acquireReadWithStatus()
        assertFalse(result.allowed, "Request should be blocked")
        assertTrue(result.retryAfterMs > 0, "Should provide retry time")
        assertEquals(0, result.remaining, "No requests should remain")
    }
}
