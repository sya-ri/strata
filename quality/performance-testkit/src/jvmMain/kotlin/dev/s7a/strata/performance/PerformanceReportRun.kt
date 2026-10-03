package dev.s7a.strata.performance

import com.google.gson.JsonObject

/**
 * One immutable input snapshot and its verified phase registration.
 */
internal data class PerformanceReportRun(
    val report: JsonObject,
    val source: JsonObject,
    val phases: Map<List<String>, JsonObject>,
)
