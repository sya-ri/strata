package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared processing of fixed CPU and native phase reports from immutable raw evidence.
 * Consumers register conditions, metrics and untimed work assertions, never their own aggregation engine.
 */
public object JvmPerformanceReports {
    /**
     * Validates independent invocations and aggregates each run's metrics separately.
     * Missing optional measurements remain JSON null and absolute times never determine success.
     */
    @Suppress("unused") // Public Maven API used by downstream JVM evidence processors.
    public fun summarize(
        paths: List<Path>,
        collector: Path,
        contract: PerformanceReportContract,
        metrics: List<PerformanceReportMetric>,
        validate: (JsonObject) -> Unit = {},
    ): JsonObject {
        val runs = load(paths, collector, contract, validate)
        return summary(runs, contract, metrics)
    }

    /**
     * Revalidates both raw sides and rejects copied invocations, different matrices or controlled inputs.
     */
    public fun compare(
        baseline: List<Path>,
        candidate: List<Path>,
        collector: Path,
        contract: PerformanceReportContract,
        metrics: List<PerformanceReportMetric>,
        validate: (JsonObject) -> Unit = {},
    ): JsonObject {
        val before = load(baseline, collector, contract, validate)
        val after = load(candidate, collector, contract, validate)
        val reports = (before + after).map { it.report }
        JvmPerformanceEvidence.verifyRepetitions(reports, contract.repetitions * 2)
        JvmPerformanceEvidence.verifyEqual(reports, contract.reportConditions)
        require(after.first().phases.keys == before.first().phases.keys) { "Changed phase matrix" }
        val rows = JsonArray()
        before.first().phases.keys.forEach { identity ->
            val old = before.map { it.phases.getValue(identity) }
            val new = after.map { it.phases.getValue(identity) }
            JvmPerformanceEvidence.verifyEqual(old + new, contract.phaseConditions)
            rows.add(
                identityRow(identity, contract).apply {
                    add("before", aggregate(old, metrics))
                    add("after", aggregate(new, metrics))
                },
            )
        }
        return JsonObject().apply {
            addProperty("contract", "strata-phase-comparison-v1")
            addProperty("status", "passed")
            add("collector_identity", PerformanceJson.collectorIdentity())
            addProperty("workload_id", contract.workloadId)
            add("before", summary(before, contract, metrics))
            add("after", summary(after, contract, metrics))
            add("phases", rows)
        }
    }

