package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.SamplingCoverageFixture.Boundary
import dev.s7a.strata.quality.benchmark.SamplingCoverageFixture.Geometry

/**
 * Measures all 90 original geometry operations with the shared owner-thread CPU, allocation and wall meter.
 * Each row uses its unchanged JMH trial and operation; reflection and independent controls stay outside samples.
 * Only the current row's scene and final returned result survive sampling, with no native resources or frame history.
 */
public object SamplingCoverageOperationReport : JvmOperationReportAdapter {
    private data class Case(
        val geometry: Geometry,
        val boundary: Boundary,
    ) {
        private val method =
            when (boundary) {
                Boundary.ExactMapMetadata -> "exactMapMetadata"
                Boundary.CompleteCpuFramePreparation -> "completeCpuFramePreparation"
            }

        val row =
            JvmOperationReportRow(
                "${geometry.name}-${boundary.name}",
                "${SamplingCoverageBenchmark::class.java.name}.$method",
                mapOf("geometry" to geometry.name),
                when (boundary) {
                    Boundary.ExactMapMetadata -> "One genuine fresh exact-map constructor, including all immutable metadata ownership."
                    Boundary.CompleteCpuFramePreparation -> "One genuine complete CPU frame preparation, including admission, composition, factors, maps and source requests."
                },
                control = geometry == Geometry.OneByOneD1,
            )
    }

    private val cases = Geometry.entries.flatMap { geometry -> Boundary.entries.map { boundary -> Case(geometry, boundary) } }

    override val rows: List<JvmOperationReportRow> = cases.map { it.row }

    /**
     * Binds every original geometry method and parameter to one independent owner-thread row, without adding a sampling mode.
     */
    public fun verifyRows() {
        check(SamplingCoverageBenchmark.operationReport() === this)
        val expected = JmhWorkloadInventory.capture(listOf(SamplingCoverageBenchmark::class.java), setOf("avgt"))
        val actual = rows.map { JmhPerformanceRunner.workloadIdentity(it.benchmark, "avgt", it.parameters) }
        check(actual.size == 90 && actual.toSet() == expected && rows.map { it.id }.distinct().size == 90)
        check(rows.count { it.control } == 2 && rows.all { it.operationsPerSample == 1 })
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        SamplingCoverageWorkEvidence.verify(SamplingCoverageRuntime())
        val benchmark = SamplingCoverageBenchmark()
        return cases.associate { case ->
            val scene = SamplingCoverageBenchmark.Scene().apply { geometry = case.geometry }
            scene.setup()
            val sample =
                JvmPerformanceRunner.measure<Any>(case.row.id, plan) {
                    when (case.boundary) {
                        Boundary.ExactMapMetadata -> benchmark.exactMapMetadata(scene)
                        Boundary.CompleteCpuFramePreparation -> benchmark.completeCpuFramePreparation(scene)
                    }
                }
            case.row.id to sample.evidence
        }
    }
}
