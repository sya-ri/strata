package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemotePatch
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * Supplemental owner CPU and allocation for all thirty unchanged protocol controls.
 * Trees, patches and reference results are prepared outside the shared sample boundary.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object RemoteProtocolOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = JmhFixtureSelection.inputs() + mapOf("remote-api" to Path.of(checkNotNull(javaClass.getResource("/remote-api.tsv")).toURI()))

    private enum class Operation(
        val method: String,
        val boundary: String,
        val run: (RemoteProtocolBenchmark, RemoteProtocolBenchmark.Protocol) -> Any,
        val expected: (RemoteProtocolBenchmark.Protocol) -> Any,
    ) {
        Diff("diff", "Complete sparse declaration diff", RemoteProtocolBenchmark::diff, { it.patch }),
        Apply("apply", "Complete patch validation and immutable application", RemoteProtocolBenchmark::apply, { it.after }),
        Update("updateCodec", "Complete public update encode and decode", RemoteProtocolBenchmark::updateCodec, { it.update }),
        Snapshot("snapshotCodec", "Complete public snapshot encode and decode", RemoteProtocolBenchmark::snapshotCodec, { it.snapshot }),
        Fragments("fragments", "Complete fragment and reassembly owner lifetimes", RemoteProtocolBenchmark::fragments, { it.codec.encode(it.update) }),
    }

    override val rows: List<JvmOperationReportRow> =
        Operation.entries.flatMap { operation ->
            listOf(100, 8192).flatMap { nodes ->
                RemoteChange.entries.map { change ->
                    JvmOperationReportRow(
                        "protocol-$nodes-${change.name}-${operation.method}",
                        "${RemoteProtocolBenchmark::class.java.name}.${operation.method}",
                        mapOf("nodes" to nodes.toString(), "change" to change.name),
                        operation.boundary,
                        control = true,
                    )
                }
            }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> =
        rows.flatMap { row ->
            val operation = Operation.entries.single { row.benchmark.endsWith(".${it.method}") }
            val benchmark = RemoteProtocolBenchmark()
            val input =
                RemoteProtocolBenchmark.Protocol().also {
                    it.nodes = row.parameters.getValue("nodes").toInt()
                    it.change = RemoteChange.valueOf(row.parameters.getValue("change"))
                    it.setup()
                }
            val expected = operation.expected(input)
            JvmPerformanceRunner.warmup(plan.warmup) { verify(operation.run(benchmark, input), expected) }
            JvmPerformanceRunner
                .sequence(plan.samples, mapOf(row.id to plan.samples)) { _, sequence ->
                    var result: Any? = null
                    sequence.sample(row.id) { result = operation.run(benchmark, input) }
                    verify(checkNotNull(result), expected)
                }.entries
                .map { it.key to it.value }
        }.toMap()

    private fun verify(
        result: Any,
        expected: Any,
    ) {
        when {
            result is ByteArray && expected is ByteArray -> check(result.contentEquals(expected))
            result is RemotePatch && expected is RemotePatch -> check(result.root == expected.root && result.changed == expected.changed && result.removed == expected.removed)
            result is RemoteMessage && expected is RemoteMessage -> {
                val codec = RemoteMessageCodec()
                check(codec.encode(result).contentEquals(codec.encode(expected)))
            }
            else -> check(result == expected)
        }
    }
}
