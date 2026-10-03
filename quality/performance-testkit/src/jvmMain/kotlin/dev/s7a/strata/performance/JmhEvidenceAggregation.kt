package dev.s7a.strata.performance

import com.google.gson.JsonObject

/**
 * Aggregates validated independent JMH scores without reinterpreting AverageTime fields as latency quantiles.
 */
internal object JmhEvidenceAggregation {
    /**
     * Produces one workload summary while requiring identical execution conditions and primary units.
     */
    internal fun summarize(
        key: JmhEvidenceCase,
        rows: List<JsonObject>,
    ): JsonObject {
        JvmPerformanceEvidence.verifyEqual(rows, JmhMetricEvidence.controlKeys)
        val metrics = rows.map { it.objectField("primaryMetric") }
        JvmPerformanceEvidence.verifyEqual(metrics, setOf("scoreUnit"))
        val times =
            rows.mapNotNull {
                it
                    .objectField("secondaryMetrics")
                    .getAsJsonObject("gc.time")
                    ?.get("score")
                    ?.metricValue()
            }
        return key.fields().apply {
            add("primary_unit", metrics.first().get("scoreUnit"))
            addProperty("primary_score_median", JvmPerformanceEvidence.median(metrics.map { checkNotNull(it.get("score")).metricValue() }))
            addProperty("allocation_bytes_per_operation_median", medianSecondary(rows, "gc.alloc.rate.norm"))
            addProperty("gc_count_median", medianSecondary(rows, "gc.count"))
            addProperty("gc_time_ms_median_available", if (times.isEmpty()) null else JvmPerformanceEvidence.median(times))
            addProperty("gc_time_available_repetitions", times.size)
            if (JmhEvidenceMode.decode(key.mode) == JmhEvidenceMode.SampleTime) {
                add(
                    "per_run_percentile_medians",
                    JsonObject().apply {
                        setOf("50.0", "95.0", "99.0").forEach { percentile ->
                            addProperty(percentile, JvmPerformanceEvidence.median(metrics.map { checkNotNull(it.objectField("scorePercentiles").get(percentile)).metricValue() }))
                        }
                    },
                )
            }
        }
    }

    /**
     * Records deltas without setting timing gates or turning missing/zero-baseline ratios into numbers.
     */
    internal fun compare(
        before: JsonObject,
        after: JsonObject,
    ): JsonObject {
        val keys = setOf("benchmark", "mode", "params", "primary_unit")
        JvmPerformanceEvidence.verifyEqual(listOf(before, after), keys)
        return JsonObject().apply {
            keys.forEach { add(it, before.get(it)) }
            add(
                "metrics",
                JsonObject().apply {
                    setOf("primary_score_median", "allocation_bytes_per_operation_median", "gc_count_median", "gc_time_ms_median_available").forEach { name ->
                        val old = before.get(name)
                        val new = after.get(name)
                        val available = old.isJsonNull.not() && new.isJsonNull.not()
                        add(
                            name,
                            JsonObject().apply {
                                add("baseline", old)
                                add("candidate", new)
                                addProperty("delta", if (available) new.metricValue() - old.metricValue() else null)
                                addProperty("candidate_to_baseline", if (available && old.metricValue() != 0.0) new.metricValue() / old.metricValue() else null)
                            },
                        )
                    }
                },
            )
            if (JmhEvidenceMode.decode(before.textField("mode")) == JmhEvidenceMode.SampleTime) {
                add(
                    "per_run_percentile_medians",
                    JsonObject().apply {
                        add("baseline", before.get("per_run_percentile_medians"))
                        add("candidate", after.get("per_run_percentile_medians"))
                    },
                )
            }
        }
    }

    private fun medianSecondary(
        rows: List<JsonObject>,
        metric: String,
    ): Double = JvmPerformanceEvidence.median(rows.map { checkNotNull(it.objectField("secondaryMetrics").objectField(metric).get("score")).metricValue() })
}
