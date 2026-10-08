package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import java.nio.file.Path

/**
 * Registers the frozen transport CPU corpus with the shared collector-bound comparison engine.
 * All thirty rows remain present, including controls and regressions; unavailable metrics remain null.
 */
public object TransportDrainCpuComparison {
    /**
     * Accepts three baseline receipts, three candidate receipts, the frozen collector JAR and a fresh output path.
     * Run this consumer on the same frozen fixture/collector classpath used for collection.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 8)
        val contract = PerformanceReportContract(
            workloadId = "bounded-transport-drain-v1",
            phaseKeys = listOf("peers", "workload"),
            phaseCount = 30,
            reportConditions = setOf("fixture_identity", "inputs", "environment", "native_uploads", "gpu_time", "fps"),
            phaseConditions = setOf("cycles_per_sample", "measured_cycles", "samples", "warmup", "processed_input_frames", "cumulative_output_frames"),
            variantReportFields = setOf("runtime_metadata"),
        )
        val metrics = listOf(
            PerformanceReportMetric("owner_cpu_ns_per_cycle", listOf("owner_thread_cpu_ns"), divisorPath = listOf("measured_cycles")),
            PerformanceReportMetric("owner_allocated_bytes_per_cycle", listOf("owner_thread_allocated_bytes"), divisorPath = listOf("measured_cycles")),
            PerformanceReportMetric("all_threads_allocated_bytes_per_cycle", listOf("all_threads_allocated_bytes"), divisorPath = listOf("measured_cycles")),
            PerformanceReportMetric("wall_ns_per_cycle", listOf("wall_total_ns"), divisorPath = listOf("measured_cycles")),
        )
        val report = JvmPerformanceReports.compare(
            args.take(3).map { Path.of(it) },
            args.drop(3).take(3).map { Path.of(it) },
            Path.of(args[6]),
            contract,
            metrics,
        )
        PerformanceJson.writeNew(Path.of(args[7]), report)
    }
}
