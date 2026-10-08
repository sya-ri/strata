package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import java.nio.file.Path

/**
 * Shared comparison registration retaining all 120 incoming rows, controls, regressions and unavailable metrics.
 * Actual queue-copy work and the permitted opt-in compiler inventory differ between runtimes, not within one side.
 */
public object IncomingFragmentCpuComparison {
    /**
     * Accepts three baseline reports, three candidate reports, the unchanged collector JAR and a fresh output path.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 8)
        val contract =
            PerformanceReportContract(
                workloadId = "incoming-fragment-ownership-v1",
                phaseKeys = listOf("owners", "workload", "phase", "route"),
                phaseCount = 120,
                reportConditions = setOf("fixture_identity", "inputs", "environment", "native_uploads", "gpu_time", "fps"),
                phaseConditions = setOf("measured_cycles", "samples", "warmup", "native_packets", "logical_bytes", "admitted_fragments", "admitted_fragment_bytes", "decoder_fragment_bytes", "decoder_fragment_arrays", "assembly_bytes", "timed_assembly_bytes"),
                variantReportFields = setOf("runtime_metadata", "remote_api_symbols"),
                variantPhaseFields = setOf("queue_copied_bytes", "queue_copy_arrays"),
            )
        val metrics =
            listOf(
                PerformanceReportMetric("owner_cpu_ns_per_cycle", listOf("owner_thread_cpu_ns"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("owner_allocated_bytes_per_cycle", listOf("owner_thread_allocated_bytes"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("all_threads_allocated_bytes_per_cycle", listOf("all_threads_allocated_bytes"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("wall_ns_per_cycle", listOf("wall_total_ns"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("queue_copied_bytes", listOf("queue_copied_bytes")),
                PerformanceReportMetric("queue_copy_arrays", listOf("queue_copy_arrays")),
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
