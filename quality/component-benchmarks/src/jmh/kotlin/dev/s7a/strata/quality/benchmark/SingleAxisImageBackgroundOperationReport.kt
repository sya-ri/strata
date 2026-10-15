package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.SingleAxisImageBackgroundBenchmark.TileSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Collects all original producer, declaration and complete retained-frame boundaries.
 * Each row creates its real owner outside sampling and closes it on every exit; reports retain
 * no host, paint scope, source image or frame. JMH remains the independent primary timer.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object SingleAxisImageBackgroundOperationReport : JvmOperationReportAdapter {
    override val rows: List<JvmOperationReportRow> =
        SingleAxisImageTileWorkload.entries.flatMap { workload ->
            Boundary.entries.map { boundary ->
                JvmOperationReportRow(
                    "${workload.name}-${boundary.name}",
                    "${SingleAxisImageBackgroundBenchmark::class.java.name}.${boundary.method}",
                    mapOf("workload" to workload.name),
                    boundary.description,
                    control = boundary == Boundary.Clean,
                )
            }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> =
        SingleAxisImageTileWorkload.entries.flatMap { workload ->
            Boundary.entries.map { boundary -> collectRow(workload, boundary, plan) }
        }.toMap()

    private fun collectRow(
        workload: SingleAxisImageTileWorkload,
        boundary: Boundary,
        plan: PerformancePlan,
    ): Pair<String, JsonObject> {
        val state = TileSession().apply { this.workload = workload }
        try {
            state.setup()
            val operation = operation(boundary, state)
            val id = "${workload.name}-${boundary.name}"
            val sampled = JvmPerformanceRunner.measure(id, plan) { operation() }
            return id to sampled.evidence
        } finally {
            state.close()
        }
    }

    private fun operation(
        boundary: Boundary,
        state: TileSession,
    ): () -> Any =
        when (boundary) {
            Boundary.PaintCollector -> state::paintCollector
            Boundary.PaintAdmission -> state::paintAdmission
            Boundary.Declaration -> state::declaration
            Boundary.DirtySource -> state::dirtySource
            Boundary.Geometry -> state::geometry
            Boundary.Clean -> state::clean
        }

    /** Method names bind the existing external JMH registry; descriptions preserve complete sample scopes. */
    private enum class Boundary(
        val method: String,
        val description: String,
    ) {
        PaintCollector("paintCollector", "Create the guarded scope, execute the genuine node callback, snapshot and close the scope."),
        PaintAdmission("paintAdmission", "Collect the genuine callback and perform the supplied runtime's complete dense-list admission."),
        Declaration("declaration", "Create and attach a real host, then close it without requesting paint."),
        DirtySource("dirtySource", "Publish the next unequal immutable source and return the complete resulting retained frame."),
        Geometry("geometry", "Alternate the actual viewport constraints and return the complete resulting retained frame."),
        Clean("clean", "Return the complete unchanged retained frame without regenerating local tiles."),
    }
}
