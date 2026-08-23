package dev.s7a.strata.runtime.minecraft.fabric;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.blaze3d.platform.NativeImage;
import dev.s7a.strata.component.PlayerSkinSource;
import dev.s7a.strata.geometry.IntSize;
import dev.s7a.strata.render.DrawImage;
import dev.s7a.strata.render.DrawImages;
import dev.s7a.strata.resource.ResourceId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Resolves and snapshots player skins through the direct-result resource-location API used by Minecraft 1.21.3.
 */
final class FabricMinecraftSkinBridge {
    private static final IntSize PLAYER_SKIN_SIZE = new IntSize(64, 64);

    private FabricMinecraftSkinBridge() {
    }

    /**
     * Starts one asynchronous native skin lookup without accessing client-thread texture state from its completion thread.
     *
     * @param minecraft borrowed active client.
     * @param source platform-neutral player selector.
     * @return eventual optional version-neutral skin reference.
     * @throws IllegalArgumentException when the source kind is unsupported.
     */
    static CompletableFuture<Optional<FabricMinecraftSkinReference>> lookup(
            Minecraft minecraft,
            PlayerSkinSource source) {
        CompletableFuture<GameProfile> profile;
        if (source == PlayerSkinSource.CurrentPlayer.INSTANCE) {
            profile = CompletableFuture.completedFuture(minecraft.getGameProfile());
        } else if (source instanceof PlayerSkinSource.Name name) {
            profile = unresolvedName(name.getValue());
        } else if (source instanceof PlayerSkinSource.Uuid uuid) {
            profile = unresolvedUuid(uuid.getValue());
        } else {
            throw new IllegalArgumentException("Unsupported player skin source: " + source.getClass().getName());
        }
        return profile
                .thenCompose(minecraft.getSkinManager()::getOrLoad)
                .thenApply(skin -> Optional.of(new ResolvedSkin(skin)));
    }

    /**
     * Snapshots the current player's selected skin on the client thread.
     *
     * @param minecraft borrowed active client.
     * @return detached normalized pixels.
     * @throws IllegalStateException when no current player exists or its texture is unavailable.
     */
    static DrawImage current(Minecraft minecraft) {
        var player = minecraft.player;
        if (player == null) {
            throw new IllegalStateException("A current Minecraft player is required to load its skin.");
        }
        return new ResolvedSkin(player.getSkin()).snapshot(minecraft);
    }

    private static CompletableFuture<GameProfile> unresolvedName(String name) {
        return resolve(new ResolvableProfile(Optional.of(name), Optional.empty(), new PropertyMap()));
    }

    private static CompletableFuture<GameProfile> unresolvedUuid(UUID uuid) {
        return resolve(new ResolvableProfile(Optional.empty(), Optional.of(uuid), new PropertyMap()));
    }

    private static CompletableFuture<GameProfile> resolve(ResolvableProfile profile) {
        return profile.resolve().thenApply(ResolvableProfile::gameProfile);
    }

    private record ResolvedSkin(PlayerSkin skin) implements FabricMinecraftSkinReference {
        @Override
        public DrawImage snapshot(Minecraft minecraft) {
            var identifier = skin.texture();
            var texture = minecraft.getTextureManager().getTexture(identifier);
            if (texture instanceof DynamicTexture dynamic) {
                NativeImage image = dynamic.getPixels();
                if (image == null) {
                    throw new IllegalStateException("The downloaded player skin has already been released.");
                }
                return snapshotNative(image);
            }
            return requireNormalized(FabricMinecraftAssets.loadMinecraftUiImage(
                    new ResourceId(identifier.getNamespace(), identifier.getPath())));
        }
    }

    private static DrawImage snapshotNative(NativeImage image) {
        return requireNormalized(DrawImages.createDrawImage(
                new IntSize(image.getWidth(), image.getHeight()),
                FabricMinecraftDynamicTextureFactoryKt.copyFabricMinecraftArgbPixels(image)));
    }

    private static DrawImage requireNormalized(DrawImage image) {
        if (image.getSize().equals(PLAYER_SKIN_SIZE) == false) {
            throw new IllegalArgumentException("Minecraft player skins must normalize to exactly 64 by 64 pixels.");
        }
        return image;
    }
}
