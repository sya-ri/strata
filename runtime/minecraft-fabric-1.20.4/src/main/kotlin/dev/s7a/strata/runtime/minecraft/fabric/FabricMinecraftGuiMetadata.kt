package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.resources.metadata.gui.GuiMetadataSection
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling
import net.minecraft.server.packs.resources.Resource

/**
 * Indicates that this Minecraft adapter stores widgets as metadata-backed GUI sprites.
 */
@get:JvmSynthetic
@Suppress("MayBeConstant")
internal val fabricMinecraftUsesGuiSprites: Boolean = true

/**
 * Decodes one GUI resource's nine-slice metadata into the detached common scaling contract.
 *
 * The operation reads the already-loaded resource metadata synchronously on the caller's thread and retains neither the resource nor Minecraft metadata objects.
 *
 * @param resource active resource whose GUI metadata is decoded.
 * @param path logical resource path used in validation failures.
 * @return detached portable nine-slice dimensions, borders, and center mode.
 * @throws IllegalArgumentException when metadata is absent or does not describe a nine-slice sprite.
 */
@JvmSynthetic
internal fun readFabricMinecraftGuiScaling(
    resource: Resource,
    path: String,
): FabricMinecraftGuiScaling {
    val section =
        resource
            .metadata()
            .getSection(GuiMetadataSection.TYPE)
            .orElseThrow { IllegalArgumentException("Minecraft resource $path is missing GUI scaling metadata.") }
    val scaling = section.scaling()
    require(scaling is GuiSpriteScaling.NineSlice) {
        "Minecraft resource $path must use nine-slice GUI scaling metadata."
    }
    val border = scaling.border()
    return FabricMinecraftGuiScaling(
        width = scaling.width(),
        height = scaling.height(),
        borderLeft = border.left(),
        borderTop = border.top(),
        borderRight = border.right(),
        borderBottom = border.bottom(),
        stretchesInner = minecraftNineSliceStretchesInner(scaling),
    )
}
