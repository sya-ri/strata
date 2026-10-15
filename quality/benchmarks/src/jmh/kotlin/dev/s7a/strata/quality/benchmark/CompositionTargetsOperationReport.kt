package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.CompositionTargetsBenchmark.Case
import dev.s7a.strata.quality.benchmark.CompositionTargetsBenchmark.Scene

/**
 * Supplements every original actual Fabric CPU-input boundary with shared owner-thread counters.
 * Full scene setup and pixel verification precede sampling; partial setup and successful rows both
 * release their current inputs and immutable sources. Detached reports retain no scene or frame.
 * Actual Fabric classes remain required; JMH and the complete native corpus keep their own scopes.
 */
public object CompositionTargetsOperationReport : JvmOperationReportAdapter {
    override val rows: List<JvmOperationReportRow> =
        Case.entries.flatMap { case ->
            Boundary.entries.map { boundary ->
                JvmOperationReportRow(
                    "${case.name}-${boundary.name}",
                    "${CompositionTargetsBenchmark::class.java.name}.${boundary.method}",
                    mapOf("case" to case.name),
                    boundary.description,
                    control = control(case, boundary),
                )
            }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> =
        Case.entries
            .flatMap { case ->
                Boundary.entries.map { boundary -> collectRow(case, boundary, plan) }
            }.toMap()

    private fun collectRow(
        case: Case,
        boundary: Boundary,
        plan: PerformancePlan,
    ): Pair<String, JsonObject> {
        val scene = Scene().apply { this.case = case }
        try {
            scene.setup()
            val operation = operation(scene, boundary)
            val id = "${case.name}-${boundary.name}"
            val sampled = JvmPerformanceRunner.measure(id, plan) { operation() }
            return id to sampled.evidence
        } finally {
            scene.close()
        }
    }

    private fun operation(
        scene: Scene,
        boundary: Boundary,
    ): () -> Any =
        when (boundary) {
            Boundary.Fresh -> scene::fresh
            Boundary.Replacement -> scene::replace
        }

    private fun control(
        case: Case,
        boundary: Boundary,
    ): Boolean =
        when (case) {
            Case.SingleTileControl, Case.SmallSourceFallback -> true
            Case.CleanLarge -> boundary == Boundary.Replacement
            else -> false
        }

    /** Original JMH methods and their complete CPU-input preparation scopes. */
    private enum class Boundary(
        val method: String,
        val description: String,
    ) {
        Fresh("fresh", "Execute the complete original fresh CPU-input and target-reservation preparation and return its actual result."),
        Replacement("replacement", "Execute the complete original revision replacement or same-list shortcut and return its actual result."),
    }
}
