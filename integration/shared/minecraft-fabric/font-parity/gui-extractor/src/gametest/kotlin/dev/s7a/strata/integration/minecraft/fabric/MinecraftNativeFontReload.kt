package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.PreparableReloadListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Runs the actual game's standard font reload with one preparation worker and normal client-thread application.
 * Resource bytes and provider implementations are unchanged; the game retains all provider ownership.
 * The returned future propagates native failures and releases the helper-owned executor on every completion path.
 */
internal object MinecraftNativeFontReload {
    /**
     * Starts on the render thread without blocking it; the loaded-test thread must await the returned future.
     */
    fun start(): CompletableFuture<Void> {
        RenderSystem.assertOnRenderThread()
        val minecraft = Minecraft.getInstance()
        val preparation = Executors.newSingleThreadExecutor { task -> Thread(task, "Strata native font preparation").apply { isDaemon = true } }
        val barrier =
            object : PreparableReloadListener.PreparationBarrier {
                override fun <T : Any> wait(prepared: T): CompletableFuture<T> = CompletableFuture.completedFuture(prepared)
            }
        return runCatching {
            val state = PreparableReloadListener.SharedState(minecraft.resourceManager)
            minecraft.fontManager.prepareSharedState(state)
            minecraft.fontManager
                .reload(state, preparation, barrier, minecraft)
                .whenComplete { _, _ -> preparation.shutdown() }
        }.getOrElse { failure ->
            preparation.shutdownNow()
            throw failure
        }
    }
}
