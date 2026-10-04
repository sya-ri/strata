package dev.s7a.strata.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Preserves formal presets and distinguishes narrow exploratory evidence on every shared target.
 */
internal class PerformanceProfileTest {
    @Test
    fun standardPreservesTheExactRegisteredPlanAndMatrix() {
        val standard = PerformancePlan(warmup = 30, samples = 600, repetitions = 3)
        assertSame(standard, PerformanceProfile.Standard.plan(standard))
        assertEquals(listOf(1, 2, 3, 4), PerformanceProfile.Standard.viewports(listOf(1, 2, 3, 4)))
        assertEquals("fixed-v1", PerformanceProfile.Standard.workloadId("fixed-v1"))
        assertEquals(PerformanceProfile.Standard, PerformanceProfile.fromQuickFlag(null))
        assertEquals(PerformanceProfile.Standard, PerformanceProfile.fromQuickFlag("false"))
    }

    @Test
    fun quickCapsSamplesWithoutIncreasingSmallIntervalsOrChangingReadinessDeadline() {
        assertEquals(PerformanceProfile.Quick, PerformanceProfile.fromQuickFlag("true"))
        val standard = PerformancePlan(warmup = 30, samples = 600, repetitions = 3, preparationTimeoutMillis = 7000)
        assertEquals(PerformancePlan(3, 10, 1, 7000), PerformanceProfile.Quick.plan(standard))
        assertEquals(PerformancePlan(0, 1, 1), PerformanceProfile.Quick.plan(PerformancePlan(0, 1)))
        assertEquals(listOf(3), PerformanceProfile.Quick.viewports(listOf(1, 2, 3, 4)))
        assertEquals(listOf("medium"), PerformanceProfile.Quick.viewports(listOf("small", "medium", "large")))
        assertEquals(listOf(1), PerformanceProfile.Quick.viewports(listOf(1)))
        assertEquals("fixed-v1-quick", PerformanceProfile.Quick.workloadId("fixed-v1"))
    }

    @Test
    fun malformedFlagsAndEmptyOrDuplicatedMatricesFailBeforeCollection() {
        listOf("TRUE", "yes", "", " true").forEach { flag -> assertFailsWith<IllegalArgumentException> { PerformanceProfile.fromQuickFlag(flag) } }
        for (profile in PerformanceProfile.entries) {
            assertFailsWith<IllegalArgumentException> { profile.viewports(emptyList<Int>()) }
            assertFailsWith<IllegalArgumentException> { profile.viewports(listOf(1, 1)) }
            assertFailsWith<IllegalArgumentException> { profile.workloadId(" ") }
        }
    }
}
