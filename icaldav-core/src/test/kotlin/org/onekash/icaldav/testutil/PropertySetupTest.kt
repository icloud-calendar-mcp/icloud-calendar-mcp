package org.onekash.icaldav.testutil

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Proves the property harness: checkAll under JUnit 5, seed reporting, seed and iteration flags. */
class PropertySetupTest {

    @Test
    fun `checkAll runs every iteration`() = runBlocking {
        val config = PropertyRuns.config(iterations = 25)
        var runs = 0
        checkAll(config, Arb.int()) { runs++ }
        assertEquals(config.iterations, runs)
    }

    @Test
    fun `a failing property reports its seed`() {
        val config = PropertyRuns.config(iterations = 25)
        val failure = assertFailsWith<AssertionError> {
            runBlocking { checkAll(config, Arb.int(0..9)) { n -> assertTrue(n < 0) } }
        }
        val message = failure.message.orEmpty()
        assertTrue(message.contains("seed ${config.seed}"), message)
    }

    @Test
    fun `the same seed draws the same inputs`() {
        // Pin the seed: under -PpropertySeed=random every config() call would draw a new one.
        withProperty(PropertyRuns.SEED_PROPERTY, "424242") {
            fun draw(): List<Int> = runBlocking {
                val seen = mutableListOf<Int>()
                checkAll(PropertyRuns.config(iterations = 20), Arb.int()) { seen += it }
                seen
            }
            assertEquals(draw(), draw())
        }
    }

    @Test
    fun `the seed property replaces the default seed`() {
        withProperty(PropertyRuns.SEED_PROPERTY, null) {
            assertEquals(PropertyRuns.DEFAULT_SEED, PropertyRuns.seed())
        }
        withProperty(PropertyRuns.SEED_PROPERTY, "12345") {
            assertEquals(12345L, PropertyRuns.seed())
            assertEquals(12345L, PropertyRuns.config(iterations = 1).seed)
        }
    }

    @Test
    fun `the iterations property multiplies each property's iterations`() {
        withProperty(PropertyRuns.ITERATIONS_PROPERTY, "3") {
            assertEquals(30, PropertyRuns.config(iterations = 10).iterations)
        }
    }

    private fun withProperty(key: String, value: String?, block: () -> Unit) {
        val previous = System.getProperty(key)
        try {
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            block()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}
