package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Canonical external benchmark identity, with actual parameter strings rather than serialized ordering.
 */
internal data class JmhEvidenceCase(
    val benchmark: String,
    val mode: String,
    val parameters: Map<String, String>,
) : Comparable<JmhEvidenceCase> {
    override fun compareTo(other: JmhEvidenceCase): Int {
        val names = compareValuesBy(this, other, JmhEvidenceCase::benchmark, JmhEvidenceCase::mode)
        if (names != 0) return names
        val left = parameters.toSortedMap().entries.toList()
        val right =
            other.parameters
                .toSortedMap()
                .entries
                .toList()
        left.zip(right).forEach { (first, second) ->
            val key = first.key.compareTo(second.key)
            if (key != 0) return key
            val value = first.value.compareTo(second.value)
            if (value != 0) return value
        }
        return left.size.compareTo(right.size)
    }

    /**
     * Returns detached fields shared by summary and comparison rows.
     */
    internal fun fields(): JsonObject =
        JsonObject().apply {
            addProperty("benchmark", benchmark)
            addProperty("mode", mode)
            add("params", JsonObject().apply { parameters.toSortedMap().forEach(::addProperty) })
        }

    /**
     * Decodes generated registration and actual JMH rows at the external JSON boundary.
     */
    internal companion object {
        /**
         * Decodes one generated benchmark/mode/parameter registration tuple.
         */
        internal fun registered(value: JsonArray): JmhEvidenceCase {
            require(value.size() == 3) { "Invalid registered JMH workload" }
            return decode(value[0].textValue(), value[1].textValue(), value[2].asJsonObject)
        }

        /**
         * Decodes an actual JMH result using the same parameter identity contract.
         */
        internal fun row(value: JsonObject): JmhEvidenceCase = decode(value.textField("benchmark"), value.textField("mode"), value.getAsJsonObject("params") ?: JsonObject())

        private fun decode(
            benchmark: String,
            mode: String,
            parameters: JsonObject,
        ): JmhEvidenceCase {
            require(benchmark.isNotBlank() && mode.isNotBlank())
            val values =
                parameters.entrySet().associate { (name, value) ->
                    require(name.isNotBlank())
                    name to value.textValue()
                }
            return JmhEvidenceCase(benchmark, mode, values)
        }
    }
}