    /**
     * Compares complete named image inventories from actual bytes outside timed intervals.
     */
    public fun compareImages(
        baseline: Path,
        candidate: Path,
        expectedCount: Int,
    ): JsonArray {
        fun images(directory: Path): Map<String, Path> =
            Files.list(directory).use { entries ->
                entries.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".png") }.toList().associateBy { it.fileName.toString() }
            }
        val old = images(baseline)
        val new = images(candidate)
        require(0 < expectedCount && old.size == expectedCount && old.keys == new.keys) { "Incomplete image inventory" }
        return JsonArray().apply {
            old.toSortedMap().forEach { (name, path) ->
                val hash = ArtifactIdentity.file(path)
                JvmEvidenceFiles.verifyFile(new.getValue(name), hash)
                add(
                    JsonObject().apply {
                        addProperty("name", name)
                        addProperty("sha256", hash)
                    },
                )
            }
        }
    }

    private fun load(
        paths: List<Path>,
        collector: Path,
        contract: PerformanceReportContract,
        validate: (JsonObject) -> Unit,
    ): List<PerformanceReportRun> {
        require(0 < contract.phaseCount && "samples" in contract.phaseConditions && contract.reportConditions.intersect(contract.variantReportFields).isEmpty() && contract.phaseConditions.intersect(contract.variantPhaseFields).isEmpty()) { "Incomplete report registration" }
        val runs =
            paths.map { path ->
                val (report, source) = JvmEvidenceFiles.snapshot(path)
                require(report.textField("workload_id").contentEquals(contract.workloadId) && report.countField("schema_version") == contract.schemaVersion) { "Wrong workload or schema" }
                validate(report.deepCopy())
                source.add("run_id", report.get("run_id"))
                PerformanceReportRun(report, source, JvmPerformanceEvidence.indexPhases(report, contract.phaseKeys, contract.phaseCount))
            }
        val reports = runs.map { it.report }
        JvmPerformanceEvidence.verifyCollectors(reports, collector)
        JvmPerformanceEvidence.verifyRepetitions(reports, contract.repetitions)
        JvmPerformanceEvidence.verifyEqual(reports, contract.reportConditions + contract.variantReportFields)
        runs.forEach { require(it.phases.keys == runs.first().phases.keys) { "Changed phase matrix" } }
        runs.first().phases.keys.forEach { identity ->
            JvmPerformanceEvidence.verifyEqual(runs.map { it.phases.getValue(identity) }, contract.phaseConditions + contract.variantPhaseFields)
        }
        return runs
    }

    private fun summary(
        runs: List<PerformanceReportRun>,
        contract: PerformanceReportContract,
        metrics: List<PerformanceReportMetric>,
    ): JsonObject =
        JsonObject().apply {
            addProperty("contract", "strata-phase-summary-v1")
            addProperty("status", "passed")
            add("collector_identity", PerformanceJson.collectorIdentity())
            addProperty("workload_id", contract.workloadId)
            add("sources", JsonArray().apply { runs.forEach { add(it.source.deepCopy()) } })
            add(
                "conditions",
                JsonObject().apply {
                    (contract.reportConditions + contract.variantReportFields).sorted().forEach {
                        add(
                            it,
                            runs
                                .first()
                                .report
                                .get(it)
                                .deepCopy(),
                        )
                    }
                },
            )
            add(
                "phases",
                JsonArray().apply {
                    runs
                        .first()
                        .phases.keys
                        .forEach { identity -> add(identityRow(identity, contract).apply { add("metrics", aggregate(runs.map { it.phases.getValue(identity) }, metrics)) }) }
                },
            )
        }

    private fun identityRow(
        identity: List<String>,
        contract: PerformanceReportContract,
    ): JsonObject =
        JsonObject().apply {
            contract.phaseKeys.zip(identity).forEach { (key, value) -> add(key, JsonParser.parseString(value)) }
        }

    private fun aggregate(
        phases: List<JsonObject>,
        metrics: List<PerformanceReportMetric>,
    ): JsonObject {
        require(metrics.isNotEmpty() && metrics.map { it.name }.toSet().size == metrics.size)
        return JsonObject().apply {
            metrics.forEach { metric ->
                require(metric.name.isNotBlank() && metric.scale.isFinite() && 0 < metric.scale)
                val values = phases.map { phase -> metricValue(phase, metric) }
                add(metric.name, if (values.any { it == null }) JsonNull.INSTANCE else JsonPrimitive(JvmPerformanceEvidence.median(values.map(::checkNotNull))))
            }
        }
    }

    private fun metricValue(
        phase: JsonObject,
        metric: PerformanceReportMetric,
    ): Double? {
        val value = resolve(phase, metric.path)
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid numeric measurement" }
        var number = value.asDouble
        require(number.isFinite() && 0 <= number)
        metric.divisorPath?.let { path ->
            val divisor = resolve(phase, path)
            require(divisor.isJsonPrimitive && divisor.asJsonPrimitive.isNumber && divisor.asDouble.isFinite() && 0 < divisor.asDouble) { "Invalid measurement divisor" }
            number /= divisor.asDouble
        }
        val result = number * metric.scale
        require(result.isFinite()) { "Measurement conversion overflow" }
        return result
    }

    private fun resolve(
        root: JsonObject,
        path: List<String>,
    ): JsonElement {
        require(path.isNotEmpty())
        return path.fold(root as JsonElement) { current, key ->
            if (current.isJsonNull) current else checkNotNull(current.asJsonObject.get(key)) { "Missing measurement: $path" }
        }
    }
}
