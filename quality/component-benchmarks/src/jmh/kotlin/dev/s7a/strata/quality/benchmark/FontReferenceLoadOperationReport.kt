package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import java.nio.file.Path

/**
 * Eight complete public loads using the shared meters; one sample is one snapshot, without subtracting collection cost.
 */
public object FontReferenceLoadOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() =
            JmhFixtureSelection.inputs() +
                mapOf(
                    "cc0-geometric-font" to Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))),
                    "component-api" to Path.of(checkNotNull(javaClass.getResource("/component-api.tsv")).toURI()),
                    "runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI()),
                )

    override val rows: List<JvmOperationReportRow> =
        FontReferenceLoadCase.entries.map { case ->
            JvmOperationReportRow(
                "load-${case.name}",
                "${FontReferenceLoadBenchmark::class.java.name}.load",
                mapOf("case" to case.name),
                "One complete public snapshot load of the prepared ${case.name} source, including source reads, parsing, graph resolution and final collection snapshots.",
                control = case.control,
            )
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val fixtures = FontReferenceLoadCase.entries.associateBy({ "load-${it.name}" }, { FontReferenceLoadFixture(it) })
        var last: MinecraftFontSnapshot? = null
        JvmPerformanceRunner.warmup(plan.warmup) { fixtures.values.forEach { fixture -> last = fixture.load() } }
        return JvmPerformanceRunner.sequence(plan.samples, rows.associate { it.id to plan.samples }) { _, sequence ->
            fixtures.forEach { (id, fixture) ->
                sequence.sample(id) { last = fixture.load() }
                fixture.verify(checkNotNull(last))
            }
        }
    }
}
