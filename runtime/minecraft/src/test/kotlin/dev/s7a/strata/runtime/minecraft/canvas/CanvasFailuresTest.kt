package dev.s7a.strata.runtime.minecraft.canvas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies lazy native failure storage without changing graph identity, callback completion or suppression order.
 */
internal class CanvasFailuresTest {
    @Test
    fun successfulEmptySingleAndManyCallbacksNeverCreateIdentityStorage() {
        val storage = CanvasFailures::class.java.getDeclaredField("seen").apply { isAccessible = true }
        listOf(0, 1, 128).forEach { count ->
            val failures = CanvasFailures()
            var completed = 0
            assertNull(storage.get(failures))

            repeat(count) { failures.attempt { completed += 1 } }
            failures.throwIfPresent()

            assertEquals(count, completed)
            assertNull(storage.get(failures))
        }
    }

    @Test
    fun firstFailureCreatesOneIdentitySetAndRethrowsTheExactInstance() {
        val storage = CanvasFailures::class.java.getDeclaredField("seen").apply { isAccessible = true }
        val first = EqualFailure()
        val second = EqualFailure()
        val failures = CanvasFailures()
        var completed = 0

        failures.attempt { throw first }
        val identities = storage.get(failures)
        assertNotNull(identities)
        listOf(first, second, second).forEach { failure ->
            failures.attempt {
                completed += 1
                throw failure
            }
        }
        failures.attempt { completed += 1 }

        assertEquals(4, completed)
        assertSame(identities, storage.get(failures))
        assertEquals(1, first.suppressed.size)
        assertSame(second, first.suppressed.single())
        repeat(2) { assertSame(first, assertThrows(EqualFailure::class.java) { failures.throwIfPresent() }) }
    }

    @Test
    fun initialAndLaterCyclicGraphsAreRememberedWithoutFlattening() {
        val first = EqualFailure()
        val existing = EqualFailure()
        val later = EqualFailure()
        val nested = EqualFailure()
        val last = EqualFailure()
        first.addSuppressed(existing)
        existing.addSuppressed(first)
        later.addSuppressed(nested)
        nested.addSuppressed(later)
        val failures = CanvasFailures(first)
        val storage = CanvasFailures::class.java.getDeclaredField("seen").apply { isAccessible = true }
        assertNotNull(storage.get(failures))

        listOf(first, existing, later, nested, later, last).forEach(failures::add)

        assertEquals(3, first.suppressed.size)
        listOf(existing, later, last).forEachIndexed { index, failure -> assertSame(failure, first.suppressed[index]) }
        assertSame(nested, later.suppressed.single())
        assertSame(first, assertThrows(EqualFailure::class.java) { failures.throwIfPresent() })
    }

    @Test
    fun nestedCleanupCompletesBeforeItsFailureReachesTheOuterCollector() {
        val first = EqualFailure()
        val second = EqualFailure()
        val third = EqualFailure()
        val outer = CanvasFailures()
        var completed = 0

        outer.attempt {
            val inner = CanvasFailures()
            listOf(first, second, first).forEach { failure ->
                inner.attempt {
                    completed += 1
                    throw failure
                }
            }
            inner.throwIfPresent()
        }
        outer.attempt { throw second }
        outer.attempt { throw third }
        outer.attempt { completed += 1 }

        assertEquals(4, completed)
        assertEquals(2, first.suppressed.size)
        assertSame(second, first.suppressed[0])
        assertSame(third, first.suppressed[1])
        assertSame(first, assertThrows(EqualFailure::class.java) { outer.throwIfPresent() })
    }

    private class EqualFailure : RuntimeException("equal failure") {
        override fun equals(other: Any?): Boolean = other is EqualFailure

        override fun hashCode(): Int = 0
    }
}
