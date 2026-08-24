package dev.s7a.strata.runtime.minecraft.fabric;

import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.minecraft.InsecurePublicKeyException;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.blaze3d.platform.NativeImage;
import dev.s7a.strata.component.PlayerSkinSource;
import dev.s7a.strata.geometry.IntSize;
import dev.s7a.strata.render.DrawImage;
import dev.s7a.strata.render.DrawImages;
import dev.s7a.strata.resource.ResourceId;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;

/**
 * Resolves and snapshots player skins through the authlib 4 profile-texture API used by Minecraft 1.20.1.
 */
final class FabricMinecraftSkinBridge {
    private static final IntSize PLAYER_SKIN_SIZE = new IntSize(64, 64);

    private FabricMinecraftSkinBridge() {
    }

    /**
     * Starts one asynchronous profile and skin lookup without retaining Minecraft texture objects.
     *
     * @param minecraft borrowed active client whose proxy and authenticated services are used.
     * @param source platform-neutral player selector.
     * @return eventual optional detached or resource-backed skin reference.
     * @throws IllegalArgumentException when the source kind is unsupported.
     */
    static CompletableFuture<Optional<FabricMinecraftSkinReference>> lookup(
            Minecraft minecraft,
            PlayerSkinSource source) {
        CompletableFuture<GameProfile> profile;
        if (source == PlayerSkinSource.CurrentPlayer.INSTANCE) {
            profile = CompletableFuture.completedFuture(minecraft.getUser().getGameProfile());
        } else if (source instanceof PlayerSkinSource.Name name) {
            profile = resolveName(minecraft, name.getValue());
        } else if (source instanceof PlayerSkinSource.Uuid uuid) {
            profile = resolveUuid(minecraft, uuid.getValue());
        } else {
            throw new IllegalArgumentException("Unsupported player skin source: " + source.getClass().getName());
        }
        return profile.thenApplyAsync(value -> Optional.of(resolveSkin(minecraft, value)), Util.ioPool());
    }

    /**
     * Snapshots the current player's selected skin on the client thread.
     *
     * @param minecraft borrowed active client.
     * @return detached normalized pixels.
     * @throws IllegalStateException when no current player exists or its selected texture is unavailable.
     */
    static DrawImage current(Minecraft minecraft) {
        var player = minecraft.player;
        if (player == null) {
            throw new IllegalStateException("A current Minecraft player is required to load its skin.");
        }
        return new ResourceSkin(player.getSkinTextureLocation()).snapshot(minecraft);
    }

    private static CompletableFuture<GameProfile> resolveName(Minecraft minecraft, String name) {
        var result = new CompletableFuture<GameProfile>();
        Util.ioPool().execute(() -> {
            var repository = new YggdrasilAuthenticationService(minecraft.getProxy()).createProfileRepository();
            repository.findProfilesByNames(new String[]{name}, Agent.MINECRAFT, new ProfileLookupCallback() {
                @Override
                public void onProfileLookupSucceeded(GameProfile profile) {
                    result.complete(profile);
                }

                @Override
                public void onProfileLookupFailed(GameProfile profile, Exception exception) {
                    result.completeExceptionally(exception);
                }
            });
        });
        return result.thenCompose(profile -> resolveUuid(minecraft, profile.getId()));
    }

    private static CompletableFuture<GameProfile> resolveUuid(Minecraft minecraft, UUID uuid) {
        return CompletableFuture.supplyAsync(
                () -> minecraft.getMinecraftSessionService().fillProfileProperties(new GameProfile(uuid, null), true),
                Util.ioPool());
    }

    private static FabricMinecraftSkinReference resolveSkin(Minecraft minecraft, GameProfile profile) {
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures;
        try {
            textures = minecraft.getMinecraftSessionService().getTextures(profile, true);
        } catch (InsecurePublicKeyException exception) {
            throw new CompletionException(exception);
        }
        var skin = textures.get(MinecraftProfileTexture.Type.SKIN);
        if (skin == null) {
            return new ResourceSkin(DefaultPlayerSkin.getDefaultSkin(UUIDUtil.getOrCreatePlayerUUID(profile)));
        }
        return new DetachedSkin(downloadSkin(minecraft, skin.getUrl()));
    }

