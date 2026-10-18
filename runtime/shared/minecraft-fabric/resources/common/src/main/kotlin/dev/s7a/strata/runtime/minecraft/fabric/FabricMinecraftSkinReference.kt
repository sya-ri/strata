package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.render.DrawImage
import net.minecraft.client.Minecraft

/**
 * Retains an immutable native skin descriptor until its owner-thread frame boundary.
 * Returned pixels are detached from native resources and textures.
 */
internal fun interface FabricMinecraftSkinReference {
    /**
     * Copies normalized 64 by 64 straight-ARGB pixels on the owning client thread.
     * Missing, released, unsupported or unnormalized textures fail with their original runtime exception.
     */
    fun snapshot(minecraft: Minecraft): DrawImage
}
