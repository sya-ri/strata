package dev.s7a.strata.runtime.minecraft.fabric

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import net.minecraft.IdentifierException
import net.minecraft.resources.Identifier
import java.math.BigDecimal

/**
 * Validates the regular-font provider graph used to derive the fixed printable-ASCII profile.
 *
 * The caller supplies immutable ASCII pixels and synchronous document reads. This function retains neither input and may run on any thread allowed by the reader.
 *
 * @param ascii copied `font/ascii.png` pixels.
 * @param readDocument reader for one selected font JSON resource identifier.
 * Provider traversal follows Minecraft's first-provider glyph selection and stops once every required printable-ASCII glyph and space metric has been resolved, so later fallback providers cannot replace the verified glyphs.
 *
 * @throws IllegalArgumentException when JSON syntax, identifiers, provider filtering, mapping, metrics, or a provider that precedes the complete ASCII contract differs from the 26.2 contract.
 * @throws Throwable when [readDocument] fails; its failure escapes unchanged.
 */
@JvmSynthetic
internal fun validateMinecraftRegularFontContract(
    ascii: DrawImage,
    readDocument: (Identifier) -> String,
) {
    MinecraftFontContractValidator(ascii, readDocument).validate()
}

private class MinecraftFontContractValidator(
    private val ascii: DrawImage,
    private val readDocument: (Identifier) -> String,
) {
    private val seen = HashSet<Identifier>()
    private val glyphs = HashSet<Int>()
    private var ascent: Int? = null
    private var spaceAdvance: Int? = null

    fun validate() {
        require(ascii.size == IntSize(128, 128)) { "Minecraft ASCII image must be 128 by 128 pixels." }
        visit(defaultFontIdentifier)
        require(ascent == 7 && spaceAdvance == 4 && glyphs.size == 94) {
            "Minecraft font metadata does not provide the 26.2 ASCII contract."
        }
    }

    private fun visit(identifier: Identifier) {
        if (seen.add(identifier).not()) return
        val root = JsonBoundary.parseDocument(readDocument(identifier), identifier)
        for (providerElement in JsonBoundary.requiredArray(root, "providers")) {
            if (isComplete()) return
            val provider =
                JsonBoundary.objectOrNull(providerElement)
                    ?: throw IllegalArgumentException("Minecraft font provider is not an object.")
            if (providerAppliesToRegularFont(provider)) {
                visitProvider(provider)
            }
        }
    }

    private fun isComplete(): Boolean = ascent == 7 && spaceAdvance == 4 && glyphs.size == 94

    private fun visitProvider(provider: JsonObject) {
        when (providerKinds[JsonBoundary.requiredString(provider, "type")] ?: ProviderKind.Other) {
            ProviderKind.Reference -> visitReference(provider)
            ProviderKind.Space -> visitSpace(provider)
            ProviderKind.Bitmap -> visitBitmap(provider)
            ProviderKind.Other -> throw IllegalArgumentException("Minecraft font metadata contains an unsupported provider type.")
        }
    }

    private fun visitReference(provider: JsonObject) {
        val id = JsonBoundary.parseIdentifier(JsonBoundary.requiredString(provider, "id"), "font reference")
        visit(Identifier.fromNamespaceAndPath(id.getNamespace(), "font/${id.getPath()}.json"))
    }

    private fun visitSpace(provider: JsonObject) {
        require(spaceAdvance == null) { "Minecraft font metadata contains more than one space provider." }
        val advances = JsonBoundary.requiredObject(provider, "advances")
        advances.entrySet().forEach { (character, _) ->
            if (character.codePointCount(0, character.length) == 1) {
                val codePoint = character.codePointAt(0)
                if (codePoint in printableAsciiRange) {
                    require(codePoint == " ".codePointAt(0)) { "Minecraft space metadata overrides a printable ASCII glyph." }
                }
            }
        }
        spaceAdvance = JsonBoundary.requiredInt(advances, " ")
    }

    private fun visitBitmap(provider: JsonObject) {
        val file = JsonBoundary.parseIdentifier(JsonBoundary.requiredString(provider, "file"), "bitmap file")
        val chars = JsonBoundary.requiredArray(provider, "chars")
        val candidateAscent = JsonBoundary.requiredInt(provider, "ascent")
        JsonBoundary.optionalInt(provider, "height")
        if (file == asciiIdentifier) {
            require(ascent == null) { "Minecraft font metadata contains more than one ASCII provider." }
            val height = JsonBoundary.optionalInt(provider, "height") ?: 8
            require(height == 8) { "Minecraft ASCII metadata must use an 8 pixel glyph height." }
            require(candidateAscent == 7) { "Minecraft ASCII ascent must be 7." }
            ascent = candidateAscent
            validateAsciiRows(chars)
        } else {
            validateNonAsciiRows(chars)
        }
    }

    private fun validateAsciiRows(chars: JsonArray) {
        require(chars.size() == 16) { "Minecraft ASCII metadata must contain 16 rows." }
        chars.forEachIndexed { row, rowElement ->
            val text = JsonBoundary.requiredStringValue(rowElement, "Minecraft ASCII character row")
            require(text.codePointCount(0, text.length) == 16) {
                "Minecraft ASCII metadata rows must contain 16 code points."
            }
            var column = 0
            text.codePoints().forEach { mappedCodePoint ->
                if (mappedCodePoint in printableAsciiRange) {
                    require(mappedCodePoint == row * 16 + column) {
                        "Minecraft ASCII metadata maps a printable code point to the wrong cell."
                    }
                    glyphs.add(mappedCodePoint)
                }
                column++
            }
        }
    }

    private fun validateNonAsciiRows(chars: JsonArray) {
        chars.forEach { rowElement ->
            val text = JsonBoundary.requiredStringValue(rowElement, "Minecraft bitmap character row")
            text.codePoints().forEach { mappedCodePoint ->
                require((mappedCodePoint in printableAsciiRange).not()) {
                    "Minecraft font metadata maps printable ASCII before the verified ASCII provider."
                }
            }
        }
    }

    private fun providerAppliesToRegularFont(provider: JsonObject): Boolean {
        val filterElement = provider.get("filter") ?: return true
        val filter =
            JsonBoundary.objectOrNull(filterElement)
                ?: throw IllegalArgumentException("Minecraft font provider filter is not an object.")
        require(filter.size() == 1 && filter.has("uniform")) {
            "Minecraft font provider uses an unsupported filter."
        }
        return JsonBoundary.requiredBoolean(filter, "uniform").not()
    }

    private enum class ProviderKind {
        Reference,
        Space,
        Bitmap,
        Other,
    }

    private object JsonBoundary {
        fun parseDocument(
            document: String,
            identifier: Identifier,
        ): JsonObject =
            try {
                objectOrNull(JsonParser.parseString(document))
                    ?: throw IllegalArgumentException("Minecraft font resource $identifier is not a JSON object.")
            } catch (failure: JsonParseException) {
                throw IllegalArgumentException("Minecraft font resource $identifier is malformed JSON.", failure)
            }

        fun parseIdentifier(
            value: String,
            label: String,
        ): Identifier =
            try {
                Identifier.parse(value)
            } catch (failure: IdentifierException) {
                throw IllegalArgumentException("Minecraft $label identifier is invalid.", failure)
            }

        fun objectOrNull(element: JsonElement): JsonObject? = if (element.isJsonObject) element.asJsonObject else null

        fun requiredString(
            objectValue: JsonObject,
            name: String,
        ): String =
            objectValue
                .get(name)
                ?.takeIf { element -> element.isJsonPrimitive && element.asJsonPrimitive.isString }
                ?.asString
                ?: throw IllegalArgumentException("Minecraft metadata is missing string $name.")

        fun requiredObject(
            objectValue: JsonObject,
            name: String,
        ): JsonObject =
            objectValue.get(name)?.let(::objectOrNull)
                ?: throw IllegalArgumentException("Minecraft metadata is missing object $name.")

        fun requiredArray(
            objectValue: JsonObject,
            name: String,
        ): JsonArray =
            objectValue
                .get(name)
                ?.takeIf(JsonElement::isJsonArray)
                ?.asJsonArray
                ?: throw IllegalArgumentException("Minecraft metadata is missing array $name.")

        fun requiredBoolean(
            objectValue: JsonObject,
            name: String,
        ): Boolean =
            objectValue
                .get(name)
                ?.takeIf { element -> element.isJsonPrimitive && element.asJsonPrimitive.isBoolean }
                ?.asBoolean
                ?: throw IllegalArgumentException("Minecraft metadata is missing boolean $name.")

        fun requiredInt(
            objectValue: JsonObject,
            name: String,
        ): Int = optionalInt(objectValue, name) ?: throw IllegalArgumentException("Minecraft metadata is missing integer $name.")

        fun optionalInt(
            objectValue: JsonObject,
            name: String,
        ): Int? {
            val element = objectValue.get(name) ?: return null
            require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
                "Minecraft metadata field $name is not an integer."
            }
            return try {
                BigDecimal(element.asString).intValueExact()
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("Minecraft metadata field $name is not an integer.", failure)
            } catch (failure: NumberFormatException) {
                throw IllegalArgumentException("Minecraft metadata field $name is not an integer.", failure)
            }
        }

        fun requiredStringValue(
            element: JsonElement,
            label: String,
        ): String =
            element.takeIf { candidate -> candidate.isJsonPrimitive && candidate.asJsonPrimitive.isString }?.asString
                ?: throw IllegalArgumentException("$label is not a string.")
    }

    private companion object {
        private val providerKinds =
            mapOf(
                "reference" to ProviderKind.Reference,
                "space" to ProviderKind.Space,
                "bitmap" to ProviderKind.Bitmap,
            )
        private val printableAsciiRange: IntRange = 0x21..0x7E
        private val asciiIdentifier: Identifier = Identifier.fromNamespaceAndPath("minecraft", "font/ascii.png")
        private val defaultFontIdentifier: Identifier = Identifier.fromNamespaceAndPath("minecraft", "font/default.json")
    }
}
