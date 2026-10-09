package dev.s7a.strata.runtime.velocity

import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import java.nio.file.Path

/**
 * Shared three-versus-three comparison retains every routing/control phase, scoped CPU/allocation and work difference.
 */
internal object NativeRoutingCpuComparison {
    /**
     * Accepts three baseline and three candidate receipts, the identical frozen collector JAR and a fresh report path.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 8)
        val contract =
            PerformanceReportContract(
                workloadId = "native-envelope-routing-v1",
                phaseKeys = listOf("players", "workload", "direction", "phase"),
                phaseCount = 14 * NativeRoutingWorkload.entries.size,
                reportConditions = setOf("fixture_identity", "inputs", "environment", "native_uploads", "gpu_time", "fps"),
                phaseConditions = setOf("meter_thread", "measured_cycles", "samples", "warmup", "operation_result", "work"),
                variantReportFields = setOf("runtime_metadata", "runtime_api_symbols"),
                variantPhaseFields = setOf("untimed_probes"),
            )
        val metrics =
            listOf(
                PerformanceReportMetric("operation_cpu_ns_per_cycle", listOf("owner_thread_cpu_ns"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("operation_allocated_bytes_per_cycle", listOf("owner_thread_allocated_bytes"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("all_threads_allocated_bytes_per_cycle", listOf("all_threads_allocated_bytes"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("wall_ns_per_cycle", listOf("wall_total_ns"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("public_decoded_fragment_arrays_per_cycle", listOf("untimed_probes", "decoded_frame_arrays")),
                PerformanceReportMetric("public_decoded_fragment_payload_bytes_per_cycle", listOf("untimed_probes", "decoded_frame_bytes")),
                PerformanceReportMetric("retained_inbox_snapshot_bytes_per_cycle", listOf("untimed_probes", "inbox_snapshot_bytes")),
                PerformanceReportMetric("retained_event_accessor_bytes_per_cycle", listOf("untimed_probes", "event_data_bytes")),
                PerformanceReportMetric("endpoint_assembled_bytes_per_cycle", listOf("untimed_probes", "assembled_bytes")),
            )
        PerformanceJson.writeNew(Path.of(args[7]), JvmPerformanceReports.compare(args.take(3).map { Path.of(it) }, args.drop(3).take(3).map { Path.of(it) }, Path.of(args[6]), contract, metrics))
    }
}
