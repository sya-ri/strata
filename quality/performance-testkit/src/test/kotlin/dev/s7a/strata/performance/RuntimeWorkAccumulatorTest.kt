package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Collector-capacity regressions independent of Minecraft timing or application behavior.
 */
class RuntimeWorkAccumulatorTest {
    @Test
    fun longUpdatesDiscardRetiredRecordsWithoutLosingCounts() {
        val owner = NativeMeterFixture()
        RuntimeWorkAccumulator(owner).use { interval ->
            repeat(60) {
                owner.retiredRecords += 1024
                owner.work += 1024L
                interval.capture()
            }
            val evidence = interval.snapshot()
            assertEquals(61_440L, evidence.getAsJsonObject("counts").get("NodeCreate").asLong)
            assertEquals(60L, evidence.get("diagnostic_samples").asLong)
            assertEquals(0, owner.retiredRecords)
            evidence.getAsJsonObject("counts").addProperty("NodeCreate", 0)
            assertEquals(
                61_440L,
                interval
                    .snapshot()
                    .getAsJsonObject("counts")
                    .get("NodeCreate")
                    .asLong,
            )
        }
        assertTrue(owner.monitorClosed)
    }

    @Test
    fun oneOverflowingSamplePoisonsTheWholeInterval() {
        val owner = NativeMeterFixture()
        RuntimeWorkAccumulator(owner).use { interval ->
            owner.retiredRecords = 4096
            assertFailsWith<IllegalStateException> { interval.capture() }
            owner.retiredRecords = 0
            assertFailsWith<IllegalStateException> { interval.capture() }
            assertFailsWith<IllegalStateException> { interval.snapshot() }
        }
        assertTrue(owner.monitorClosed)
    }

    @Test
    fun counterOverflowCannotBeReportedAsSuccessfulEvidence() {
        val owner = NativeMeterFixture()
        RuntimeWorkAccumulator(owner).use { interval ->
            owner.work = Long.MAX_VALUE
            interval.capture()
            owner.work = 1L
            assertFailsWith<ArithmeticException> { interval.capture() }
            assertFailsWith<IllegalStateException> { interval.snapshot() }
        }
        assertTrue(owner.monitorClosed)
    }
}
