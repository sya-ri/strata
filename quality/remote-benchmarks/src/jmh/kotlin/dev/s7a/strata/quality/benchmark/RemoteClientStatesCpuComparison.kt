package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import java.nio.file.Files
import java.nio.file.Path

/**
 * Retains every editable-state CPU case, control and regression through the shared three-plus-three comparison engine.
 * Collector-bound run receipts have already validated their paired JMH launch and immutable source/archive plan.
 */
public object RemoteClientStatesCpuComparison {
    /**
     * Accepts three baseline receipts, three candidate receipts, the frozen collector JAR and a fresh output path.
     * Run on the same frozen fixture/collector classpath as collection; missing or changed case controls fail explicitly.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 8)
        val contract =
            PerformanceReportContract(
                workloadId = "current-client-editable-states-v1",
                phaseKeys = listOf("entries", "membership", "operation"),
                phaseCount = 45,
                reportConditions = setOf("fixture_identity", "inputs", "environment", "jvm_arguments", "native_uploads", "gpu_time", "fps"),
                phaseConditions = setOf("operations_per_sample", "measured_operations", "processed_operations", "samples", "warmup", "last_result"),
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
                verifyPair(cpu)
                require(pairedRuns.add(cpu.getAsJsonObject("paired_launch").get("jmh_run_id").asString)) { "CPU reports must reference six distinct JMH runs" }
            }
        PerformanceJson.writeNew(Path.of(args[7]), report)
    }

    private fun verifyPair(cpu: JsonObject) {
        val pair = cpu.getAsJsonObject("paired_launch")
        listOf("jmh_receipt", "jmh_results", "source_plan", "cpu_executable").forEach { field ->
            require(ArtifactIdentity.file(Path.of(pair.get(field).asString)) == pair.get(field + "_sha256").asString)
        }
        val receipt = read(Path.of(pair.get("jmh_receipt").asString))
        require(receipt.get("run_id") == pair.get("jmh_run_id") && receipt.get("repetition") == cpu.get("repetition"))
        require(receipt.get("status").asString.contentEquals("passed"))
        require(receipt.get("results_sha256") == pair.get("jmh_results_sha256"))
        require(receipt.get("collector_identity") == cpu.get("collector_identity"))
        require(receipt.get("arguments") == pair.get("jmh_arguments") && receipt.get("registered_workloads") == pair.get("registered_workloads"))
        require(read(Path.of(pair.get("source_plan").asString)) == pair.get("source_archive_plan"))
        require(pair.get("environment") == cpu.get("environment") && pair.get("cpu_jvm_arguments") == cpu.get("jvm_arguments"))
        require(pair.getAsJsonObject("jmh_controls").get("jvmArgs") == cpu.get("jvm_arguments"))
        receipt.getAsJsonObject("environment").entrySet().forEach { (key, value) -> require(cpu.getAsJsonObject("environment").get(key) == value) }
        val targets = pair.getAsJsonObject("target_archive_identity")
        cpu.getAsJsonObject("runtime_metadata").getAsJsonArray("modules").forEach { entry ->
            val module = entry.asJsonObject
            require(targets.get(module.get("module").asString) == module.getAsJsonObject("codeSource").get("sha256"))
        }
    }

    private fun read(path: Path): JsonObject = Files.newBufferedReader(path, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
}
