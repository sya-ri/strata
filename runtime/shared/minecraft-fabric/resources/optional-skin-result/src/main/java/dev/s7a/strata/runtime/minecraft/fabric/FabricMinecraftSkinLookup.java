package dev.s7a.strata.runtime.minecraft.fabric;

import com.mojang.authlib.GameProfile;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.SkinManager;

/** Adapts the compiled skin manager's result to an optional asynchronous skin reference. */
final class FabricMinecraftSkinLookup {
    private FabricMinecraftSkinLookup() {}

    /** Maps the native asynchronous result through the supplied skin-reference factory. */
    static CompletableFuture<Optional<FabricMinecraftSkinReference>> load(SkinManager skinManager, GameProfile profile, Function<PlayerSkin, FabricMinecraftSkinReference> reference) {
        return skinManager.getOrLoad(profile).thenApply(resolved -> resolved.map(reference));
    }
}
