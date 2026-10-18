@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.resource.ResourceId
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.PlayerSkin

/**
 * Retains only the native descriptor until its owner-thread snapshot copies detached skin pixels.
 */
internal fun fabricMinecraftSkinReference(skin: PlayerSkin): FabricMinecraftSkinReference =
    FabricMinecraftSkinReference { minecraft ->
        val identifier = skin.texture()
        when (val texture = minecraft.textureManager.getTexture(identifier)) {
            is DynamicTexture -> {
                val image = checkNotNull(texture.pixels) { "The downloaded player skin has already been released." }
                createPlayerSkinSnapshot(image)
            }

            else -> {
                requireNormalizedPlayerSkin(loadMinecraftUiImage(ResourceId(identifier.namespace, identifier.path)))
            }
        }
    }
