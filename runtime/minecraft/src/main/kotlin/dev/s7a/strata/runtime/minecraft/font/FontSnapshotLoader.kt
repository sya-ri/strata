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
 */
internal class FontSnapshotLoader(
    sources: List<MinecraftFontAssetSource>,
    private val compatibility: MinecraftFontCompatibility,
    private val options: MinecraftFontOptions,
) {
    private val diagnostics = ArrayList<MinecraftFontDiagnostic>()
    private val resources = FontResourceStack(sources, compatibility, diagnostics)
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
                        FontJson.array(FontJson.document(document.read().copyBytes()).get("providers")).map { value ->
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
        val resolved = FontGraphResolver(declarations, diagnostics).resolve()
        return MinecraftFontSnapshot(compatibility, options, resolved, diagnostics)
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
        val rows = FontJson.array(document.get("chars")).map { row -> FontJson.string(row).codePoints().toArray() }
        require(rows.isNotEmpty() && rows.first().isNotEmpty()) { "Bitmap character rows must be nonempty." }
        val columns = rows.first().size
        require(rows.all { row -> row.size == columns }) { "Bitmap rows must contain equal scalar counts." }
        val cells = LinkedHashMap<Int, Int>()
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, codePoint ->
                FontJson.validateScalar(codePoint)
                if (codePoint != 0) cells[codePoint] = Math.addExact(Math.multiplyExact(y, columns), x)
            }
        }
        val file = FontJson.identifier(FontJson.string(document.get("file")))
        return asset(font, source, ResourceId(file.namespace, "textures/${file.path}")) { resource ->
            FontProvider.Bitmap(resource, height, ascent, columns, rows.size, Collections.unmodifiableMap(cells))
        }
    }

    private fun space(document: JsonObject): FontProvider.Space {
        val advances = LinkedHashMap<Int, Float>()
        for ((key, value) in FontJson.objectValue(document.get("advances")).entrySet()) {
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
            val values = if (skip.isJsonArray) skip.asJsonArray.toList() else listOf(skip)
            for (value in values) FontJson.string(value).codePoints().forEach { codePoint -> skipped.add(codePoint) }
        }
        val file = FontJson.identifier(FontJson.string(document.get("file")))
        return asset(font, source, ResourceId(file.namespace, "font/${file.path}")) { resource ->
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
            FontProvider.Unihex(FontUnihexData.load(resource.copyBytes()), Collections.unmodifiableList(overrides))
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
