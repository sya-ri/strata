package dev.s7a.strata.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies identity-based cleanup failure accumulation and suppression order.
 */
internal class FailureAccumulatorTest {
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
        assertEquals(listOf(existing, later), first.suppressed.toList())
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
        assertEquals(listOf(second), first.suppressed.toList())
    }

    @Test
    fun optionalFailurePreservesExistingSuppressionWithoutDuplicatingIt() {
        val first = IllegalStateException("first")
        val existing = IllegalStateException("existing")
        first.addSuppressed(existing)
        val accumulator = FailureAccumulator()

        accumulator.addOptional(first)

        assertSame(first, accumulator.first)
        assertEquals(listOf(existing), first.suppressed.toList())
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
        assertEquals(listOf(later, nested), first.suppressed.toList())
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
        assertEquals(listOf(later, nested, deepest), first.suppressed.toList())
    }

    @Test
    fun throwingWithoutARecordedFailureIsRejected() {
        val accumulator = FailureAccumulator()

        assertThrows(IllegalStateException::class.java) { accumulator.throwFirst() }
    }
}
