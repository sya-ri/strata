package dev.s7a.strata.runtime.minecraft.fabric;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import dev.s7a.strata.component.PlayerSkinSource;
import dev.s7a.strata.render.DrawImage;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;

/**
 * Resolves and snapshots player skins through the profile-repository API used by Minecraft 1.20.4.
 */
final class FabricMinecraftSkinBridge {
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
            profile = resolveName(minecraft, name.getValue());
        } else if (source instanceof PlayerSkinSource.Uuid uuid) {
            profile = resolveUuid(minecraft, uuid.getValue());
        } else {
            throw new IllegalArgumentException("Unsupported player skin source: " + source.getClass().getName());
        }
        return profile
                .thenCompose(minecraft.getSkinManager()::getOrLoad)
                .thenApply(skin -> Optional.of(new FabricMinecraftResolvedSkin(skin)));
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
        return new FabricMinecraftResolvedSkin(player.getSkin()).snapshot(minecraft);
    }

    private static CompletableFuture<GameProfile> resolveName(Minecraft minecraft, String name) {
        var result = new CompletableFuture<GameProfile>();
        Util.ioPool().execute(() -> {
            var repository = new YggdrasilAuthenticationService(minecraft.getProxy()).createProfileRepository();
            repository.findProfilesByNames(new String[]{name}, new ProfileLookupCallback() {
                @Override
                public void onProfileLookupSucceeded(GameProfile profile) {
                    result.complete(profile);
                }

                @Override
                public void onProfileLookupFailed(String profileName, Exception exception) {
                    result.completeExceptionally(exception);
                }
            });
        });
        return result.thenCompose(profile -> resolveUuid(minecraft, profile.getId()));
    }

    private static CompletableFuture<GameProfile> resolveUuid(Minecraft minecraft, UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            ProfileResult result = minecraft.getMinecraftSessionService().fetchProfile(uuid, true);
            if (result == null) {
                throw new IllegalArgumentException("Minecraft did not resolve player profile " + uuid + '.');
            }
            return result.profile();
        }, Util.ioPool());
    }

}
