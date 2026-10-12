package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject

/**
 * Registers full presentation metrics only when every raw phase exposes their optional ancestor.
 * Legacy adapters may omit it only while GUI queries are unavailable; mixed matrices are rejected.
 */
internal fun NativeComponentPerformanceEvidence.presentationGpuMetricsPresent(reports: List<JsonObject>): Boolean {
    val phases = reports.flatMap { report -> report.getAsJsonArray("phases").map { it.asJsonObject } }
    val present = phases.map { it.has("presentation_gpu") }
    require(present.isNotEmpty() && present.distinct().size == 1) { "Inconsistent full presentation GPU metric availability" }
    val guiAvailable = phases.map { it.getAsJsonObject("native_gpu").get("available").asBoolean }
    require(guiAvailable.distinct().size == 1) { "Inconsistent GUI GPU measurement availability" }
    if (present.first()) {
        val fullAvailable = phases.map { phase -> phase.get("presentation_gpu").let { it.isJsonNull.not() && it.asJsonObject.get("available").asBoolean } }
        require(fullAvailable.distinct().size == 1 && fullAvailable.first() == guiAvailable.first()) { "Inconsistent full presentation GPU measurement availability" }
    } else {
        require(guiAvailable.first().not()) { "Available GUI queries require complete full presentation GPU pairs" }
    }
    return present.first()
}

/**
 * Requires complete, independent GUI and full-presentation timestamp pairs for every sampled frame.
 */
internal fun NativeComponentPerformanceEvidence.verifyGpu(
    phase: JsonObject,
    samples: Int,
) {
    val gpu = phase.getAsJsonObject("native_gpu")
    verifyGpuResult(gpu, samples)
    val presentation = phase.get("presentation_gpu")?.takeUnless { it.isJsonNull }?.asJsonObject
    if (gpu.get("available").asBoolean) {
        require(presentation != null && presentation.get("available").asBoolean) { "Available GUI queries require complete full presentation GPU pairs" }
        require(presentation.get("scope") != gpu.get("scope")) { "Full presentation GPU scope cannot be the GUI-only scope" }
    }
    presentation?.let {
        require(it.get("available") == gpu.get("available")) { "Inconsistent GPU scope availability" }
        verifyGpuResult(it, samples)
    }
}

/**
 * Preserves explicit unavailable scopes and rejects incomplete or invalid native timestamp observations.
 */
private fun NativeComponentPerformanceEvidence.verifyGpuResult(
    gpu: JsonObject,
    samples: Int,
) {
    if (gpu.get("available").asBoolean) {
        require(gpu.get("samples").asInt == samples)
        val period = gpu.get("timestamp_period_ns").asDouble
        require(period.isFinite() && 0.0 < period && gpu.get("scope").asString.isNotBlank())
        listOf("duration", "operation_to_completion_observation").forEach { field ->
            val distribution = gpu.getAsJsonObject(field)
            require(distribution != null && distribution.get("samples")?.asInt == samples) { "Incomplete native GPU interval" }
        }
    } else {
        require(gpu.get("reason").asString.isNotBlank())
        require(gpu.get("duration").isJsonNull && gpu.get("operation_to_completion_observation").isJsonNull)
    }
}
