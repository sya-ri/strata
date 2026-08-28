package dev.s7a.strata.runtime.minecraft.font

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.s7a.strata.resource.ResourceId

/**
 * Callback-lifetime resource stack that applies pack filters and active overlays before snapshot reads.
 *
 * @param sources pack sources in increasing priority order.
 * @param compatibility typed overlay capabilities.
 * @param diagnostics callback-owned output receiving detached metadata failures.
 * @param budget loader-owned aggregate counters, never retained by the snapshot.
 */
internal class FontResourceStack(
    sources: List<MinecraftFontAssetSource>,
    compatibility: MinecraftFontCompatibility,
    private val diagnostics: MutableList<MinecraftFontDiagnostic>,
    private val budget: FontLoadBudget,
) {
    private val entries = LinkedHashMap<ResourceId, MutableList<Location>>()
    private val assetLayers = ArrayList<AssetLayer>()
    private val selectedResources = HashMap<ResourceId, Result<FontResource?>>()

    init {
        sources.forEach { source ->
            runCatching { addSource(source, compatibility) }.getOrElse { failure ->
                if (failure is MinecraftFontLoadLimitException) {
                    metadataFailure(source, "Source loading limit exceeded; source excluded.", failure)
                } else {
                    throw failure
                }
            }
        }
    }

    /**
     * Lists all font-family identifiers represented by resource documents.
     * Unsupported derived identifiers produce detached document diagnostics without opening those locations or excluding valid siblings.
     * Only identifier validation failures are handled; unexpected failures propagate.
     */
    fun fontIds(): List<ResourceId> =
        entries.entries
            .filter { (id, _) -> id.path.startsWith("font/") && id.path.endsWith(".json") }
            .mapNotNull { (id, locations) ->
                runCatching { ResourceId(id.namespace, id.path.removePrefix("font/").removeSuffix(".json")) }.getOrElse { failure ->
                    if ((failure is IllegalArgumentException).not()) throw failure
                    for (location in locations.asReversed()) {
                        diagnostics +=
                            MinecraftFontDiagnostic(
                                MinecraftFontDiagnostic.Kind.MalformedDocument,
                                null,
                                location.sourceName,
                                "Font document path cannot form a supported font identifier: ${location.path}.",
                            )
                    }
                    null
                }
            }

    /**
     * Returns callback-lifetime low-to-high document locations without opening their contents.
     * Callers read each location inside the same failure boundary used to parse that document.
     */
    fun fontDocuments(font: ResourceId): List<Location> {
        val id = ResourceId(font.namespace, "font/${font.path}.json")
        return entries[id].orEmpty().toList()
    }

    /**
     * Reads the highest-priority bytes for one resource, including assets omitted from enumeration.
     * Accepted source filters and overlays apply before considering lower sources.
     * A listed resource that disappears or any failed read remains a failure instead of selecting a lower replacement.
     * Success, absence, and ordinary failures are memoized for this load only; neither sources nor failures enter the resulting snapshot.
     */
    fun selected(id: ResourceId): FontResource? =
        selectedResources
            .getOrPut(id) {
                runCatching { readSelected(id) }.onFailure { failure -> if ((failure is Exception).not()) throw failure }
            }.getOrThrow()

    private fun readSelected(id: ResourceId): FontResource? {
        for (layer in assetLayers.asReversed()) {
            val resource = layer.read(id)
            if (resource != null) return resource
            if (layer.blocks(id)) return null
        }
        return null
    }

    private fun addSource(
        source: MinecraftFontAssetSource,
        compatibility: MinecraftFontCompatibility,
    ) {
        val paths = budget.paths(source).map(String::checkedFontSourcePath).sorted()
        val metadata =
            runCatching { budget.read(source, "pack.mcmeta", document = true)?.let { bytes -> FontJson.document(bytes, budget.limits) } }.getOrElse { failure ->
                metadataFailure(source, "Cannot read pack metadata; source excluded.", failure)
                return
            }
        val selectedOverlays =
            if (compatibility.packOverlays && metadata != null) {
                runCatching { overlays(metadata, compatibility) }.getOrElse { failure ->
                    metadataFailure(source, "Cannot parse overlay metadata.", failure)
                    if (compatibility.rejectMalformedOverlayMetadata) return
                    emptyList()
                }
            } else {
                emptyList()
            }
        val blocked =
            runCatching { metadata?.let(::filters).orEmpty() }.getOrElse { failure ->
                metadataFailure(source, "Cannot parse resource filter metadata; filter ignored.", failure)
                emptyList()
            }
        val selected = LinkedHashMap<ResourceId, Location>()
        addRoot(source, paths, "", selected)
        for (overlay in selectedOverlays) addRoot(source, paths, "$overlay/", selected)
        val assetLayer = AssetLayer(source, paths.toSet(), listOf("") + selectedOverlays.map { overlay -> "$overlay/" }, blocked, budget)
        assetLayers.add(assetLayer)
        entries.keys.removeAll(assetLayer::blocks)
        selected.forEach { (id, location) -> entries.getOrPut(id, ::ArrayList).add(location) }
    }

    private fun metadataFailure(
        source: MinecraftFontAssetSource,
        message: String,
        failure: Throwable,
    ) {
        if ((failure is Exception).not()) throw failure
        diagnostics +=
            MinecraftFontDiagnostic(
                MinecraftFontDiagnostic.Kind.PackMetadataFailure,
                null,
                source.name,
                failure.message?.let { detail -> "$message $detail" } ?: message,
            )
    }

    private fun addRoot(
        source: MinecraftFontAssetSource,
        paths: List<String>,
        root: String,
        selected: MutableMap<ResourceId, Location>,
    ) {
        val prefix = "${root}assets/"
        for (path in paths) {
            if (path.startsWith(prefix)) {
                path.removePrefix(prefix).fontResourceIdentifier()?.let { id ->
                    if (id.path.startsWith("font/") && id.path.endsWith(".json")) {
                        budget.claim(FontLoadBudget.Kind.FontDocuments, 1)
                        selected[id] = Location(id, source, path, budget)
                    }
                }
            }
        }
    }

    private fun filters(metadata: JsonObject): List<Rule> {
        val filter = metadata.get("filter") ?: return emptyList()
        val block = FontJson.objectValue(filter).get("block") ?: return emptyList()
        return FontJson.array(block).map { value ->
            val rule = FontJson.objectValue(value)
            Rule(
                Regex(rule.get("namespace")?.let(FontJson::string) ?: ".*"),
                Regex(rule.get("path")?.let(FontJson::string) ?: ".*"),
            )
        }
    }

    private fun overlays(
        metadata: JsonObject,
        compatibility: MinecraftFontCompatibility,
    ): List<String> {
        val overlayRoot = metadata.get("overlays") ?: return emptyList()
        val values = FontJson.objectValue(overlayRoot).get("entries")
        return FontJson.array(values).mapNotNull { value ->
            val entry = FontJson.objectValue(value)
            val directory = FontJson.string(entry.get("directory")).checkedFontSourcePath()
            require(Regex("[a-zA-Z0-9_.-]+").matches(directory)) { "Overlay directories must be single safe path segments." }
            if (applies(entry, compatibility)) directory else null
        }
    }

    private fun applies(
        entry: JsonObject,
        compatibility: MinecraftFontCompatibility,
    ): Boolean {
        val minimum = entry.get("min_format")
        val maximum = entry.get("max_format")
        if (compatibility.minorPackFormats && (minimum != null || maximum != null)) {
            require(minimum != null && maximum != null) { "Overlay min_format and max_format must appear together." }
            val current = PackFormat(compatibility.packFormat, compatibility.packFormatMinor)
            val lower = format(minimum, 0)
            val upper = format(maximum, Int.MAX_VALUE)
            require(lower <= upper) { "Overlay format range is reversed." }
            return lower <= current && current <= upper
        }
        val formats = requireNotNull(entry.get("formats")) { "Overlay formats are missing." }
        val lower: Int
        val upper: Int
        if (formats.isJsonObject) {
            lower = FontJson.integer(formats.asJsonObject.get("min_inclusive"))
            upper = FontJson.integer(formats.asJsonObject.get("max_inclusive"))
        } else {
            lower = FontJson.integer(formats)
            upper = lower
        }
        require(lower <= upper) { "Overlay format range is reversed." }
        return compatibility.packFormat in lower..upper
    }

    private fun format(
        value: JsonElement,
        defaultMinor: Int,
    ): PackFormat {
        if (value.isJsonArray) {
            val components = value.asJsonArray
            require(components.size() in 1..2) { "Pack format must contain one or two components." }
            return PackFormat(FontJson.integer(components[0]), if (components.size() == 2) FontJson.integer(components[1]) else defaultMinor)
        }
        return PackFormat(FontJson.integer(value), defaultMinor)
    }

    private data class PackFormat(
        val major: Int,
        val minor: Int,
    ) : Comparable<PackFormat> {
        init {
            require(0 <= major && 0 <= minor) { "Pack-format components must be non-negative." }
        }

        override fun compareTo(other: PackFormat): Int = compareValuesBy(this, other, PackFormat::major, PackFormat::minor)
    }

    private data class Rule(
        val namespace: Regex,
        val path: Regex,
    )

    private class AssetLayer(
        private val source: MinecraftFontAssetSource,
        private val listedPaths: Set<String>,
        private val roots: List<String>,
        private val filters: List<Rule>,
        private val budget: FontLoadBudget,
    ) {
        fun read(id: ResourceId): FontResource? {
            for (root in roots.asReversed()) {
                val path = "${root}assets/${id.namespace}/${id.path}"
                val bytes = budget.read(source, path)
                if (bytes != null) return FontResource(id, source.name, bytes)
                require((path in listedPaths).not()) { "Font resource disappeared while loading: $id." }
            }
            return null
        }

        fun blocks(id: ResourceId): Boolean =
            // Native namespace selection and path filtering each evaluate the entire block list independently.
            filters.any { rule -> rule.namespace.containsMatchIn(id.namespace) } && filters.any { rule -> rule.path.containsMatchIn(id.path) }
    }

    /**
     * Callback-lifetime resource location that keeps reading inside the caller's document failure boundary.
     * This value borrows the source only while constructing a snapshot and must never be retained by the snapshot.
     *
     * @param id resource identifier supplied by the validated resource path.
     * @param source caller-owned stable source.
     * @property path canonical source-relative path used by detached diagnostics.
     * @param budget loader-owned counters used before detaching the resource.
     */
    internal class Location(
        private val id: ResourceId,
        private val source: MinecraftFontAssetSource,
        val path: String,
        private val budget: FontLoadBudget,
    ) {
        /**
         * Immutable source label available even when resource reading fails.
         */
        val sourceName: String = source.name

        /**
         * Reads and detaches bytes while the source remains stable on the loader thread.
         *
         * @return an owned resource without source references.
         * @throws Throwable when the listed resource disappears or cannot be read.
         */
        fun read(): FontResource = FontResource(id, sourceName, requireNotNull(budget.read(source, path, document = true)) { "Font resource disappeared while loading: $id." })
    }
}
