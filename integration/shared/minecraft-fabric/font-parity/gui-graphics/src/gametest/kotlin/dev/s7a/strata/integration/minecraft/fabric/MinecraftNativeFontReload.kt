package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.PreparableReloadListener
import net.minecraft.util.profiling.InactiveProfiler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Runs the game's standard font reload with serial preparation and normal client-thread application.
 * The unchanged resource manager and providers remain game-owned; only the temporary executor belongs to this helper.
 * The returned future propagates native reload failures and terminates the executor on either completion path.
 */
internal object MinecraftNativeFontReload {
    /**
     * Starts on the render thread and returns without blocking it; callers must await completion from the test thread.
     */
    fun start(): CompletableFuture<Void> {
        RenderSystem.assertOnRenderThread()
        val minecraft = Minecraft.getInstance()
        val preparation = Executors.newSingleThreadExecutor { task -> Thread(task, "Strata native font preparation").apply { isDaemon = true } }
        val barrier =
            object : PreparableReloadListener.PreparationBarrier {
                override fun <T> wait(prepared: T): CompletableFuture<T> = CompletableFuture.completedFuture(prepared)
            }
        return runCatching {
            minecraft.fontManager
                .reload(barrier, minecraft.resourceManager, InactiveProfiler.INSTANCE, InactiveProfiler.INSTANCE, preparation, minecraft)
                .whenComplete { _, _ -> preparation.shutdown() }
        }.getOrElse { failure ->
            preparation.shutdownNow()
            throw failure
        }
    }
}
