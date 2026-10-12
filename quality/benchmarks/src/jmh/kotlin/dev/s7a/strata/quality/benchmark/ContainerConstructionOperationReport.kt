package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.element.Element
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.ContainerConstructionBenchmark.Container
import dev.s7a.strata.quality.benchmark.ContainerConstructionBenchmark.Scene
import dev.s7a.strata.quality.benchmark.ContainerConstructionBenchmark.Shape
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * All 84 original construction/frame rows, including 24 empty/single-child controls.
 * Each row owns one primed scene; setup, result checks and surviving-session close stay outside sampling.
 * The shared runner retains only the final returned result and collects the caller's CPU/allocation scope.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object ContainerConstructionOperationReport : JvmOperationReportAdapter {
    private enum class Operation(
        val method: String,
        val boundary: String,
    ) {
        Construct("construct", "Build the complete detached container declaration with its final public child snapshots."),
        RebuildFrame("rebuildFrame", "Publish one observed revision, rebuild the complete declaration and commit its retained frame."),
    }

    private data class Case(
        val operation: Operation,
        val container: Container,
        val shape: Shape,
    ) {
        val row =
            JvmOperationReportRow(
                "${operation.method}-${container.name}-${shape.name}",
                "${ContainerConstructionBenchmark::class.java.name}.${operation.method}",
                mapOf("container" to container.name, "shape" to shape.name),
                operation.boundary,
                control = shape == Shape.Empty || shape == Shape.Single,
            )
    }

    private val cases =
        Operation.entries.flatMap { operation ->
            Container.entries.flatMap { container ->
                Shape.entries.map { shape -> Case(operation, container, shape) }
            }
        }

    override val rows: List<JvmOperationReportRow> = cases.map { it.row }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val benchmark = ContainerConstructionBenchmark()
        return cases.associate { case ->
            val scene =
                Scene().apply {
                    container = case.container
                    shape = case.shape
                }
            try {
                scene.setUp()
                val sample =
                    JvmPerformanceRunner.measure<Any>(
                        case.row.id,
                        plan,
                        afterOperation = { _, result ->
                            when (case.operation) {
                                Operation.Construct -> check(result is Element)
                                Operation.RebuildFrame -> check(result is RuntimeUiFrame)
                            }
                        },
                    ) {
                        when (case.operation) {
                            Operation.Construct -> benchmark.construct(scene)
                            Operation.RebuildFrame -> benchmark.rebuildFrame(scene)
                        }
                    }
                case.row.id to sample.evidence
            } finally {
                scene.close()
            }
        }
    }
}
