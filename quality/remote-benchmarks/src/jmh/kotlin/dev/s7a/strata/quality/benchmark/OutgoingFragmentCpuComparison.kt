package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import java.nio.file.Path

/**
 * Shared comparison registration retaining all 196 outgoing rows, controls, regressions and unavailable metrics.
 * Actual native copies, queue array payload and reserved headroom differ between runtimes, not within one side.
 */
public object OutgoingFragmentCpuComparison {
    /**
     * Accepts three baseline reports, three candidate reports, the unchanged collector JAR and a fresh output path.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 8)
        val contract =
            PerformanceReportContract(
                workloadId = "outgoing-fragment-envelopes-v1",
                phaseKeys = listOf("owners", "workload", "phase", "route"),
                phaseCount = 196,
                reportConditions = setOf("fixture_identity", "inputs", "environment", "native_uploads", "gpu_time", "fps", "unpublished_failure_arrays"),
                phaseConditions = setOf("measured_cycles", "samples", "warmup", "logical_codec_bytes", "timed_logical_codec_bytes", "scoped_delivered_fragments", "attempted_messages", "observed_queued_fragments", "delivered_fragments", "delivered_array_payload_bytes", "peak_queued_fragments"),
                variantReportFields = setOf("runtime_metadata", "remote_api_symbols"),
                variantPhaseFields = setOf("observed_queued_array_payload_bytes", "observed_intermediate_fragment_payload_bytes", "discarded_queued_array_payload_bytes", "native_fragment_copy_bytes", "native_fragment_copy_arrays", "timed_native_fragment_copy_bytes", "timed_native_fragment_copy_arrays", "peak_reserved_headroom_bytes"),
            )
        val metrics =
            listOf(
                PerformanceReportMetric("owner_cpu_ns_per_cycle", listOf("owner_thread_cpu_ns"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("owner_allocated_bytes_per_cycle", listOf("owner_thread_allocated_bytes"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("all_threads_allocated_bytes_per_cycle", listOf("all_threads_allocated_bytes"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("wall_ns_per_cycle", listOf("wall_total_ns"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("native_fragment_copy_bytes", listOf("native_fragment_copy_bytes")),
                PerformanceReportMetric("native_fragment_copy_arrays", listOf("native_fragment_copy_arrays")),
                PerformanceReportMetric("timed_native_fragment_copy_bytes", listOf("timed_native_fragment_copy_bytes")),
                PerformanceReportMetric("timed_native_fragment_copy_arrays", listOf("timed_native_fragment_copy_arrays")),
                PerformanceReportMetric("observed_queued_array_payload_bytes", listOf("observed_queued_array_payload_bytes")),
                PerformanceReportMetric("discarded_queued_array_payload_bytes", listOf("discarded_queued_array_payload_bytes")),
                PerformanceReportMetric("peak_reserved_headroom_bytes", listOf("peak_reserved_headroom_bytes")),
            )
        val report =
            JvmPerformanceReports.compare(
                args.take(3).map { Path.of(it) },
                args.drop(3).take(3).map { Path.of(it) },
                Path.of(args[6]),
                contract,
                metrics,
            )
        PerformanceJson.writeNew(Path.of(args[7]), report)
    }
}
