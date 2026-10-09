package dev.s7a.strata.runtime.velocity

import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import java.nio.file.Path

/**
 * Registers all native-free worker controls with the shared comparison engine, preserving regressions and null metrics.
 */
internal object VelocityDrainCpuComparison {
    /**
     * Accepts three baseline receipts, three candidate receipts, the identical collector JAR and one fresh output.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 8)
        val contract =
            PerformanceReportContract(
                workloadId = "bounded-velocity-drain-v1",
                phaseKeys = listOf("workload"),
                phaseCount = VelocityDrainWorkload.entries.size,
                reportConditions = setOf("fixture_identity", "inputs", "environment", "native_uploads", "gpu_time", "fps"),
                phaseConditions = setOf("queued_commands_per_cycle", "cycles_per_sample", "measured_cycles", "samples", "warmup", "processed_commands", "host_peers"),
                variantReportFields = setOf("runtime_metadata"),
                variantPhaseFields = setOf("untimed_command_polls_per_cycle", "post_empty_arrival"),
            )
        val metrics =
            listOf(
                PerformanceReportMetric("owner_cpu_ns_per_cycle", listOf("owner_thread_cpu_ns"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("owner_allocated_bytes_per_cycle", listOf("owner_thread_allocated_bytes"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("all_threads_allocated_bytes_per_cycle", listOf("all_threads_allocated_bytes"), divisorPath = listOf("measured_cycles")),
                PerformanceReportMetric("wall_ns_per_cycle", listOf("wall_total_ns"), divisorPath = listOf("measured_cycles")),
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
