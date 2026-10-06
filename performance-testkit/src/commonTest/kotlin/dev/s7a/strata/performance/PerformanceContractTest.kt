package dev.s7a.strata.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression coverage for the shared collection and evidence contract.
 */
class PerformanceContractTest {
    @Test
    fun coverageRejectsNewFeaturesAndMissingHosts() {
        val existing = scenario("old", "existing", PerformanceHost.Jvm)
        assertFailsWith<IllegalArgumentException> {
            PerformanceCoverage(mapOf("existing" to setOf(PerformanceHost.Jvm), "new" to setOf(PerformanceHost.Jvm)), listOf(existing)).verify()
        }
        assertFailsWith<IllegalArgumentException> {
            PerformanceCoverage(mapOf("existing" to setOf(PerformanceHost.Jvm, PerformanceHost.Web)), listOf(existing)).verify()
        }
    }

    @Test
    fun coverageSelectsAffectedFeaturesAndRejectsMissingExecution() {
        val cases = listOf(scenario("first", "one", PerformanceHost.Jvm), scenario("second", "two", PerformanceHost.Web))
        val coverage = PerformanceCoverage(mapOf("one" to setOf(PerformanceHost.Jvm), "two" to setOf(PerformanceHost.Web)), cases)
        assertEquals(listOf("first"), coverage.select(setOf("one")).map(PerformanceScenario::id))
        assertEquals(2, coverage.select().size)
        assertFailsWith<IllegalArgumentException> { coverage.verifyCompleted(emptySet()) }
        coverage.verifyCompleted(setOf(Triple("first", PerformanceHost.Jvm, PerformancePhase.Idle)), setOf("one"))
        assertFailsWith<IllegalArgumentException> { coverage.select(setOf("unknown")) }
    }

    @Test
    fun duplicateAndUnknownScenariosFail() {
        val existing = scenario("same", "one", PerformanceHost.Jvm)
        assertFailsWith<IllegalArgumentException> {
            PerformanceCoverage(mapOf("one" to setOf(PerformanceHost.Jvm)), listOf(existing, existing)).verify()
        }
        assertFailsWith<IllegalArgumentException> {
            PerformanceCoverage(mapOf("two" to setOf(PerformanceHost.Jvm)), listOf(existing)).verify()
        }
    }

    @Test
    fun missingAndOverflowedMetricsCannotPass() {
        val expected = WorkExpectation(exact = mapOf("paint" to 0), maximum = mapOf("rows" to 8))
        expected.verify(mapOf("paint" to 0, "rows" to 7))
        assertFailsWith<IllegalStateException> { expected.verify(mapOf("paint" to 0)) }
        assertFailsWith<IllegalStateException> { expected.verify(mapOf("paint" to 0, "rows" to 9)) }
        assertFailsWith<IllegalStateException> { expected.verify(mapOf("paint" to 0, "rows" to 7), overflowed = true) }
    }

    @Test
    fun statisticsUseCompleteNonNegativeIntervals() {
        val values = mutableListOf(9L, 1L, 5L, 3L, 7L)
        val result = PerformanceDistribution.of(values)
        assertEquals(5, result.samples)
        assertEquals(5, result.p50)
        assertEquals(9, result.p95)
        assertEquals(25, result.total)
        assertEquals(9, values.first())
        assertFailsWith<IllegalArgumentException> { PerformanceDistribution.of(emptyList()) }
        assertFailsWith<IllegalArgumentException> { PerformanceDistribution.of(listOf(-1)) }
        assertFailsWith<IllegalArgumentException> { PerformancePlan(samples = 0) }
        assertFailsWith<IllegalArgumentException> { PerformancePlan(warmup = Int.MAX_VALUE, samples = 1) }
        assertFailsWith<IllegalArgumentException> { PerformanceDistribution.of(listOf(Long.MAX_VALUE, 1)) }
    }

    @Test
    fun registrationCannotOmitRequiredLifecyclePhases() {
        val existing = scenario("idle", "feature", PerformanceHost.Jvm)
        assertFailsWith<IllegalArgumentException> {
            PerformanceCoverage(
                mapOf("feature" to setOf(PerformanceHost.Jvm)),
                listOf(existing),
                mapOf("feature" to setOf(PerformancePhase.Idle, PerformancePhase.Release)),
            ).verify()
        }
    }

    @Test
    fun completeRepetitionsRejectDuplicatesChangedCollectorsAndMissingMetrics() {
        val coverage = PerformanceCoverage(mapOf("feature" to setOf(PerformanceHost.Jvm)), listOf(scenario("case", "feature", PerformanceHost.Jvm)))
        val runs = (0..2).map { evidence(it) }
        coverage.verifyEvidence(runs)
        assertFailsWith<IllegalArgumentException> { coverage.verifyEvidence(runs.take(2)) }
        assertFailsWith<IllegalArgumentException> { coverage.verifyEvidence(listOf(runs[0], runs[1], runs[1])) }
        assertFailsWith<IllegalArgumentException> { coverage.verifyEvidence(listOf(runs[0], runs[1], evidence(2, collector = "other"))) }
        assertFailsWith<IllegalArgumentException> { runs[0].requireComparable(evidence(0, metrics = mapOf("wall_ns" to null))) }
        runs[0].requireComparable(evidence(0, target = "candidate"))
    }

    private fun evidence(
        repetition: Int,
        collector: String = "collector",
        target: String = "baseline",
        metrics: Map<String, Long?> = mapOf("wall_ns" to 5L),
    ): PerformanceEvidence = PerformanceEvidence("case", PerformanceHost.Jvm, PerformancePhase.Idle, repetition, "fixed-v1", collector, target, mapOf("java" to "same"), metrics)

    private fun scenario(
        id: String,
        feature: String,
        host: PerformanceHost,
    ): PerformanceScenario = PerformanceScenario(id, setOf(feature), setOf(host), setOf(PerformancePhase.Idle), "fixed-v1")
}
