package dev.s7a.strata.performance

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Frozen native-report field spelling, independent of the scenario and application domain.
 * This does not make evidence from different collector versions comparable.
 */
public object LegacyNativeEvidence {
    /**
     * Translates a complete kit distribution to historical field names without recomputing statistics.
     * Unavailable collection remains empty and cannot satisfy a required-metric assertion.
     */
    public fun distribution(source: JsonElement?): JsonObject =
        JsonObject().apply {
            if (source == null || source.isJsonNull) {
                addProperty("availableSamples", 0)
            } else {
                val measured = source.asJsonObject
                add("availableSamples", measured.get("samples"))
                add("raw", measured.get("raw").deepCopy())
                for ((legacy, current) in mapOf("p50" to "p50_ns", "p95" to "p95_ns", "p99" to "p99_ns", "max" to "max_ns", "total" to "total_ns")) {
                    add(legacy, measured.get(current))
                }
            }
        }
}
