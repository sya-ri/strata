package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Shared JVM validation and aggregation for detached collector-bound evidence.
 * Consumers provide workload-specific conditions; they do not implement another comparison engine.
 */
public object JvmPerformanceEvidence {
    /**
     * Reads a bounded strict UTF-8 report and attaches the digest of that exact input snapshot.
     * The source receipt is transport metadata; the original file is never modified.
     */
    @Suppress("unused") // Public Maven API used by downstream JVM evidence processors.
    public fun readReport(path: Path): JsonObject {
        val (report, receipt) = JvmEvidenceFiles.snapshot(path)
        require(report.has("source_receipt").not()) { "Raw evidence must not contain an injected source receipt" }
        report.add("source_receipt", receipt)
        return report
    }

    /**
     * Requires the exact loaded collector archive, independently of its advertised version or filename.
     * Comparison runs from compiled directories are rejected; use the actual measured testkit JAR.
     */
    public fun verifyCollectors(
        reports: List<JsonObject>,
        collector: Path,
    ): JsonObject {
        require(reports.isNotEmpty()) { "No performance reports" }
        val type = JvmPerformanceEvidence::class.java
        val origin =
            Path.of(
                type.protectionDomain.codeSource.location
                    .toURI(),
            )
        require(Files.isRegularFile(origin) && Files.isSameFile(origin, collector)) { "Run evidence processing from the selected testkit JAR" }
        val expected = PerformanceJson.collectorIdentity()
        JvmEvidenceFiles.verifyFile(collector, expected.textField("code_source_sha256"))
        require(reports.all { it.objectField("collector_identity") == expected }) { "Collector differs; recollect both sides with this testkit JAR" }
        return expected
    }

    /**
     * Requires successful independent canonical UUID invocations, rejecting copied or partial receipts.
     */
    public fun verifyRepetitions(
        reports: List<JsonObject>,
        count: Int = 3,
    ) {
        require(0 < count && reports.size == count) { "Missing or extra performance repetitions" }
        val identities =
            reports.map { report ->
                require(report.textField("status").contentEquals("passed")) { "An invocation did not pass" }
                val identity = report.textField("run_id")
                require(UUID.fromString(identity).toString().contentEquals(identity.lowercase())) { "Noncanonical invocation identity" }
                identity.lowercase()
            }
        require(identities.toSet().size == count) { "Duplicate performance invocation" }
    }

    /**
     * Preserves exact controlled conditions and rejects missing fields instead of projecting them away.
     */
    public fun verifyEqual(
        reports: List<JsonObject>,
        keys: Set<String>,
    ) {
        require(reports.isNotEmpty())
        keys.forEach { key ->
            val expected = checkNotNull(reports.first().get(key)) { "Missing comparison condition: $key" }
            require(reports.all { it.has(key) && it.get(key) == expected }) { "Changed or missing comparison condition: $key" }
        }
    }

    /**
     * Indexes actual phase identities without accepting duplicate or missing registrations.
     * Structured identities remain JSON values, so viewport objects and ordinary scalar IDs are supported.
     */
    public fun indexPhases(
        report: JsonObject,
        keys: List<String>,
        expectedCount: Int? = null,
    ): Map<List<JsonElement>, JsonObject> {
        require(keys.isNotEmpty() && keys.toSet().size == keys.size)
        val indexed = linkedMapOf<List<JsonElement>, JsonObject>()
        report.arrayField("phases").forEach { value ->
            val phase = value.asJsonObject
            val identity = keys.map { checkNotNull(phase.get(it)).deepCopy() }
            require(indexed.put(identity, phase.deepCopy()) == null) { "Duplicate performance phase: $identity" }
        }
        require(indexed.isNotEmpty() && (expectedCount == null || indexed.size == expectedCount)) { "Incomplete performance phase inventory" }
        return indexed
    }

    /**
     * Aggregates independent finite nonnegative measurements without pooling per-run latency quantiles.
     */
    public fun median(values: List<Double>): Double {
        require(values.isNotEmpty() && values.all { it.isFinite() && 0 <= it }) { "Missing, negative or non-finite metric" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1] / 2 + sorted[middle] / 2
    }

    /**
     * Projects consumer-declared descriptive exclusions while preserving all other nested controlled inputs.
     */
    public fun projectContract(
        value: JsonElement,
        excludedFields: Set<String>,
        excludedPrefixes: Set<String> = emptySet(),
    ): JsonElement =
        when {
            value.isJsonObject -> {
                JsonObject().apply {
                    value.asJsonObject.entrySet().sortedBy { it.key }.forEach { (name, child) ->
                        if (name !in excludedFields && excludedPrefixes.none(name::startsWith)) add(name, projectContract(child, excludedFields, excludedPrefixes))
                    }
                }
            }

            value.isJsonArray -> {
                JsonArray().apply { value.asJsonArray.forEach { add(projectContract(it, excludedFields, excludedPrefixes)) } }
            }

            else -> {
                value.deepCopy()
            }
        }
}
