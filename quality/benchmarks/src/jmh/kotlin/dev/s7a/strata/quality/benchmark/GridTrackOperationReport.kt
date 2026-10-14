package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * All 28 complete Grid frame boundaries, with ten empty or unchanged-frame controls.
 * Each row owns one primed scene; assertions and terminal release remain outside sampling.
 * Only one scene survives at a time, preserving the original trial-scoped retained ownership.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object GridTrackOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = JmhFixtureSelection.inputs() + mapOf("headless-api" to Path.of(checkNotNull(javaClass.getResource("/headless-api.tsv")).toURI()))

    private enum class Boundary(
        val method: String,
        val description: String,
    ) {
        Clean("cleanFrame", "Complete one unchanged retained frame."),
        Child("childSizeFrame", "Change the first retained leaf size and complete its frame; Empty has no leaf."),
        Constraints("constraintFrame", "Alternate root constraints and complete every required measure and layout."),
        Alignment("alignmentFrame", "Publish default alignment, rebuild its declaration and complete its frame."),
    }

    override val rows: List<JvmOperationReportRow> =
        Boundary.entries.flatMap { boundary ->
            GridTrackBenchmark.Workload.entries.map { workload ->
                JvmOperationReportRow(
                    "${boundary.method}-${workload.name}",
                    "${GridTrackBenchmark::class.java.name}.${boundary.method}",
                    mapOf("workload" to workload.name),
                    boundary.description,
                    control = boundary == Boundary.Clean || workload == GridTrackBenchmark.Workload.Empty,
                )
            }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> =
        rows.associate { row ->
            val boundary = Boundary.entries.single { row.benchmark.endsWith(".${it.method}") }
            val scene = GridTrackBenchmark.Scene()
            scene.workload = GridTrackBenchmark.Workload.valueOf(row.parameters.getValue("workload"))
            try {
                scene.setUp()
                scene.verifyWork()
                val sample =
                    JvmPerformanceRunner.measure(
                        row.id,
                        plan,
                        afterOperation = { _, frame -> scene.verifySample(frame) },
                    ) {
                        operate(boundary, scene)
                    }
                row.id to sample.evidence
            } finally {
                scene.close()
            }
        }

    private fun operate(
        boundary: Boundary,
        scene: GridTrackBenchmark.Scene,
    ): RuntimeUiFrame =
        when (boundary) {
            Boundary.Clean -> scene.cleanFrame()
            Boundary.Child -> scene.childSizeFrame()
            Boundary.Constraints -> scene.constraintFrame()
            Boundary.Alignment -> scene.alignmentFrame()
        }
}
