package dev.s7a.strata.runtime.minecraft.fabric.mixin

import dev.s7a.strata.runtime.minecraft.fabric.closeFabricMinecraftProfile
import dev.s7a.strata.runtime.minecraft.fabric.invalidateFabricMinecraftProfile
import net.minecraft.server.packs.resources.ReloadInstance
import net.minecraft.server.packs.resources.ReloadableResourceManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Invalidates the normal screen-opening profile before native resource replacement and terminal cleanup.
 *
 * The exact native method descriptors are shared by every supported release.
 * Reload closes old packs and constructs its new view before any reload listener can prepare; a failure there must still invalidate the cache.
 * Native close does not notify reload listeners, so it also needs an explicit hook.
 * The callbacks never cancel native work and ignore unrelated integrated-server resource managers.
 */
@Mixin(ReloadableResourceManager::class)
internal abstract class FabricMinecraftResourceReloadMixin private constructor() {
    // Why: Mixin requires a callback argument even when the observer never cancels native work.
    @Suppress("UnusedParameter")
    @Inject(
        method = ["createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;"],
        at = [At("HEAD")],
    )
    private fun beforeResourceReload(callback: CallbackInfoReturnable<ReloadInstance>) {
        invalidateFabricMinecraftProfile(this)
    }

    @Suppress("UnusedParameter")
    @Inject(method = ["close()V"], at = [At("HEAD")])
    private fun beforeResourceClose(callback: CallbackInfo) {
        closeFabricMinecraftProfile(this)
    }
}
