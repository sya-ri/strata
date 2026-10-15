package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.IntegerBlitBenchmark.Path
import dev.s7a.strata.quality.benchmark.IntegerBlitBenchmark.Scenario

/**
 * Collects the original complete raster operations through the shared owner-thread meter.
 * A single immutable scene is prepared and independently verified outside sampling, then released
 * before the next row. Reports retain no image, command, output or horizontal coordinate map.
 */
public object IntegerBlitOperationReport : JvmOperationReportAdapter {
    private val controls: Set<Scenario> =
        setOf(
            Scenario.Small1,
            Scenario.Small2,
            Scenario.Small3,
            Scenario.Small4,
            Scenario.OneRow1,
            Scenario.OneRow2,
            Scenario.OneRow3,
            Scenario.OneRow4,
            Scenario.Identity1,
            Scenario.Identity2,
            Scenario.Identity3,
            Scenario.Identity4,
            Scenario.OneTexel1,
            Scenario.OneTexel2,
            Scenario.OneTexel3,
            Scenario.OneTexel4,
        )

    override val rows: List<JvmOperationReportRow> =
        Path.entries.flatMap { path ->
            Scenario.entries.map { scenario ->
                JvmOperationReportRow(
                    "$path-$scenario",
                    "${IntegerBlitBenchmark::class.java.name}.raster",
                    mapOf("path" to path.name, "scenario" to scenario.name),
                    "Allocate the complete output and execute every original background, clip, integer blit and ordered blend.",
                    control = scenario in controls,
                )
            }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> =
        rows.associate { row ->
            val scene =
                IntegerBlitScene.create(
                    Path.valueOf(row.parameters.getValue("path")),
                    Scenario.valueOf(row.parameters.getValue("scenario")),
                )
            scene.verify()
            val sampled = JvmPerformanceRunner.measure(row.id, plan) { scene.paint() }
            check(0 < sampled.value.size.width && 0 < sampled.value.size.height)
            row.id to sampled.evidence
        }

}
