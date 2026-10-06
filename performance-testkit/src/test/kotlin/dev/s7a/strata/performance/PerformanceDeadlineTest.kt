package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deadline inheritance and wrap boundaries, with a deterministic clock rather than sleeps.
 */
class PerformanceDeadlineTest {
    @Test
    fun preservesAnEarlierAndAnExpiredParentDeadline() {
        var now = 0L
        val overall = PerformanceDeadline(10) { now }
        now = 9_000_000L
        val section = overall.child(60_000)
        now = 10_000_000L
        assertTrue(section.isWithin())
        now += 1
        assertFalse(section.isWithin())
        assertFalse(overall.child(60_000).isWithin())
        assertEquals(now, overall.elapsedNanos())
    }

    @Test
    fun keepsRelativeOrderWhenTheClockWraps() {
        var now = Long.MAX_VALUE - 1_000_000L
        val deadline = PerformanceDeadline(2) { now }
        now += 2_000_000L
        assertTrue(deadline.isWithin())
        assertEquals(2_000_000L, deadline.elapsedNanos())
        now += 1
        assertFalse(deadline.isWithin())
    }

    @Test
    fun rejectsInvalidDurationsBeforeReadingTheClock() {
        assertFailsWith<IllegalArgumentException> { PerformanceDeadline(-1) { error("Clock must not be acquired") } }
        assertFailsWith<ArithmeticException> { PerformanceDeadline(Long.MAX_VALUE) { error("Clock must not be acquired") } }
    }
}
