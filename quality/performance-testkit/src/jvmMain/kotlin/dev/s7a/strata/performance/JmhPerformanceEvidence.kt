package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path

/**
 * JVM-only evidence validation, aggregation and comparison over standard JMH output.
 * JMH owns collection; this API consumes preserved receipts and never times an operation itself.
 */
public object JmhPerformanceEvidence {
    private val receiptConditions = setOf("contract", "fork_verification", "arguments", "fixture_identity", "harness_sha256", "environment", "registered_workloads")

    /**
     * Requires independent complete matrices with identical collector, fixture, inputs, targets and host conditions.
     * SampleTime preserves per-run percentile medians; AverageTime summarizes only scores.
     */
    public fun summarize(
        directories: List<Path>,
        collector: Path,
        repetitions: Int = 3,
    ): JsonObject {
        require(0 < repetitions && directories.size == repetitions)
        val runs = directories.map { JmhRunEvidence.load(it, collector) }
        val receipts = runs.map(JmhEvidenceRun::receipt)
        JvmPerformanceEvidence.verifyRepetitions(receipts, repetitions)
        require(receipts.map { it.countField("repetition") }.toSet() == (0 until repetitions).toSet()) { "Missing or duplicate JMH repetition index" }
        JvmPerformanceEvidence.verifyEqual(receipts, receiptConditions)
        require(runs.all { it.targets == runs.first().targets && it.inputs == runs.first().inputs && it.rows.keys == runs.first().rows.keys }) { "JMH runtime, fixture inputs or workload matrix changed" }
        val cases =
            runs
                .first()
                .rows.keys
                .sorted()
        return JsonObject().apply {
            addProperty("contract", "strata-jmh-summary-v1")
            addProperty("status", "passed")
            add("collector_identity", JvmPerformanceEvidence.verifyCollectors(receipts, collector))
            addProperty("repetitions", repetitions)
            addProperty("case_count", cases.size)
            add("sources", JsonArray().apply { runs.forEach { add(it.sources) } })
            add("cases", JsonArray().apply { cases.forEach { key -> add(JmhEvidenceAggregation.summarize(key, runs.map { it.rows.getValue(key) })) } })
            add("conditions", conditions(runs.first(), cases))
            add("target_identities", runs.first().targets)
        }
    }

    /**
     * Revalidates both raw sides; only target bytes may differ and invocation IDs cannot be reused.
     * Absolute operation times never determine validation success.
     */
    public fun compare(
        baseline: List<Path>,
        candidate: List<Path>,
        collector: Path,
        repetitions: Int = 3,
    ): JsonObject {
        val before = summarize(baseline, collector, repetitions)
        val after = summarize(candidate, collector, repetitions)
        JvmPerformanceEvidence.verifyEqual(listOf(before, after), setOf("conditions"))
        val identities = (before.arrayField("sources") + after.arrayField("sources")).map { it.asJsonObject.textField("run_id") }
        require(identities.toSet().size == 2 * repetitions) { "JMH comparison reuses an invocation" }
        val left = before.objectField("target_identities")
        val right = after.objectField("target_identities")
        require(left.keySet() == right.keySet() && left.keySet().all { left.objectField(it).get("representative") == right.objectField(it).get("representative") }) { "JMH target module or representative inventory changed" }
        val old = before.arrayField("cases")
        val new = after.arrayField("cases")
        require(old.size() == new.size())
        return JsonObject().apply {
            addProperty("contract", "strata-jmh-comparison-v1")
            addProperty("status", "passed")
            add("collector_identity", before.get("collector_identity"))
            add("baseline", before)
            add("candidate", after)
            add("cases", JsonArray().apply { old.zip(new).forEach { (first, second) -> add(JmhEvidenceAggregation.compare(first.asJsonObject, second.asJsonObject)) } })
            add(
                "changed_targets",
                JsonArray().apply {
                    left
                        .keySet()
                        .sorted()
                        .filter { left.get(it) != right.get(it) }
                        .forEach(::add)
                },
            )
        }
    }

    private fun conditions(
        run: JmhEvidenceRun,
        cases: List<JmhEvidenceCase>,
    ): JsonObject =
        JsonObject().apply {
            receiptConditions.filter { it.contentEquals("contract").not() }.forEach { add(it, run.receipt.get(it)) }
            add("inputs", run.inputs)
            add(
                "controls",
                JsonArray().apply {
                    cases.forEach { key ->
                        add(key.fields().apply { JmhMetricEvidence.controlKeys.forEach { add(it, run.rows.getValue(key).get(it)) } })
                    }
                },
            )
        }
}