    private static DrawImage downloadSkin(Minecraft minecraft, String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection(minecraft.getProxy());
            connection.setDoInput(true);
            connection.setDoOutput(false);
            connection.connect();
            int responseFamily = connection.getResponseCode() / 100;
            if (responseFamily != 2) {
                throw new IOException("Minecraft skin download returned HTTP " + connection.getResponseCode() + '.');
            }
            NativeImage decoded;
            try (var stream = connection.getInputStream()) {
                decoded = NativeImage.read(stream);
            }
            NativeImage normalized = normalizeLegacySkin(decoded);
            try {
                return snapshotNative(normalized);
            } finally {
                normalized.close();
            }
        } catch (IOException exception) {
            throw new CompletionException(exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static NativeImage normalizeLegacySkin(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width != 64 || (height != 32 && height != 64)) {
            image.close();
            throw new IllegalArgumentException("Minecraft player skins must decode to 64 by 32 or 64 by 64 pixels.");
        }
        boolean legacy = height == 32;
        NativeImage normalized = image;
        try {
            if (legacy) {
                var expanded = new NativeImage(64, 64, true);
                try {
                    expanded.copyFrom(normalized);
                } catch (RuntimeException | Error failure) {
                    expanded.close();
                    throw failure;
                }
                normalized.close();
                normalized = expanded;
                normalized.fillRect(0, 32, 64, 32, 0);
                normalized.copyRect(4, 16, 16, 32, 4, 4, true, false);
                normalized.copyRect(8, 16, 16, 32, 4, 4, true, false);
                normalized.copyRect(0, 20, 24, 32, 4, 12, true, false);
                normalized.copyRect(4, 20, 16, 32, 4, 12, true, false);
                normalized.copyRect(8, 20, 8, 32, 4, 12, true, false);
                normalized.copyRect(12, 20, 16, 32, 4, 12, true, false);
                normalized.copyRect(44, 16, -8, 32, 4, 4, true, false);
                normalized.copyRect(48, 16, -8, 32, 4, 4, true, false);
                normalized.copyRect(40, 20, 0, 32, 4, 12, true, false);
                normalized.copyRect(44, 20, -8, 32, 4, 12, true, false);
                normalized.copyRect(48, 20, -16, 32, 4, 12, true, false);
                normalized.copyRect(52, 20, -8, 32, 4, 12, true, false);
            }
            setNoAlpha(normalized, 0, 0, 32, 16);
            if (legacy) {
                applyLegacyHatTransparency(normalized, 32, 0, 64, 32);
            }
            setNoAlpha(normalized, 0, 16, 64, 32);
            setNoAlpha(normalized, 16, 48, 48, 64);
            return normalized;
        } catch (RuntimeException | Error failure) {
            normalized.close();
            throw failure;
        }
    }

    private static void applyLegacyHatTransparency(NativeImage image, int left, int top, int right, int bottom) {
        for (int x = left; x < right; x += 1) {
            for (int y = top; y < bottom; y += 1) {
                if (((image.getPixelRGBA(x, y) >> 24) & 255) < 128) {
                    return;
                }
            }
        }
        for (int x = left; x < right; x += 1) {
            for (int y = top; y < bottom; y += 1) {
                image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) & 0x00FFFFFF);
            }
        }
    }

    private static void setNoAlpha(NativeImage image, int left, int top, int right, int bottom) {
        for (int x = left; x < right; x += 1) {
            for (int y = top; y < bottom; y += 1) {
                image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) | 0xFF000000);
            }
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

    private record DetachedSkin(DrawImage image) implements FabricMinecraftSkinReference {
        @Override
        public DrawImage snapshot(Minecraft minecraft) {
            return image;
        }
    }

    private record ResourceSkin(ResourceLocation location) implements FabricMinecraftSkinReference {
        @Override
        public DrawImage snapshot(Minecraft minecraft) {
            var texture = minecraft.getTextureManager().getTexture(location);
            if (texture instanceof DynamicTexture dynamic) {
                NativeImage image = dynamic.getPixels();
                if (image == null) {
                    throw new IllegalStateException("The downloaded player skin has already been released.");
                }
                return snapshotNative(image);
            }
            return requireNormalized(FabricMinecraftAssets.loadMinecraftUiImage(
                    new ResourceId(location.getNamespace(), location.getPath())));
        }
    }
}
