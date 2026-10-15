package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import java.nio.file.Path

/**
 * Supplemental owner CPU and allocation for all sixty-four original complete Text operations.
 * Every row owns one prepared input; invocation header preparation and assertions stay outside samples.
 * ReceiveLifecycle retains negotiation and terminal close inside the original operation.
 */
public object RemoteTextOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = JmhFixtureSelection.inputs() + mapOf("remote-api" to Path.of(checkNotNull(javaClass.getResource("/remote-api.tsv")).toURI()))

    private enum class Operation(
        val method: String,
        val boundary: String,
        val publicControl: Boolean,
        val run: (RemoteTextBenchmark, RemoteTextBenchmark.Input) -> Any,
    ) {
        Encode("publicMessageEncode", "Complete public message encoding", true, RemoteTextBenchmark::publicMessageEncode),
        Decode("publicMessageDecode", "Complete public message decoding", true, RemoteTextBenchmark::publicMessageDecode),
        Receive("receiveMessage", "Every prepared fragment and complete typed negotiated receive", false, RemoteTextBenchmark::receiveMessage),
        Lifecycle("receiveLifecycle", "Construction, local Hello drain, peer negotiation, every fragment, complete typed receive and terminal close", false, RemoteTextBenchmark::receiveLifecycle),
    }

    override val rows: List<JvmOperationReportRow> =
        Operation.entries.flatMap { operation ->
            RemoteTextCorpus.entries.map { corpus ->
                JvmOperationReportRow(
                    "text-${corpus.name}-${operation.method}",
                    "${RemoteTextBenchmark::class.java.name}.${operation.method}",
                    mapOf("corpus" to corpus.name),
                    operation.boundary,
                    control = operation.publicControl || corpus == RemoteTextCorpus.NoTextAck,
                )
            }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> =
        rows
            .flatMap { row ->
                val operation = Operation.entries.single { row.benchmark.endsWith(".${it.method}") }
                val benchmark = RemoteTextBenchmark()
                val input = RemoteTextBenchmark.Input().also { it.corpus = RemoteTextCorpus.valueOf(row.parameters.getValue("corpus")) }
                try {
                    input.setup()
                    JvmPerformanceRunner.warmup(plan.warmup) {
                        input.prepareReceive()
                        verify(operation.run(benchmark, input), input)
                    }
                    JvmPerformanceRunner
                        .sequence(plan.samples, mapOf(row.id to plan.samples)) { _, sequence ->
                            input.prepareReceive()
                            var result: Any? = null
                            sequence.sample(row.id) { result = operation.run(benchmark, input) }
                            verify(checkNotNull(result), input)
                        }.entries
                        .map { it.key to it.value }
                } finally {
                    input.close()
                }
            }.toMap()

    private fun verify(
        result: Any,
        input: RemoteTextBenchmark.Input,
    ) {
        if (result is ByteArray) check(result.contentEquals(input.wire)) else check(result == input.message)
    }
}
