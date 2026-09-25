package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.render.DrawImage;
import net.minecraft.client.Minecraft;

/**
 * Retains one version-native player skin until its owner-thread frame boundary.
 *
 * <p>Implementations may retain the immutable native skin descriptor but must detach all returned pixels from Minecraft resources and textures.</p>
 */
interface FabricMinecraftSkinReference {
    /**
     * Snapshots this resolved skin on the Minecraft client thread.
     *
     * @param minecraft active client that owns the selected resource and texture managers.
     * @return immutable detached 64 by 64 straight-ARGB pixels.
     * @throws RuntimeException when the resolved texture is absent, released, unsupported, or not normalized.
     */
    DrawImage snapshot(Minecraft minecraft);
}
