package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.runtime.headless.HeadlessImage
import java.nio.file.Path

/**
 * Supplemental complete composition rows; immutable source preparation stays outside sampling.
 */
public object NonuniformOverlayOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = JmhFixtureSelection.inputs() + mapOf("headless-api" to Path.of(checkNotNull(javaClass.getResource("/headless-api.tsv")).toURI()))

    override val rows: List<JvmOperationReportRow> =
        listOf(320, 1920).flatMap { width ->
            listOf(1, 16, 64).map { layers ->
                JvmOperationReportRow(
                    "compose-$width-$layers",
                    "${NonuniformOverlayBenchmark::class.java.name}.composition",
                    mapOf("width" to width.toString(), "layers" to layers.toString()),
                    "Switch prepared source, allocate complete output and compose every ordered translucent fill.",
                )
            }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val states =
            rows.associate { row ->
                row.id to
                    NonuniformOverlayBenchmark.MixedPixels().apply {
                        width = row.parameters.getValue("width").toInt()
                        layers = row.parameters.getValue("layers").toInt()
                        setup()
                    }
            }
        var last: HeadlessImage? = null
        JvmPerformanceRunner.warmup(plan.warmup) { states.values.forEach { last = it.next() } }
        return JvmPerformanceRunner.sequence(plan.samples, rows.associate { it.id to plan.samples }) { _, sequence ->
            states.forEach { (id, state) ->
                sequence.sample(id) { last = state.next() }
                check(checkNotNull(last).size.width == state.width)
            }
        }
    }
}
