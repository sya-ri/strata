package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.ComponentObserverBenchmark.Scenario
import java.nio.file.Path

/**
 * Collects all 24 unchanged JMH operation boundaries with the existing owner CPU/allocation collector.
 * Fixture preparation, work assertions, reference frames and surviving-resource cleanup stay outside sampling.
 */
public object ComponentObserverOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() =
            JmhFixtureSelection.inputs() +
                mapOf(
                    "cc0-geometric-font" to Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))),
                    "component-api" to Path.of(checkNotNull(javaClass.getResource("/component-api.tsv")).toURI()),
                    "runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI()),
                )

    override val rows: List<JvmOperationReportRow> =
        Scenario.entries.flatMap { scenario ->
            listOf(0, 1, 16, 128).map { count ->
                JvmOperationReportRow(
                    "${scenario.name}-$count",
                    "${ComponentObserverBenchmark::class.java.name}.operation",
                    mapOf("observers" to count.toString(), "scenario" to scenario.name),
                    when (scenario) {
                        Scenario.Changed64 -> "64 changed writes and every current callback."
                        Scenario.Equal64 -> "64 equal writes with no notifications."
                        Scenario.FirstWrite -> "Register all callbacks, first changed write and release every callback."
                        Scenario.Churn64 -> "64 complete extra-callback registration, changed write and release cycles."
                        Scenario.ChangedFrame -> "One changed write and one completed retained frame."
                        Scenario.CleanFrame -> "One completed clean retained frame, no writes."
                    },
                    control = scenario != Scenario.Changed64,
                )
            }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val fixtures = ArrayList<ComponentObserverFixture>()
        return AutoCloseable { fixtures.forEach(ComponentObserverFixture::close) }.use { _ ->
            val selected =
                rows.associate { row ->
                    val fixture = ComponentObserverFixture(row.parameters.getValue("observers").toInt(), Scenario.valueOf(row.parameters.getValue("scenario")))
                    fixtures.add(fixture)
                    row.id to fixture
                }
            JvmPerformanceRunner.warmup(plan.warmup) {
                rows.forEach { row ->
                    val fixture = selected.getValue(row.id)
                    val before = fixture.notifications
                    val checked = fixture.checked
                    fixture.execute()
                    fixture.verifyOperation(before, checked)
                }
            }
            JvmPerformanceRunner.sequence(plan.samples, rows.associate { it.id to plan.samples }) { _, sequence ->
                rows.forEach { row ->
                    val fixture = selected.getValue(row.id)
                    val before = fixture.notifications
                    val checked = fixture.checked
                    sequence.sample(row.id) { fixture.execute() }
                    fixture.verifyOperation(before, checked)
                }
            }
        }
    }
}
