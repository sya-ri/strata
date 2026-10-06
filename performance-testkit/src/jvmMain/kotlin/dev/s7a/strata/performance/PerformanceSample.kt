package dev.s7a.strata.performance

import com.google.gson.JsonObject

/**
 * One complete measured interval and its last application-owned result, without frame history.
 *
 * @param evidence detached metric and work report.
 * @param value last operation result, available for assertions and captures outside timing.
 */
public data class PerformanceSample<T>(
    public val evidence: JsonObject,
    public val value: T,
)
