package dev.s7a.strata.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies identity-based cleanup failure accumulation and suppression order.
 */
internal class FailureAccumulatorTest {
    @Test
    fun successfulCallbacksDoNotRecordAFailure() {
        val accumulator = FailureAccumulator()
        var completed = 0

        repeat(128) {
            accumulator.capture { completed += 1 }
            accumulator.addOptional(null)
            accumulator.throwIfPresent()
        }

        assertEquals(128, completed)
        assertNull(accumulator.first)
    }

    @Test
    fun valueEqualFailuresRemainDistinctAndEveryCallbackRuns() {
        val first = EqualFailure()
        val second = EqualFailure()
        val accumulator = FailureAccumulator()
        var completed = 0

        listOf(first, first, second, second).forEach { failure ->
            accumulator.capture {
                completed += 1
                throw failure
            }
        }
        accumulator.capture { completed += 1 }

        assertEquals(5, completed)
        assertSame(first, accumulator.first)
        assertEquals(1, first.suppressedExceptions.size)
        assertSame(second, first.suppressedExceptions.single())
        repeat(2) {
            assertSame(first, assertFailsWith<EqualFailure> { accumulator.throwIfPresent() })
        }
    }

    @Test
    fun initialCyclicGraphAndDirectLaterGraphsKeepTheirExistingSemantics() {
        val first = EqualFailure()
        val existing = EqualFailure()
        val later = EqualFailure()
        val nested = EqualFailure()
        first.addSuppressed(existing)
        existing.addSuppressed(first)
        later.addSuppressed(nested)
        val accumulator = FailureAccumulator(first)

        accumulator.add(existing)
        accumulator.add(later)
        accumulator.add(nested)
        accumulator.addOptional(later)

        assertSame(first, accumulator.first)
        val suppressed = first.suppressedExceptions
        assertEquals(3, suppressed.size)
        listOf(existing, later, nested).forEachIndexed { index, failure -> assertSame(failure, suppressed[index]) }
        assertSame(first, assertFailsWith<EqualFailure> { accumulator.throwFirst() })
    }

    @Test
    fun deduplicatesIdentityAndPreservesInitialSuppressionOrder() {
        val first = IllegalStateException("first")
        val existing = IllegalStateException("existing")
        val later = IllegalStateException("later")
        first.addSuppressed(existing)
        val accumulator = FailureAccumulator(first)

        accumulator.add(first)
        accumulator.add(existing)
        accumulator.add(later)
        accumulator.add(later)
        accumulator.addOptional(existing)

        assertSame(first, accumulator.first)
        assertEquals(listOf(existing, later), first.suppressedExceptions)
    }

    @Test
    fun deduplicatesTheFirstFailureAndSkipsSelfSuppression() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        val accumulator = FailureAccumulator()

        accumulator.add(first)
        accumulator.add(first)
        accumulator.add(second)
        accumulator.add(second)

        assertSame(first, accumulator.first)
        assertEquals(listOf(second), first.suppressedExceptions)
    }

    @Test
    fun optionalFailurePreservesExistingSuppressionWithoutDuplicatingIt() {
        val first = IllegalStateException("first")
        val existing = IllegalStateException("existing")
        first.addSuppressed(existing)
        val accumulator = FailureAccumulator()

        accumulator.addOptional(first)

        assertSame(first, accumulator.first)
        assertEquals(listOf(existing), first.suppressedExceptions)
    }

    @Test
    fun optionalLaterFailureFlattensItsExistingSuppressionOntoTheFirstFailure() {
        val first = IllegalStateException("first")
        val later = IllegalStateException("later")
        val nested = IllegalStateException("nested")
        later.addSuppressed(nested)
        val accumulator = FailureAccumulator(first)

        accumulator.addOptional(later)

        assertSame(first, accumulator.first)
        assertEquals(listOf(later, nested), first.suppressedExceptions)
    }

    @Test
    fun optionalLaterFailureFlattensNestedSuppressionByIdentityInTraversalOrder() {
        val first = IllegalStateException("first")
        val later = IllegalStateException("later")
        val nested = IllegalStateException("nested")
        val deepest = IllegalStateException("deepest")
        nested.addSuppressed(deepest)
        later.addSuppressed(nested)
        deepest.addSuppressed(later)
        val accumulator = FailureAccumulator(first)

        accumulator.addOptional(later)

        assertSame(first, accumulator.first)
        assertEquals(listOf(later, nested, deepest), first.suppressedExceptions)
    }

    @Test
    fun throwingWithoutARecordedFailureIsRejected() {
        val accumulator = FailureAccumulator()

        assertFailsWith<IllegalStateException> { accumulator.throwFirst() }
    }

    private class EqualFailure : RuntimeException("equal failure") {
        override fun equals(other: Any?): Boolean = other is EqualFailure

        override fun hashCode(): Int = 0
    }
}
