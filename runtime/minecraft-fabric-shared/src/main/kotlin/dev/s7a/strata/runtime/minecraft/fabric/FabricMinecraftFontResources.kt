package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.font.MinecraftBoundedFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimitException
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.readMinecraftFontBytes
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager
import java.util.Collections

/**
 * Detaches font document stacks and their individually selected assets from a stable resource manager.
 * Only the valid `font` directory is enumerated; provider assets use exact resource lookups and may reside under any valid provider path.
 * The caller keeps the manager stable on its owning thread until return; all streams close synchronously and no manager is retained.
 *
 * @param manager active resource view with filters and overlays already applied.
 * @param compatibility compiler-selected provider capabilities.
 * @param options current font and language selection.
 * @return immutable font snapshot without resource-manager ownership.
 * @throws Throwable when enumeration or a fatal resource failure prevents snapshot creation.
 */
@JvmSynthetic
internal fun extractFabricMinecraftFontSnapshot(
    manager: ResourceManager,
    compatibility: MinecraftFontCompatibility,
    options: MinecraftFontOptions,
): MinecraftFontSnapshot = extractFabricMinecraftFontSnapshot(manager, compatibility, options, MinecraftFontLoadLimits())

/**
 * Detaches a stable native font stack under explicitly supplied immutable input ceilings.
 * Native enumeration is borrowed; Strata applies limits before copying entries or opening resource streams.
 * Ordinary resource failures become detached diagnostics where the font loader supports independent bundle recovery.
 *
 * @param manager caller-owned resource view, kept stable on its owner thread until return.
 * @param compatibility compiler-selected font capabilities.
 * @param options captured language and provider selection.
 * @param limits inclusive resource, record, and image allocation ceilings.
 * @return immutable snapshot retaining no native resource or stream.
 * @throws Throwable when enumeration or a fatal failure prevents snapshot creation; all opened streams still close.
 */
@JvmSynthetic
internal fun extractFabricMinecraftFontSnapshot(
    manager: ResourceManager,
    compatibility: MinecraftFontCompatibility,
    options: MinecraftFontOptions,
    limits: MinecraftFontLoadLimits,
): MinecraftFontSnapshot {
    val stacks = manager.listResourceStacks("font") { id -> id.path.endsWith(".json") }
    requireFontLimit(stacks.size <= limits.maxFontDocuments, "Active font document count exceeds its loading ceiling.")
    val layers = ArrayList<MutableMap<String, Resource>>()
    var documents = 0L
    stacks.forEach { (id, resources) ->
        documents += resources.size
        requireFontLimit(documents <= limits.maxFontDocuments && resources.size < limits.maxSources, "Active font document stack exceeds its loading ceiling.")
        requireFontLimit(documents <= limits.maxEntries, "Active font entries exceed their aggregate loading ceiling.")
        requireFontLimit(8L + id.namespace.length + id.path.length <= limits.maxPathLength, "Active font document path exceeds its loading ceiling.")
        val path = "assets/${id.namespace}/${id.path}"
        while (layers.size < resources.size) layers.add(LinkedHashMap())
        resources.forEachIndexed { index, resource ->
            val layer = layers[index]
            requireFontLimit(layer.size < limits.maxSourceEntries, "Active font layer exceeds its entry ceiling.")
            layer[path] = resource
        }
    }
    val sources =
        layers.mapIndexed { index, resources -> FabricFontAssetSource("active-resource-layer-$index", resources) } +
            FabricFontAssetSource("active-resource-selection", emptyMap(), manager)
    return MinecraftFontSnapshot.load(sources, compatibility, options, limits)
}

private fun requireFontLimit(
    condition: Boolean,
    message: String,
) {
    if (condition.not()) throw MinecraftFontLoadLimitException(message)
}

private class FabricFontAssetSource(
    override val name: String,
    private val resources: Map<String, Resource>,
    private val selected: ResourceManager? = null,
) : MinecraftBoundedFontAssetSource {
    override fun paths(): Set<String> = paths(MinecraftFontLoadLimits())

    override fun paths(limits: MinecraftFontLoadLimits): Set<String> = paths(limits) {}

    override fun paths(
        limits: MinecraftFontLoadLimits,
        onEntryExamined: () -> Unit,
    ): Set<String> {
        val paths = LinkedHashSet<String>()
        for (path in resources.keys) {
            onEntryExamined()
            if (limits.maxSourceEntries <= paths.size) throw MinecraftFontLoadLimitException("Active font layer exceeds its entry ceiling.")
            if (limits.maxPathLength < path.length) throw MinecraftFontLoadLimitException("Active font document path exceeds its loading ceiling.")
            paths.add(path)
        }
        return Collections.unmodifiableSet(paths)
    }

    override fun read(path: String): ByteArray? = read(path, MinecraftFontLoadLimits())

    override fun read(
        path: String,
        limits: MinecraftFontLoadLimits,
    ): ByteArray? {
        if (limits.maxPathLength < path.length) throw MinecraftFontLoadLimitException("Active font asset path exceeds its loading ceiling.")
        return (resources[path] ?: selectedResource(path))?.open()?.use { input -> input.readMinecraftFontBytes(limits.maxAssetBytes) }
    }

    private fun selectedResource(path: String): Resource? {
        val manager = selected ?: return null
        if (path.startsWith("assets/").not()) return null
        val location = path.removePrefix("assets/")
        val separator = location.indexOf('/')
        if (separator < 1 || location.lastIndex <= separator) return null
        val id = minecraftResourceLocation(location.substring(0, separator), location.substring(separator + 1))
        return manager.getResource(id).orElse(null)
    }
}
