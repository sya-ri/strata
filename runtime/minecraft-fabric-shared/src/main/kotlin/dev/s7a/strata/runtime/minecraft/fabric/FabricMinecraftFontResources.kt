package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager

/**
 * Copies the active resource manager's already filtered and overlaid font stack into an immutable offline snapshot.
 * Streams are opened only for referenced font files and closed synchronously; the returned snapshot retains no Minecraft object.
 * Per-resource stack layers preserve the native low-to-high document order and selected asset precedence without applying pack metadata twice.
 *
 * @param minecraft owner client whose current resource and language options are pinned by this call.
 * @return detached resource font snapshot for the compiled target release.
 * @throws Throwable when enumeration or a fatal resource failure prevents snapshot creation on the client thread.
 */
@JvmSynthetic
internal fun extractFabricMinecraftFontSnapshot(minecraft: Minecraft): MinecraftFontSnapshot {
    check(minecraft.isSameThread) { "Font resources must be extracted on the client thread." }
    return extractFabricMinecraftFontSnapshot(minecraft.resourceManager, fabricMinecraftFontCompatibility(), fabricMinecraftFontOptions(minecraft))
}

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
): MinecraftFontSnapshot {
    val stacks = manager.listResourceStacks("font") { id -> id.path.endsWith(".json") }
    val layers = ArrayList<MutableMap<String, Resource>>()
    stacks.forEach { (id, resources) ->
        while (layers.size < resources.size) layers.add(LinkedHashMap())
        resources.forEachIndexed { index, resource -> layers[index]["assets/${id.namespace}/${id.path}"] = resource }
    }
    val sources =
        layers.mapIndexed { index, resources -> FabricFontAssetSource("active-resource-layer-$index", resources) } +
            FabricFontAssetSource("active-resource-selection", emptyMap(), manager)
    return MinecraftFontSnapshot.load(sources, compatibility, options)
}

private class FabricFontAssetSource(
    override val name: String,
    private val resources: Map<String, Resource>,
    private val selected: ResourceManager? = null,
) : MinecraftFontAssetSource {
    override fun paths(): Set<String> = resources.keys.toSet()

    override fun read(path: String): ByteArray? = (resources[path] ?: selectedResource(path))?.open()?.use { it.readBytes() }

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
