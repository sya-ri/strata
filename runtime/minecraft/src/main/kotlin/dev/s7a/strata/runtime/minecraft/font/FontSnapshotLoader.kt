package dev.s7a.strata.runtime.minecraft.font

import com.google.gson.JsonObject
import dev.s7a.strata.resource.ResourceId
import java.util.Collections

/**
 * Synchronous callback-lifetime parser for the complete immutable font graph.
 *
 * @param sources caller-selected resource stack.
 * @param compatibility typed format capabilities.
 * @param options captured provider selection.
 * @param limits immutable input and image-allocation ceilings.
 */
internal class FontSnapshotLoader(
    sources: List<MinecraftFontAssetSource>,
    private val compatibility: MinecraftFontCompatibility,
    private val options: MinecraftFontOptions,
    private val limits: MinecraftFontLoadLimits,
) {
    private val diagnostics = ArrayList<MinecraftFontDiagnostic>()
    private val budget = FontLoadBudget(limits)
    private val resources = FontResourceStack(sources, compatibility, diagnostics, budget)
    private val bitmapChecks = HashMap<FontResource, Result<Boolean>>()
    private val faceChecks = HashMap<FontFaceKey, Result<Unit>>()
    private var nextIdentity = 0

    /**
     * Parses all documents and resolves reference graphs before releasing resource-source ownership.
     */
    fun load(): MinecraftFontSnapshot {
        val declarations = LinkedHashMap<ResourceId, List<FontProviderEntry>>()
        for (font in resources.fontIds()) {
            val providers = ArrayList<FontProviderEntry>()
            for (document in resources.fontDocuments(font).asReversed()) {
                val parsed =
                    runCatching {
                        val records = FontJson.array(FontJson.document(document.read().copyBytes(), limits).get("providers"))
                        budget.claim(FontLoadBudget.Kind.Providers, records.size().toLong())
                        records.map { value ->
                            parse(font, document.sourceName, FontJson.objectValue(value))
                        }
                    }.getOrElse { failure ->
                        if ((failure is Exception).not()) throw failure
                        diagnostics +=
                            MinecraftFontDiagnostic(
                                MinecraftFontDiagnostic.Kind.MalformedDocument,
                                font,
                                document.sourceName,
                                failure.message ?: "Malformed font document.",
                            )
                        emptyList()
                    }
                providers.addAll(parsed)
            }
            declarations[font] = providers
        }
        val resolved = FontGraphResolver(declarations, diagnostics, budget).resolve()
        return MinecraftFontSnapshot(compatibility, options, resolved, diagnostics, limits)
    }

    private fun parse(
        font: ResourceId,
        source: String,
        document: JsonObject,
    ): FontProviderEntry {
        val kindId = FontJson.identifier(FontJson.string(document.get("type")))
        val kind = requireNotNull(Kind.entries.firstOrNull { candidate -> candidate.id == kindId }) { "Unsupported font provider: $kindId." }
        val provider =
            when (kind) {
                Kind.Bitmap -> bitmap(font, source, document)
                Kind.Space -> space(document)
                Kind.Reference -> FontProvider.Reference(FontJson.identifier(FontJson.string(document.get("id"))))
                Kind.Unihex -> unihex(font, source, document)
                Kind.TrueType -> trueType(font, source, document)
            }
        val filter = if (compatibility.providerFilters) filter(document) else emptyMap()
        val identity = nextIdentity
        nextIdentity = Math.incrementExact(nextIdentity)
        return FontProviderEntry(identity, provider, Collections.unmodifiableMap(filter), source)
    }

    private fun bitmap(
        font: ResourceId,
        source: String,
        document: JsonObject,
    ): FontProvider {
        val height = document.get("height")?.let(FontJson::integer) ?: 8
        val ascent = FontJson.integer(document.get("ascent"))
        require(ascent <= height) { "Bitmap ascent cannot exceed its height." }
        val rows = FontJson.array(document.get("chars"))
        require(0 < rows.size()) { "Bitmap character rows must be nonempty." }
        val first = FontJson.string(rows[0])
        val columns = first.codePointCount(0, first.length)
        require(0 < columns) { "Bitmap character rows must be nonempty." }
        budget.claim(FontLoadBudget.Kind.Glyphs, rows.size().toLong() * columns)
        val cells = LinkedHashMap<Int, Int>()
        rows.forEachIndexed { y, value ->
            val row = FontJson.string(value)
            require(row.codePointCount(0, row.length) == columns) { "Bitmap rows must contain equal scalar counts." }
            var offset = 0
            repeat(columns) { x ->
                val codePoint = row.codePointAt(offset)
                offset += Character.charCount(codePoint)
                FontJson.validateScalar(codePoint)
                if (codePoint != 0) cells[codePoint] = Math.addExact(Math.multiplyExact(y, columns), x)
            }
        }
        val file = FontJson.identifier(FontJson.string(document.get("file")))
        return asset(font, source, ResourceId(file.namespace, "textures/${file.path}")) { resource ->
            bitmapChecks
                .getOrPut(resource) {
                    runCatching { resource.checkBitmap(limits) { amount -> budget.claim(FontLoadBudget.Kind.DecompressedBytes, amount) } }
                }.getOrThrow()
            FontProvider.Bitmap(resource, height, ascent, columns, rows.size(), Collections.unmodifiableMap(cells))
        }
    }

    private fun space(document: JsonObject): FontProvider.Space {
        val records = FontJson.objectValue(document.get("advances"))
        budget.claim(FontLoadBudget.Kind.Glyphs, records.size().toLong())
        val advances = LinkedHashMap<Int, Float>()
        for ((key, value) in records.entrySet()) {
            advances[FontJson.codePoint(key)] = FontJson.decimal(value)
        }
        return FontProvider.Space(Collections.unmodifiableMap(advances))
    }

    private fun trueType(
        font: ResourceId,
        source: String,
        document: JsonObject,
    ): FontProvider {
        val shift = document.get("shift")?.let(FontJson::array)
        require(shift == null || shift.size() == 2) { "TrueType shift requires two values." }
        val settings =
            MinecraftTrueTypeSettings(
                document.get("size")?.let(FontJson::decimal) ?: 11.0f,
                document.get("oversample")?.let(FontJson::decimal) ?: 1.0f,
                shift?.get(0)?.let(FontJson::decimal) ?: 0.0f,
                shift?.get(1)?.let(FontJson::decimal) ?: 0.0f,
            )
        val skipped = LinkedHashSet<Int>()
        document.get("skip")?.let { skip ->
            val values = if (skip.isJsonArray) skip.asJsonArray.asIterable() else listOf(skip)
            for (value in values) {
                val text = FontJson.string(value)
                budget.claim(FontLoadBudget.Kind.Glyphs, text.codePointCount(0, text.length).toLong())
                text.codePoints().forEach { codePoint -> skipped.add(codePoint) }
            }
        }
        val file = FontJson.identifier(FontJson.string(document.get("file")))
        return asset(font, source, ResourceId(file.namespace, "font/${file.path}")) { resource ->
            faceChecks
                .getOrPut(FontFaceKey(resource, settings)) {
                    runCatching {
                        budget.claim(FontLoadBudget.Kind.TrueTypeFaces, 1)
                        budget.claim(FontLoadBudget.Kind.TrueTypeInputBytes, resource.size.toLong())
                    }
                }.getOrThrow()
            FontProvider.TrueType(resource, settings, Collections.unmodifiableSet(skipped))
        }
    }

    private fun unihex(
        font: ResourceId,
        source: String,
        document: JsonObject,
    ): FontProvider {
        val overrides =
            document
                .get("size_overrides")
                ?.let(FontJson::array)
                ?.also { values -> budget.claim(FontLoadBudget.Kind.Glyphs, values.size().toLong()) }
                ?.map { value ->
                    val entry = FontJson.objectValue(value)
                    val first = FontJson.codePoint(FontJson.string(entry.get("from")))
                    val last = FontJson.codePoint(FontJson.string(entry.get("to")))
                    require(first < last) { "Unihex override ranges must have a strictly increasing endpoint." }
                    val left = FontJson.integer(entry.get("left"))
                    val right = FontJson.integer(entry.get("right"))
                    FontProvider.WidthOverride(first, last, left, right)
                }.orEmpty()
        val file = FontJson.identifier(FontJson.string(document.get("hex_file")))
        return asset(font, source, file) { resource ->
            FontProvider.Unihex(FontUnihexData.load(resource.copyBytes(), budget), Collections.unmodifiableList(overrides))
        }
    }

    private fun filter(document: JsonObject): Map<FontOption, Boolean> {
        val value = document.get("filter") ?: return emptyMap()
        return FontJson.objectValue(value).entrySet().associate { (key, expected) ->
            FontOption.decode(key) to FontJson.boolean(expected)
        }
    }

    private fun asset(
        font: ResourceId,
        source: String,
        id: ResourceId,
        create: (FontResource) -> FontProvider,
    ): FontProvider =
        runCatching {
            create(requireNotNull(resources.selected(id)) { "Font provider resource is missing: $id." })
        }.getOrElse { failure ->
            if ((failure is Exception).not()) throw failure
            val diagnostic =
                MinecraftFontDiagnostic(
                    MinecraftFontDiagnostic.Kind.ProviderLoadFailure,
                    font,
                    source,
                    failure.message ?: "Font provider could not be loaded: $id.",
                )
            diagnostics += diagnostic
            FontProvider.Failed(diagnostic)
        }

    private enum class Kind(
        val id: ResourceId,
    ) {
        Bitmap(ResourceId("minecraft", "bitmap")),
        Space(ResourceId("minecraft", "space")),
        Reference(ResourceId("minecraft", "reference")),
        Unihex(ResourceId("minecraft", "unihex")),
        TrueType(ResourceId("minecraft", "ttf")),
    }
}
