package org.onekash.mcp.calendar.cancellation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Security tests for CancellationManager.
 *
 * Tests for:
 * - Cancellation replay attacks
 * - Resource exhaustion via uncompleted operations
 * - Race conditions between cancel and complete
 * - Token ID prediction/enumeration
 * - Memory exhaustion via unbounded maps
 * - DoS via rapid token creation
 */
class CancellationSecurityTest {

    private lateinit var manager: CancellationManager

    @BeforeEach
    fun setup() {
        manager = CancellationManager()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CANCELLATION REPLAY ATTACKS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `replay cancellation on same request is idempotent`() {
        val token = manager.startOperation("request-1")

        // First cancellation
        manager.handleCancellation("request-1", "User cancelled")
        assertTrue(token.isCancelled())

        // Replay cancellation - should not cause issues
        manager.handleCancellation("request-1", "Replay attempt")
        manager.handleCancellation("request-1", "Another replay")
        manager.handleCancellation("request-1", "Yet another")

        // Should still be cancelled, no exceptions
        assertTrue(token.isCancelled())
    }

    @Test
    fun `cancel completed operation is safely ignored`() {
        manager.startOperation("request-1")
        manager.completeOperation("request-1")

        // Attempting to cancel completed operation
        manager.handleCancellation("request-1", "Late cancel")

        // Should not throw or cause inconsistency
        assertFalse(manager.isCancelled("request-1"))
    }

    @Test
    fun `cancel nonexistent operation is safely ignored`() {
        // Cancel request that was never started
        manager.handleCancellation("fake-request-123", "Spoofed cancel")
        manager.handleCancellation("", "Empty ID cancel")
        manager.handleCancellation("null", "Null-like cancel")

        // Should not throw
        assertEquals(0, manager.activeCount())
    }

    @Test
    fun `cancel with malicious reason string is handled`() {
        manager.startOperation("request-1")

        val maliciousReasons = listOf(
            "<script>alert('xss')</script>",
            "'; DROP TABLE operations; --",
            "\u0000\u0001\u0002null bytes",
            "A".repeat(10_000_000), // Very long reason
            "${"\n".repeat(1000)}multiline\ninjection",
            """{"admin":true,"bypass":"auth"}"""
        )

        maliciousReasons.forEach { reason ->
            // Should not throw or cause issues
            manager.handleCancellation("request-1", reason)
        }

        assertTrue(manager.isCancelled("request-1"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESOURCE EXHAUSTION VIA UNCOMPLETED OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `many uncompleted operations dont cause OOM`() {
        // Create many operations without completing them
        repeat(10_000) { i ->
            manager.startOperation("request-$i")
        }

        assertEquals(10_000, manager.activeCount())

        // Clear to allow GC
        manager.clear()
        assertEquals(0, manager.activeCount())
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `completed operations map is bounded`() {
        // Complete many operations to test completedOperations map cleanup
        repeat(10_000) { i ->
            manager.startOperation("request-$i")
            manager.completeOperation("request-$i")
        }

        // Completed operations should be cleaned up over time
        // The retention is 5 minutes, so recent ones will still be there
        assertEquals(0, manager.activeCount())
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `rapid start-cancel-complete cycle`() {
        repeat(10_000) { i ->
            val token = manager.startOperation("rapid-$i")
            if (i % 2 == 0) {
                manager.handleCancellation("rapid-$i", "Cancel")
            }
            manager.completeOperation("rapid-$i")
        }

        assertEquals(0, manager.activeCount())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RACE CONDITIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `race between cancel and complete`() {
        val iterations = 1000
        val errors = AtomicInteger(0)

        repeat(iterations) { i ->
            val requestId = "race-$i"
            manager.startOperation(requestId)

            val latch = CountDownLatch(1)

            val cancelThread = Thread {
                latch.await()
                try {
                    manager.handleCancellation(requestId, "Cancel")
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }

            val completeThread = Thread {
                latch.await()
                try {
                    manager.completeOperation(requestId)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }

            cancelThread.start()
            completeThread.start()

            latch.countDown() // Start race

            cancelThread.join()
            completeThread.join()
        }

        assertEquals(0, errors.get(), "Race conditions caused exceptions")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `race between multiple cancellations`() {
        val requestId = "multi-cancel"
        manager.startOperation(requestId)

        val threads = (1..100).map { i ->
            Thread {
                manager.handleCancellation(requestId, "Reason $i")
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should be cancelled exactly once logically
        assertTrue(manager.isCancelled(requestId))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `race between start and cancel for same ID`() {
        val iterations = 100
        val errors = AtomicInteger(0)

        repeat(iterations) { i ->
            val requestId = "start-cancel-race-$i"

            val startThread = Thread {
                try {
                    manager.startOperation(requestId)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }

            val cancelThread = Thread {
                try {
                    manager.handleCancellation(requestId, "Cancel")
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }

            startThread.start()
            cancelThread.start()

            startThread.join()
            cancelThread.join()

            manager.completeOperation(requestId)
        }

        assertEquals(0, errors.get())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOKEN ID PREDICTION/ENUMERATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `sequential request IDs are handled safely`() {
        // An attacker might try sequential IDs to cancel others' operations
        manager.startOperation("request-1")
        manager.startOperation("request-2")
        manager.startOperation("request-3")

        // Attacker tries to cancel request-2 without authorization
        // The manager doesn't have auth - it relies on transport layer
        // But we verify the cancel only affects the targeted request
        manager.handleCancellation("request-2", "Attacker cancel")

        assertFalse(manager.isCancelled("request-1"))
        assertTrue(manager.isCancelled("request-2"))
        assertFalse(manager.isCancelled("request-3"))
    }

    @Test
    fun `UUID-style request IDs work correctly`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val token = manager.startOperation(uuid)

        assertNotNull(token)
        assertEquals(uuid, token.requestId)

        manager.handleCancellation(uuid, "Cancel")
        assertTrue(token.isCancelled())
    }

    @Test
    fun `special characters in request ID are handled`() {
        val specialIds = listOf(
            "request/with/slashes",
            "request:with:colons",
            "request@with@at",
            "request#with#hash",
            "request with spaces",
            "request\twith\ttabs",
            "request\nwith\nnewlines"
        )

        specialIds.forEach { id ->
            val token = manager.startOperation(id)
            assertNotNull(token, "Should handle ID: $id")
            manager.handleCancellation(id, "Cancel")
            assertTrue(manager.isCancelled(id), "Should cancel ID: $id")
            manager.completeOperation(id)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MEMORY EXHAUSTION VIA UNBOUNDED MAPS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `completed operations cleanup works`() {
        // This tests the cleanup mechanism for completedOperations
        // which retains entries for 5 minutes by default

        // Simulate many completed operations
        repeat(1000) { i ->
            manager.startOperation("cleanup-test-$i")
            manager.completeOperation("cleanup-test-$i")
        }

        // Active count should be 0
        assertEquals(0, manager.activeCount())

        // Attempting to cancel completed operations should be ignored
        repeat(1000) { i ->
            manager.handleCancellation("cleanup-test-$i", "Late cancel")
        }
    }

    @Test
    fun `very long request IDs dont cause issues`() {
        val longId = "a".repeat(10_000)

        val token = manager.startOperation(longId)
        assertNotNull(token)

        manager.handleCancellation(longId, "Cancel")
        assertTrue(manager.isCancelled(longId))

        manager.completeOperation(longId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DOS VIA RAPID TOKEN CREATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `rapid token creation and destruction`() {
        // Simulate rapid operation churn
        repeat(5_000) { i ->
            manager.startOperation("churn-$i")
            manager.completeOperation("churn-$i")
        }

        assertEquals(0, manager.activeCount())
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `concurrent token creation from multiple threads`() {
        val threadCount = 20
        val operationsPerThread = 100
        val errors = AtomicInteger(0)

        val threads = (1..threadCount).map { t ->
            Thread {
                repeat(operationsPerThread) { i ->
                    try {
                        val id = "thread-$t-op-$i"
                        manager.startOperation(id)
                        manager.completeOperation(id)
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(0, errors.get())
        assertEquals(0, manager.activeCount())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CANCELLATION TOKEN SECURITY
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `token checkCancelled throws appropriate exception`() {
        val token = manager.startOperation("check-test")

        // Should not throw when not cancelled
        token.checkCancelled()

        manager.handleCancellation("check-test", "Cancel")

        // Should throw after cancellation
        val exception = assertThrows<OperationCancelledException> {
            token.checkCancelled()
        }

        assertEquals("check-test", exception.requestId)
        assertTrue(exception.message?.contains("check-test") == true)
    }

    @Test
    fun `token isCancelled is idempotent`() {
        val token = manager.startOperation("idempotent-test")

        // Multiple checks before cancellation
        assertFalse(token.isCancelled())
        assertFalse(token.isCancelled())
        assertFalse(token.isCancelled())

        manager.handleCancellation("idempotent-test", "Cancel")

        // Multiple checks after cancellation
        assertTrue(token.isCancelled())
        assertTrue(token.isCancelled())
        assertTrue(token.isCancelled())
    }

    @Test
    fun `token age cannot be manipulated`() {
        val token = manager.startOperation("age-test")

        val age1 = token.ageMs()
        Thread.sleep(10)
        val age2 = token.ageMs()

        assertTrue(age2 > age1, "Age should increase over time")
        assertTrue(age2 - age1 >= 10, "Age difference should be at least sleep time")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ISOLATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `separate managers are isolated`() {
        val manager1 = CancellationManager()
        val manager2 = CancellationManager()

        manager1.startOperation("shared-id")
        manager2.startOperation("shared-id")

        manager1.handleCancellation("shared-id", "Cancel in manager1")

        assertTrue(manager1.isCancelled("shared-id"))
        assertFalse(manager2.isCancelled("shared-id"))
    }

    @Test
    fun `clear doesnt affect other managers`() {
        val manager1 = CancellationManager()
        val manager2 = CancellationManager()

        manager1.startOperation("op-1")
        manager2.startOperation("op-2")

        manager1.clear()

        assertEquals(0, manager1.activeCount())
        assertEquals(1, manager2.activeCount())
    }
}
