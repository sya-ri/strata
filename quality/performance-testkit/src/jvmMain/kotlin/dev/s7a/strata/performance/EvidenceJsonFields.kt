package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Typed external JSON field decoding shared by JVM evidence contracts.
 * Missing, boolean, string-encoded numeric and non-finite metrics are not silently coerced.
 */
internal fun JsonObject.objectField(name: String): JsonObject = checkNotNull(get(name)).also { require(it.isJsonObject) { "Expected object: $name" } }.asJsonObject

/**
 * Returns a required detached evidence array without coercion.
 */
internal fun JsonObject.arrayField(name: String): JsonArray = checkNotNull(get(name)).also { require(it.isJsonArray) { "Expected array: $name" } }.asJsonArray

/**
 * Decodes one required JSON string; identifiers may additionally require nonblank values.
 */
internal fun JsonElement.textValue(): String {
    require(isJsonPrimitive && asJsonPrimitive.isString) { "Expected JSON string" }
    return asString
}

/**
 * Decodes a required nonblank identifier or condition string.
 */
internal fun JsonObject.textField(name: String): String = checkNotNull(get(name)).textValue().also { require(it.isNotBlank()) { "Blank evidence field: $name" } }

/**
 * Decodes a finite, nonnegative metric instead of accepting unavailable data as zero.
 */
internal fun JsonElement.metricValue(): Double {
    require(isJsonPrimitive && asJsonPrimitive.isNumber) { "Expected JSON number" }
    return asDouble.also { require(it.isFinite() && 0 <= it) { "Negative or non-finite evidence metric" } }
}

/**
 * Decodes a strictly integral, nonnegative JSON count within JVM integer bounds.
 */
internal fun JsonObject.countField(name: String): Int {
    val value = checkNotNull(get(name))
    value.metricValue()
    val integer = checkNotNull(value.asString.toIntOrNull()) { "Nonintegral evidence count: $name" }
    require(0 <= integer) { "Negative evidence count: $name" }
    return integer
}
