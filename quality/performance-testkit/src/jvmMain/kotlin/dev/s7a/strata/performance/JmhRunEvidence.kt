package dev.s7a.strata.performance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Revalidates one completed JMH invocation before it can enter an aggregate or comparison.
 */
internal object JmhRunEvidence {
    /**
     * Loads only complete archive-bound evidence from the selected collector.
     */
    internal fun load(
        directory: Path,
        collector: Path,
    ): JmhEvidenceRun {
        val (receipt, source) = JvmEvidenceFiles.snapshot(directory.resolve("receipt.json"))
        verifyHeader(receipt)
        JvmPerformanceEvidence.verifyCollectors(listOf(receipt), collector)
        JvmEvidenceFiles.verifyFile(directory.resolve("collector.jar"), receipt.objectField("collector_identity").textField("code_source_sha256"))
        JvmEvidenceFiles.verifyFile(directory.resolve("harness.jar"), receipt.textField("harness_sha256"))
        ZipFile(directory.resolve("harness.jar").toFile()).use { require(it.getEntry("org/openjdk/jmh/runner/Runner.class") != null) { "Preserved archive is not the JMH harness" } }
        val targets = JmhArchiveEvidence.targets(directory, receipt)
        val inputs = JmhArchiveEvidence.inputs(directory, receipt)
        val rows = loadRows(directory, receipt)
        return JmhEvidenceRun(
            receipt,
            rows,
            JsonObject().apply {
                add("run_id", receipt.get("run_id"))
                add("repetition", receipt.get("repetition"))
                add("receipt_sha256", source.get("sha256"))
                add("results_sha256", receipt.get("results_sha256"))
            },
            targets,
            inputs,
        )
    }

    private fun verifyHeader(receipt: JsonObject) {
        require(receipt.textField("contract").contentEquals("strata-jmh-v1")) { "Unsupported JMH receipt" }
        require(receipt.textField("fork_verification").contentEquals("loaded-artifacts-per-iteration-v1")) { "Missing actual JMH fork verification" }
        val arguments = receipt.arrayField("arguments")
        require(0 < arguments.size()) { "Missing JMH options" }
        arguments.forEach { it.textValue() }
        val fixtures = receipt.objectField("fixture_identity")
        require(0 < fixtures.size()) { "Missing JMH fixture identity" }
        fixtures.entrySet().forEach { JvmEvidenceFiles.validateHash(it.value.textValue()) }
        val environment = receipt.objectField("environment")
        require(0 < environment.size() && environment.entrySet().all { it.value.textValue().isNotBlank() }) { "Missing JMH environment" }
        JvmEvidenceFiles.validateHash(receipt.textField("harness_sha256"))
    }

    private fun loadRows(
        directory: Path,
        receipt: JsonObject,
    ): Map<JmhEvidenceCase, JsonObject> {
        val registered = receipt.arrayField("registered_workloads").map { JmhEvidenceCase.registered(JsonParser.parseString(it.textValue()).asJsonArray) }
        require(registered.isNotEmpty() && registered.size == registered.toSet().size) { "Missing or duplicate registered JMH workload" }
        val resultPath = directory.resolve("results.json")
        val raw = JvmEvidenceFiles.array(resultPath, receipt.textField("results_sha256"))
        require(0 < raw.size() && raw.size() <= 16_384) { "Missing or oversized JMH results" }
        val rows = linkedMapOf<JmhEvidenceCase, JsonObject>()
        raw.forEach { value ->
            val row = value.asJsonObject
            val key = JmhEvidenceCase.row(row)
            require(rows.put(key, row) == null) { "Duplicate JMH result" }
            JmhMetricEvidence.verify(row)
        }
        require(rows.keys == registered.toSet()) { "JMH did not complete its registered workload matrix" }
        return rows
    }
}
