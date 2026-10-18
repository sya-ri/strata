package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.server.packs.resources.Resource

/**
 * Constructs Minecraft's unnamed dynamic texture for the caller-owned [image].
 */
internal fun createFabricMinecraftDynamicTexture(image: NativeImage): DynamicTexture = DynamicTexture(image)

/**
 * Indicates whether this exact client renders widgets from individual GUI sprites.
 */
@get:JvmSynthetic
@Suppress("MayBeConstant")
internal val fabricMinecraftUsesGuiSprites: Boolean = false

/**
 * Indicates whether this exact client's tooltip frame stretches its center.
 */
@get:JvmSynthetic
@Suppress("MayBeConstant")
internal val minecraftTooltipFrameStretchesInner: Boolean = false

/**
 * Rejects GUI sprite metadata because Minecraft 1.20 and 1.20.1 render their widgets from fixed atlases and code-defined colors.
 *
 * @param resource unused resource borrowed for signature parity with GUI-sprite adapters.
 * @param path logical resource path used in the failure message.
 * @return this function never returns.
 * @throws IllegalArgumentException on every call.
 */
@Suppress("UnusedParameter")
internal fun readFabricMinecraftGuiScaling(
    resource: Resource,
    path: String,
): FabricMinecraftGuiScaling = throw IllegalArgumentException("Minecraft 1.20/1.20.1 resource $path has no GUI sprite metadata.")
