package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.NativeCanvasLookupBenchmark.Placement
import dev.s7a.strata.quality.benchmark.NativeCanvasLookupBenchmark.Scene
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.infra.Blackhole
import java.nio.file.Path

/**
 * Supplements all 216 original operation boundaries with the existing owner-thread CPU and allocation meter.
 * Calls the unchanged benchmark methods; setup, verification and surviving-owner cleanup remain outside sampling.
 * The two borrowing methods use a published software Blackhole without JMH compiler hints.
 * Supplemental wall time is therefore a separate measurement and cannot substitute for generated JMH scores.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object NativeCanvasLookupOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = cleanupOperationInputs()

    // Publication through the singleton prevents a privately confined consumer; only the current weak value is kept.
    private val sink = Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.")

    private enum class Boundary(
        val method: String,
        val description: String,
    ) {
        Target("target", "Borrow every original queued target, including the original per-borrow consumer."),
        Protocol("protocol", "Prepare, queue, borrow every target and consume the complete original batch, including the Canvas-free admission bypass."),
        Capture("capture", "Capture the original detached presentation repeatedly without consulting a live native owner."),
        OneShot("captureOneShot", "Publish the original completed detached membership and capture it exactly once."),
        Rasterize("rasterizePrepared", "Rasterize the original primed portable commands into fresh output storage without receipt lookup."),
        Combined("captureAndRasterize", "Capture the original detached presentation and rasterize its complete portable result."),
    }

    override val rows: List<JvmOperationReportRow> =
        Boundary.entries.flatMap { boundary ->
            Placement.entries.flatMap { placement ->
                listOf(8, 10_000).flatMap { portable ->
                    listOf(false, true).map { changed -> row(boundary, placement, portable, changed) }
                }
            }
        }

    /**
     * Requires the same complete method/parameter inventory as both generated JMH modes and all scalar/raster controls.
     */
    public fun verifyRows() {
        val expected = JmhWorkloadInventory.capture(listOf(NativeCanvasLookupBenchmark::class.java), setOf("avgt", "sample"))
        val actual = rows.flatMap { row -> listOf("avgt", "sample").map { mode -> JmhPerformanceRunner.workloadIdentity(row.benchmark, mode, row.parameters) } }
        check(rows.size == 216 && rows.map { it.id }.distinct().size == 216)
        check(actual.size == 432 && actual.toSet() == expected)
        check(rows.count { it.control } == 116 && rows.all { it.operationsPerSample == 1 })
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        verifyRows()
        check(System.getProperty("compilerBlackholesEnabled", "false").toBoolean().not()) { "Supplemental owner collection requires the software consumer." }
        val benchmark = NativeCanvasLookupBenchmark()
        val reports = linkedMapOf<String, JsonObject>()
        for (boundary in Boundary.entries) {
            for (placement in Placement.entries) {
                for (portable in listOf(8, 10_000)) {
                    for (changed in listOf(false, true)) {
                        val row = row(boundary, placement, portable, changed)
                        val scene = Scene().apply {
                            this.placement = placement
                            this.portable = portable
                            this.changed = changed
                        }
                        try {
                            scene.setup()
                            val sample =
                                JvmPerformanceRunner.measure<Any>(row.id, plan) {
                                    when (boundary) {
                                        Boundary.Target -> benchmark.target(scene, sink)
                                        Boundary.Protocol -> benchmark.protocol(scene, sink)
                                        Boundary.Capture -> benchmark.capture(scene)
                                        Boundary.OneShot -> benchmark.captureOneShot(scene)
                                        Boundary.Rasterize -> benchmark.rasterizePrepared(scene)
                                        Boundary.Combined -> benchmark.captureAndRasterize(scene)
                                    }
                                }
                            check(reports.put(row.id, sample.evidence) == null)
                        } finally {
                            sink.evaporate("Yes, I am Stephen Hawking, and know a thing or two about black holes.")
                            scene.close()
                        }
                    }
                }
            }
        }
        check(reports.keys == rows.map { it.id }.toSet())
        return reports
    }

    private fun row(
        boundary: Boundary,
        placement: Placement,
        portable: Int,
        changed: Boolean,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            "${boundary.method}-${placement.name}-$portable-$changed",
            "${NativeCanvasLookupBenchmark::class.java.name}.${boundary.method}",
            mapOf("placement" to placement.name, "portable" to portable.toString(), "changed" to changed.toString()),
            "${boundary.description} Supplemental owner-thread interval; borrowing uses the software consumer without JMH compiler hints.",
            control = placement.targets <= 1 || boundary === Boundary.Rasterize,
        )
}
