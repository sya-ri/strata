package dev.s7a.strata.runtime.minecraft.font

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.s7a.strata.resource.ResourceId

/**
 * Strict JSON decoding at the font-resource boundary; returned values retain no mutable application input.
 */
internal object FontJson {
    /**
     * Parses UTF-8 JSON bytes, rejecting a non-object root.
     */
    fun document(bytes: ByteArray): JsonObject = objectValue(JsonParser.parseString(bytes.toString(Charsets.UTF_8)))

    /**
     * Requires an object value, reporting malformed external data as an argument failure.
     */
    fun objectValue(value: JsonElement?): JsonObject {
        require(value != null && value.isJsonObject) { "Expected a JSON object." }
        return value.asJsonObject
    }

    /**
     * Requires a JSON array without coercion.
     */
    fun array(value: JsonElement?): JsonArray {
        require(value != null && value.isJsonArray) { "Expected a JSON array." }
        return value.asJsonArray
    }

    /**
     * Requires a JSON string without numeric or boolean coercion.
     */
    fun string(value: JsonElement?): String {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Expected a JSON string." }
        return value.asString
    }

    /**
     * Requires one exactly representable integer.
     */
    fun integer(value: JsonElement?): Int {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Expected a JSON integer." }
        return value.asBigDecimal.intValueExact()
    }

    /**
     * Requires one finite floating-point number.
     */
    fun decimal(value: JsonElement?): Float {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Expected a JSON number." }
        val result = value.asFloat
        require(result.isFinite()) { "Font values must be finite." }
        return result
    }

    /**
     * Requires a JSON boolean without string coercion.
     */
    fun boolean(value: JsonElement?): Boolean {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Expected a JSON boolean." }
        return value.asBoolean
    }

    /**
     * Decodes a resource identifier, applying the native default namespace at this external boundary.
     */
    fun identifier(value: String): ResourceId {
        val separator = value.indexOf(':')
        if (separator < 0) return ResourceId("minecraft", value)
        val namespace = if (separator == 0) "minecraft" else value.substring(0, separator)
        return ResourceId(namespace, value.substring(separator + 1))
    }

    /**
     * Requires a string containing exactly one Unicode scalar.
     */
    fun codePoint(value: String): Int {
        require(value.codePointCount(0, value.length) == 1) { "A glyph mapping must contain one Unicode scalar." }
        return value.codePointAt(0).also(::validateScalar)
    }

    /**
     * Rejects values outside Unicode and isolated UTF-16 surrogates.
     */
    fun validateScalar(value: Int) {
        require(value in 0..0x10FFFF && (value in 0xD800..0xDFFF).not()) { "Glyph keys must be Unicode scalar values." }
    }
}
