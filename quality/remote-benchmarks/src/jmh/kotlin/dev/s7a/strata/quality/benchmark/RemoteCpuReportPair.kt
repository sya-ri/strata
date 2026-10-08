package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.s7a.strata.performance.ArtifactIdentity
import java.nio.file.Files
import java.nio.file.Path

/**
 * Revalidates immutable CPU/JMH launch pair artifacts for consumers of the shared comparison engine.
 */
internal object RemoteCpuReportPair {
    /**
     * Checks exact receipt, collector, source plan, executable, controls, inputs and loaded target identities.
     */
    fun verify(cpu: JsonObject) {
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
