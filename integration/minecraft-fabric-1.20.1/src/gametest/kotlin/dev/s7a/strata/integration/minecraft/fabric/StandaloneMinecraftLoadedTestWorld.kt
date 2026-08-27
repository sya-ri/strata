package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.gui.screens.GenericDirtMessageScreen
import net.minecraft.client.gui.screens.ReceivingLevelScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Owns one disposable Minecraft 1.20 or 1.20.1 singleplayer world created by [StandaloneMinecraftLoadedTestContext].
 *
 * The handle schedules server work on the integrated-server thread and removes its save after a clean disconnect. It must be closed from the standalone test thread.
 *
 * @param context loaded-client coordinator that created this world.
 * @param server integrated server borrowed until [close].
 * @param worldId storage identifier owned by this handle.
 */
internal class StandaloneMinecraftLoadedTestWorld(
    private val context: MinecraftLoadedTestContext,
    override val server: MinecraftServer,
    private val worldId: String,
) : MinecraftLoadedTestWorld {
    override fun <T : Any> computeOnServer(action: (MinecraftServer) -> T): T {
        val result = CompletableFuture<T>()
        server.execute {
            runCatching { action(server) }
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally)
        }
        return result.get(SERVER_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun awaitReady() {
        context.waitFor(WORLD_TIMEOUT_TICKS) { minecraft ->
            minecraft.level != null && minecraft.player != null && (minecraft.screen is ReceivingLevelScreen).not()
        }
        context.waitTicks(2)
    }

    override fun close() {
        context.computeOnClient { minecraft ->
            minecraft.level?.disconnect()
            minecraft.clearLevel(GenericDirtMessageScreen(Component.translatable("menu.savingLevel")))
        }
        context.waitFor(WORLD_TIMEOUT_TICKS) { minecraft -> minecraft.level == null && server.isRunning.not() }
        context.computeOnClient { minecraft -> minecraft.setScreen(TitleScreen()) }
        val storageAccess = context.computeOnClient { minecraft -> minecraft.levelSource.createAccess(worldId) }
        storageAccess.use { access -> access.deleteLevel() }
    }

    private companion object {
        private const val SERVER_TASK_TIMEOUT_SECONDS = 30L
        private const val WORLD_TIMEOUT_TICKS = 1_200
    }
}
