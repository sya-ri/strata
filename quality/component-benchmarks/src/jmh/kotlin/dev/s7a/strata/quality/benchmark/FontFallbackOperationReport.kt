package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import java.nio.file.Path

/**
 * All 48 original fallback operations use independent prepared owners and the shared owner-thread meter.
 * Source acquisition, snapshot loading, reference glyphs and surviving-owner close remain outside samples.
 * Lifecycle retains its original backend opening, preflight, 64-scalar lookup and terminal close inside each operation.
 * Complete returned glyph, retention and actual backend-work checks run after sampling; failures prevent a complete report.
 */
public object FontFallbackOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() {
            val required =
                JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) +
                    mapOf(
                        "cc0-geometric-font" to Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))),
                        "component-api" to Path.of(checkNotNull(javaClass.getResource("/component-api.tsv")).toURI()),
                        "runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI()),
                    )
            val additional = JmhFixtureSelection.inputs()
            require(required.keys.intersect(additional.keys).isEmpty()) { "Fixture input labels overlap required control inputs" }
            return required + additional
        }

    private enum class Operation(
        val method: String,
        val boundary: String,
    ) {
        Warm("warm", "Resolve prepared scalar 65 once, retaining the original raster access order."),
        Churn("churn", "Resolve every predetermined scalar from 65 through 128 in ascending order and return scalar 128."),
        Lifecycle("lifecycle", "Open, preflight, query all 64 predetermined scalars and close one fresh bounded engine."),
    }

    private val depths = listOf(1, 10)
    private val counterNames = listOf("backend_open_attempts", "png_decode_calls", "face_open_attempts", "native_glyph_calls", "face_close_calls", "backend_close_calls")

    override val rows: List<JvmOperationReportRow> =
        Operation.entries.flatMap { operation ->
            FontFallbackWorkload.entries.flatMap { workload -> depths.map { depth -> row(operation, workload, depth) } }
        }

    /**
     * Requires exact method and parameter coverage from the real JMH-generated registry before collecting any row.
     */
    internal fun verifyInventory() {
        val actual = JmhWorkloadInventory.capture(listOf(FontFallbackBenchmark::class.java), setOf("avgt"))
        val declared = rows.map { JmhPerformanceRunner.workloadIdentity(it.benchmark, "avgt", it.parameters) }.toSet()
        check(rows.size == 48 && rows.map { it.id }.toSet().size == rows.size && declared == actual)
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        verifyInventory()
        return Operation.entries
            .flatMap { operation ->
                FontFallbackWorkload.entries.flatMap { workload ->
                    depths.map { depth ->
                        val row = row(operation, workload, depth)
                        row.id to collectRow(operation, workload, depth, plan)
                    }
                }
            }.toMap()
    }

    private fun collectRow(
        operation: Operation,
        workload: FontFallbackWorkload,
        depth: Int,
        plan: PerformancePlan,
    ): JsonObject {
        val owner =
            FontFallbackBenchmark.FallbackSession().apply {
                this.workload = workload
                this.depth = depth
            }
        owner.setup()
        val id = row(operation, workload, depth).id
        val report =
            try {
                val expected = if (operation == Operation.Warm) owner.uncachedWarm() else owner.uncached()
                val retained = owner.retained
                var before = owner.observedWork
                val deltas = JsonArray()
                JvmPerformanceRunner
                    .measure(
                        id,
                        plan,
                        beforeSample = { before = owner.observedWork },
                        afterOperation = { _, result ->
                            equalGlyph(expected, result)
                            val delta = verifyOperation(owner, operation, before, retained)
                            deltas.add(JsonArray().apply { delta.forEach { add(it) } })
                        },
                    ) {
                        when (operation) {
                            Operation.Warm -> owner.warm()
                            Operation.Churn -> owner.churn()
                            Operation.Lifecycle -> owner.lifecycle()
                        }
                    }.evidence
                    .apply {
                        check(deltas.size() == plan.samples)
                        add("sampled_backend_work", workRecord(deltas))
                    }
            } finally {
                owner.close()
            }
        val work = owner.observedWork
        check(work[0] == work[5]) { "A supplemental fixture engine retained its native backend after close" }
        return report
    }

    private fun verifyOperation(
        owner: FontFallbackBenchmark.FallbackSession,
        operation: Operation,
        before: List<Long>,
        retained: List<Long>,
    ): List<Long> {
        val current = owner.observedWork
        check(current.size == counterNames.size && before.size == counterNames.size)
        val delta = current.zip(before) { after, previous -> Math.subtractExact(after, previous) }
        check(delta.all { 0L <= it })
        if (operation == Operation.Lifecycle) {
            check(delta[0] == 1L && delta[5] == 1L)
            check(owner.retained == retained)
        } else {
            check(delta[0] == 0L && delta[5] == 0L)
        }
        val live = owner.retained
        check(live[0] <= 64L && live[1] <= 1024 * 1024L && live[2] <= 1L)
        return delta
    }

    private fun workRecord(deltas: JsonArray): JsonObject =
        JsonObject().apply {
            add("counter_names", JsonArray().apply { counterNames.forEach { add(it) } })
            add("deltas", deltas)
        }

    private fun row(
        operation: Operation,
        workload: FontFallbackWorkload,
        depth: Int,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            "${operation.method}-${workload.name}-$depth",
            "${FontFallbackBenchmark::class.java.name}.${operation.method}",
            mapOf("workload" to workload.name, "depth" to depth.toString()),
            operation.boundary,
        )

    private fun equalGlyph(
        expected: MinecraftFontGlyph,
        actual: MinecraftFontGlyph,
    ) {
        check(expected.copy(image = null) == actual.copy(image = null))
        check(expected.image?.size == actual.image?.size && (expected.image?.copyArgb()?.contentEquals(actual.image?.copyArgb()) ?: (actual.image == null)))
    }
}
