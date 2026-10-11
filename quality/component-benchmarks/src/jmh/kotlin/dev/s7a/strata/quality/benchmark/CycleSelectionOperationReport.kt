package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * All 39 selection fixtures, 48 complete rows and 30 independent controls.
 * Retained main fixtures are primed separately for each method; controls are fresh per invocation.
 * Ordinary surviving-resource close stays outside sampling; DisposeCloseRelease closes inside control().
 */
@OptIn(InternalStrataRuntimeApi::class)
public object CycleSelectionOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() =
            JmhFixtureSelection.inputs() +
                mapOf(
                    "cc0-geometric-font" to Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))),
                    "component-api" to Path.of(checkNotNull(javaClass.getResource("/component-api.tsv")).toURI()),
                    "runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI()),
                )

    private enum class MainOperation(
        val method: String,
        val boundary: String,
    ) {
        Hover("unchangedHoverAndCompletedFrame", "Alternate inside/outside hover"),
        Write("selectionWriteAndCompletedFrame", "Publish preconstructed adjacent valid selection"),
    }

    override val rows: List<JvmOperationReportRow> =
        MainOperation.entries.flatMap { operation ->
            listOf(8, 128, 1024).flatMap { choices ->
                CycleSelectionPosition.entries.map { position ->
                    JvmOperationReportRow(
                        "${operation.method}-$choices-${position.name}",
                        "${CycleSelectionBenchmark::class.java.name}.${operation.method}",
                        mapOf("choices" to choices.toString(), "position" to position.name),
                        "${operation.boundary}, including the complete production host frame.",
                    )
                }
            }
        } +
            CycleSelectionControl.entries.map { control ->
                JvmOperationReportRow(
                    "control-${control.name}",
                    "${CycleSelectionBenchmark::class.java.name}.control",
                    mapOf("scenario" to control.name),
                    "Complete ${control.name} transition sequence, including all specified recovery frames and required terminal close.",
                    control = true,
                )
            }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val main = linkedMapOf<String, CycleSelectionFixture>()
        return AutoCloseable { close(main.values) }.use {
            rows.filter { it.control.not() }.forEach { row ->
                val count = row.parameters.getValue("choices").toInt()
                main[row.id] = CycleSelectionFixture(count, CycleSelectionPosition.valueOf(row.parameters.getValue("position")).index(count), deferAssertions = true)
            }
            JvmPerformanceRunner.warmup(plan.warmup) {
                rows.forEach { row ->
                    if (row.control) {
                        CycleSelectionFixture(control = CycleSelectionControl.valueOf(row.parameters.getValue("scenario")), deferAssertions = true).use {
                            it.control()
                            it.assertOperation()
                        }
                    } else {
                        val fixture = main.getValue(row.id)
                        operate(row, fixture)
                        fixture.assertOperation()
                    }
                }
            }
            JvmPerformanceRunner.sequence(plan.samples, rows.associate { it.id to plan.samples }) { _, sequence ->
                rows.forEach { row ->
                    if (row.control) {
                        CycleSelectionFixture(control = CycleSelectionControl.valueOf(row.parameters.getValue("scenario")), deferAssertions = true).use { fixture ->
                            sequence.sample(row.id) { fixture.control() }
                            fixture.assertOperation()
                        }
                    } else {
                        val fixture = main.getValue(row.id)
                        sequence.sample(row.id) { operate(row, fixture) }
                        fixture.assertOperation()
                    }
                }
            }
        }
    }

    private fun operate(
        row: JvmOperationReportRow,
        fixture: CycleSelectionFixture,
    ) {
        when (MainOperation.entries.single { row.benchmark.endsWith(".${it.method}") }) {
            MainOperation.Hover -> fixture.hover()
            MainOperation.Write -> fixture.write()
        }
    }

    private fun close(fixtures: Collection<CycleSelectionFixture>) {
        var failure: Throwable? = null
        fixtures.forEach { fixture ->
            runCatching { fixture.close() }.exceptionOrNull()?.let { cause ->
                val previous = failure
                if (previous == null) failure = cause else previous.addSuppressed(cause)
            }
        }
        failure?.let { throw it }
    }
}
