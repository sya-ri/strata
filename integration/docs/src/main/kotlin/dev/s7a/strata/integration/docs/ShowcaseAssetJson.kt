package dev.s7a.strata.integration.docs

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Decodes bounded external showcase metadata after a streaming depth and token preflight.
 * Returned JSON belongs to the caller; input arrays and readers are never retained.
 */
internal object ShowcaseAssetJson {
    /**
     * Parses one UTF-8 object, rejecting malformed input and ceilings before allocating a JSON tree.
     */
    fun document(
        bytes: ByteArray,
        limits: MinecraftFontLoadLimits,
    ): JsonObject {
        require(bytes.size <= limits.maxDocumentBytes) { "Showcase JSON exceeds its document byte ceiling." }
        val text =
            StandardCharsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        JsonReader(StringReader(text)).use { reader -> preflight(reader, limits) }
        return objectValue(JsonParser.parseString(text))
    }

    /**
     * Requires one external JSON object without accepting coercions or null.
     */
    fun objectValue(value: JsonElement?): JsonObject {
        require(value != null && value.isJsonObject) { "Showcase metadata requires a JSON object." }
        return value.asJsonObject
    }

    /**
     * Requires a JSON string without accepting numeric or boolean coercions.
     */
    fun string(value: JsonElement?): String {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Showcase metadata requires a string." }
        return value.asString
    }

    /**
     * Requires one exactly representable signed integer, rejecting fractions and overflow.
     */
    fun integer(value: JsonElement?): Int {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Showcase metadata requires an integer." }
        return value.asBigDecimal.intValueExact()
    }

    /**
     * Requires a JSON boolean without accepting string coercions.
     */
    fun boolean(value: JsonElement?): Boolean {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Showcase metadata requires a boolean." }
        return value.asBoolean
    }

    private fun preflight(
        reader: JsonReader,
        limits: MinecraftFontLoadLimits,
    ) {
        var depth = 0
        var tokens = 0
        while (reader.peek() != JsonToken.END_DOCUMENT) {
            require(++tokens <= limits.maxJsonValues) { "Showcase JSON exceeds its token ceiling." }
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    depth++
                }

                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    depth++
                }

                JsonToken.END_OBJECT -> {
                    reader.endObject()
                    depth--
                }

                JsonToken.END_ARRAY -> {
                    reader.endArray()
                    depth--
                }

                JsonToken.NAME -> {
                    reader.nextName()
                }

                JsonToken.STRING, JsonToken.NUMBER -> {
                    reader.nextString()
                }

                JsonToken.BOOLEAN -> {
                    reader.nextBoolean()
                }

                JsonToken.NULL -> {
                    reader.nextNull()
                }

                JsonToken.END_DOCUMENT -> {
                    break
                }
            }
            require(depth <= limits.maxJsonDepth) { "Showcase JSON exceeds its nesting ceiling." }
        }
    }
}
