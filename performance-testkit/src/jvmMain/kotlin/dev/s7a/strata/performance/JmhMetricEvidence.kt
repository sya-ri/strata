package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Revalidates standard JMH raw metrics and independent fork confirmations without creating timings.
 */
internal object JmhMetricEvidence {
    internal val controlKeys: Set<String> =
        setOf("jmhVersion", "mode", "threads", "forks", "jvm", "jvmArgs", "jdkVersion", "vmName", "vmVersion", "warmupIterations", "warmupTime", "warmupBatchSize", "measurementIterations", "measurementTime", "measurementBatchSize")

    /**
     * Rejects missing conditions, incomplete fork/iteration matrices and unavailable metrics.
     */
    internal fun verify(row: JsonObject) {
        require(controlKeys.all(row::has)) { "Missing JMH execution condition" }
        setOf("threads", "forks", "warmupBatchSize", "measurementIterations", "measurementBatchSize").forEach { require(0 < row.countField(it)) }
        row.countField("warmupIterations")
        val mode = JmhEvidenceMode.decode(row.textField("mode"))
        val primary = row.objectField("primaryMetric")
        primary.textField("scoreUnit")
        checkNotNull(primary.get("score")).metricValue()
        val samples = primary.arrayField(if (mode == JmhEvidenceMode.SampleTime) "rawDataHistogram" else "rawData")
        verifyMatrix(samples, row.countField("forks"), row.countField("measurementIterations"))
        if (mode == JmhEvidenceMode.SampleTime) verifyHistograms(samples, primary) else samples.forEach { fork -> fork.asJsonArray.forEach { it.metricValue() } }
        val secondary = row.objectField("secondaryMetrics")
        verifyProvenance(secondary.objectField("strata.provenance"), row)
        metric(secondary, "gc.alloc.rate.norm", "B/op")
        metric(secondary, "gc.count", "counts")
        if (secondary.has("gc.time")) metric(secondary, "gc.time", "ms")
    }

    private fun verifyProvenance(
        provenance: JsonObject,
        row: JsonObject,
    ) {
        require(provenance.textField("scoreUnit").contentEquals("verified") && checkNotNull(provenance.get("score")).metricValue() == 1.0) { "JMH fork artifacts were not verified" }
        val confirmations = provenance.arrayField("rawData")
        verifyMatrix(confirmations, row.countField("forks"), row.countField("measurementIterations"))
        require(confirmations.all { fork -> fork.asJsonArray.all { it.metricValue() == 1.0 } }) { "Incomplete JMH fork provenance iterations" }
    }

    private fun verifyMatrix(
        samples: JsonArray,
        forks: Int,
        iterations: Int,
    ) {
        require(samples.size() == forks && samples.all { it.isJsonArray && it.asJsonArray.size() == iterations }) { "Incomplete JMH fork/iteration data" }
    }

    private fun verifyHistograms(
        samples: JsonArray,
        primary: JsonObject,
    ) {
        samples.forEach { fork ->
            fork.asJsonArray.forEach { iteration ->
                require(iteration.isJsonArray && 0 < iteration.asJsonArray.size()) { "Missing JMH sample histogram" }
                iteration.asJsonArray.forEach { value ->
                    require(value.isJsonArray && value.asJsonArray.size() == 2)
                    value.asJsonArray[0].metricValue()
                    val count = JsonObject().apply { add("count", value.asJsonArray[1]) }
                    require(0 < count.countField("count")) { "Invalid JMH histogram count" }
                }
            }
        }
        setOf("50.0", "95.0", "99.0").forEach { checkNotNull(primary.objectField("scorePercentiles").get(it)).metricValue() }
    }

    private fun metric(
        secondary: JsonObject,
        name: String,
        unit: String,
    ) {
        val metric = secondary.objectField(name)
        require(metric.textField("scoreUnit").contentEquals(unit)) { "Changed JMH metric unit: $name" }
        checkNotNull(metric.get("score")).metricValue()
    }
}
