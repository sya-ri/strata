package dev.s7a.strata.runtime.minecraft.font

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/**
 * Preflights JSON nesting and token counts before Gson allocates a complete mutable document tree.
 * The same lenient input syntax used by Gson's tree parser is preserved, including native ignored metadata fields.
 */
internal object FontJsonBounds {
    /**
     * Streams a bounded immutable string without retaining reader state or allocating a document tree.
     *
     * @param contents already byte-bounded JSON text.
     * @param limits inclusive nesting and token ceilings.
     * @throws Throwable when parsing or a ceiling check fails.
     */
    fun check(
        contents: String,
        limits: MinecraftFontLoadLimits,
    ) {
        JsonReader(StringReader(contents)).use { reader ->
            reader.isLenient = true
            var depth = 0L
            var tokens = 0L
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                requireFontLimit(++tokens, limits.maxJsonValues.toLong(), "JSON tokens")
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> {
                        requireFontLimit(++depth, limits.maxJsonDepth.toLong(), "JSON nesting")
                        reader.beginArray()
                    }

                    JsonToken.BEGIN_OBJECT -> {
                        requireFontLimit(++depth, limits.maxJsonDepth.toLong(), "JSON nesting")
                        reader.beginObject()
                    }

                    JsonToken.END_ARRAY -> {
                        reader.endArray()
                        depth--
                    }

                    JsonToken.END_OBJECT -> {
                        reader.endObject()
                        depth--
                    }

                    JsonToken.NAME -> {
                        reader.nextName()
                    }

                    else -> {
                        reader.skipValue()
                    }
                }
            }
        }
    }
}
