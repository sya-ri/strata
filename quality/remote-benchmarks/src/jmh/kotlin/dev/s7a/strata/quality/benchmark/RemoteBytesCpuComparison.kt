package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import java.nio.file.Path

/**
 * Retains every byte-codec CPU case, control and regression through the shared three-plus-three comparison engine.
 * Collector-bound run receipts have already validated their paired JMH launch and immutable source/archive plan.
 */
public object RemoteBytesCpuComparison {
    /**
     * Accepts three baseline receipts, three candidate receipts, the frozen collector JAR and a fresh output path.
     * Run on the same frozen fixture/collector classpath as collection; missing or changed case controls fail explicitly.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 8)
        val contract =
            PerformanceReportContract(
                workloadId = "remote-bytes-codec-v1",
                phaseKeys = listOf("corpus", "operation"),
                phaseCount = 64,
                reportConditions = setOf("fixture_identity", "inputs", "environment", "jvm_arguments", "native_uploads", "gpu_time", "fps"),
                phaseConditions = setOf("operations_per_sample", "measured_operations", "processed_operations", "samples", "warmup"),
                variantReportFields = setOf("runtime_metadata"),
            )
        val metrics =
            listOf(
                PerformanceReportMetric("owner_cpu_ns_per_operation", listOf("owner_thread_cpu_ns"), divisorPath = listOf("measured_operations")),
                PerformanceReportMetric("owner_allocated_bytes_per_operation", listOf("owner_thread_allocated_bytes"), divisorPath = listOf("measured_operations")),
                PerformanceReportMetric("all_threads_allocated_bytes_per_operation", listOf("all_threads_allocated_bytes"), divisorPath = listOf("measured_operations")),
                PerformanceReportMetric("wall_ns_per_operation", listOf("wall_total_ns"), divisorPath = listOf("measured_operations")),
            )
        val pairedRuns = mutableSetOf<String>()
        val report =
            JvmPerformanceReports.compare(
                args.take(3).map { Path.of(it) },
                args.drop(3).take(3).map { Path.of(it) },
                Path.of(args[6]),
                contract,
                metrics,
            ) { cpu ->
                RemoteCpuReportPair.verify(cpu)
                require(pairedRuns.add(cpu.getAsJsonObject("paired_launch").get("jmh_run_id").asString)) { "CPU reports must reference six distinct JMH runs" }
            }
        PerformanceJson.writeNew(Path.of(args[7]), report)
    }
}
