package org.onekash.mcp.calendar.cancellation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Tests for CancellationManager.
 */
class CancellationManagerTest {

    private lateinit var manager: CancellationManager

    @BeforeEach
    fun setup() {
        manager = CancellationManager()
    }

    @Test
    fun `startOperation returns token`() {
        val token = manager.startOperation("request-1")

        assertNotNull(token)
        assertEquals("request-1", token.requestId)
        assertFalse(token.isCancelled())
    }

    @Test
    fun `handleCancellation cancels active operation`() {
        manager.startOperation("request-1")

        assertFalse(manager.isCancelled("request-1"))

        manager.handleCancellation("request-1", "User requested")

        assertTrue(manager.isCancelled("request-1"))
    }

    @Test
    fun `handleCancellation ignores unknown operations`() {
        // Should not throw
        manager.handleCancellation("unknown-request", "Test")

        assertFalse(manager.isCancelled("unknown-request"))
    }

    @Test
    fun `handleCancellation ignores completed operations`() {
        manager.startOperation("request-1")
        manager.completeOperation("request-1")

        // Should not throw and should be ignored
        manager.handleCancellation("request-1", "Late cancellation")

        // No way to check completed operation status, but it shouldn't throw
    }

    @Test
    fun `completeOperation removes from active`() {
        manager.startOperation("request-1")
        assertEquals(1, manager.activeCount())

        manager.completeOperation("request-1")
        assertEquals(0, manager.activeCount())
    }

    @Test
    fun `getToken returns token for active operation`() {
        manager.startOperation("request-1")

        val token = manager.getToken("request-1")
        assertNotNull(token)
        assertEquals("request-1", token.requestId)
    }

    @Test
    fun `getToken returns null for unknown operation`() {
        val token = manager.getToken("unknown")
        assertNull(token)
    }

    @Test
    fun `cancellation token checkCancelled throws when cancelled`() {
        val token = manager.startOperation("request-1")

        // Should not throw initially
        token.checkCancelled()

        manager.handleCancellation("request-1", "Cancel")

        // Should throw after cancellation
        val exception = assertThrows<OperationCancelledException> {
            token.checkCancelled()
        }
        assertEquals("request-1", exception.requestId)
    }

    @Test
    fun `cancellation token tracks age`() {
        val token = manager.startOperation("request-1")

        Thread.sleep(10)

        assertTrue(token.ageMs() >= 10, "Token age should be at least 10ms")
    }

    @Test
    fun `clear removes all operations`() {
        manager.startOperation("request-1")
        manager.startOperation("request-2")
        assertEquals(2, manager.activeCount())

        manager.clear()
        assertEquals(0, manager.activeCount())
    }

    @Test
    fun `concurrent operations are tracked independently`() {
        manager.startOperation("request-1")
        manager.startOperation("request-2")
        manager.startOperation("request-3")

        manager.handleCancellation("request-2", "Cancel 2")

        assertFalse(manager.isCancelled("request-1"))
        assertTrue(manager.isCancelled("request-2"))
        assertFalse(manager.isCancelled("request-3"))
    }

    @Test
    fun `thread safety for concurrent access`() {
        val threads = (1..10).map { i ->
            Thread {
                val token = manager.startOperation("request-$i")
                Thread.sleep((Math.random() * 10).toLong())
                if (i % 2 == 0) {
                    manager.handleCancellation("request-$i", "Cancel")
                }
                Thread.sleep((Math.random() * 10).toLong())
                manager.completeOperation("request-$i")
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should complete without ConcurrentModificationException
        assertEquals(0, manager.activeCount())
    }
}
