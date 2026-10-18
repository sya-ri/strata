package dev.s7a.strata.runtime.minecraft.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import dev.s7a.strata.render.DrawImage;
import dev.s7a.strata.resource.ResourceId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;

/** Retains a resolved native skin descriptor and snapshots it on the client thread. */
record FabricMinecraftResolvedSkin(PlayerSkin skin) implements FabricMinecraftSkinReference {
    @Override
    public DrawImage snapshot(Minecraft minecraft) {
        var identifier = skin.texture();
        var texture = minecraft.getTextureManager().getTexture(identifier);
        if (texture instanceof DynamicTexture dynamic) {
            NativeImage image = dynamic.getPixels();
            if (image == null) {
                throw new IllegalStateException("The downloaded player skin has already been released.");
            }
            return FabricMinecraftSkinImages.snapshot(image);
        }
        return FabricMinecraftSkinImages.requireNormalized(FabricMinecraftAssets.loadMinecraftUiImage(
                new ResourceId(identifier.getNamespace(), identifier.getPath())));
    }
}
