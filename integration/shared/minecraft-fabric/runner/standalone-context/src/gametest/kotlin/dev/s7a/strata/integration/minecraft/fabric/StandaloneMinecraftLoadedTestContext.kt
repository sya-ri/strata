package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.level.levelgen.presets.WorldPresets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates loaded-client verification through native client-thread, tick, image, and world boundaries.
 *
 * The context owns one tick signal registration for the process lifetime and schedules all Minecraft access on the client thread. Calls originate from the standalone test thread and fail when the client stops responding.
 */
internal class StandaloneMinecraftLoadedTestContext : MinecraftLoadedTestContext {
    private val tickSignal = Semaphore(0)
    private val initialOptions by lazy {
        computeOnClient { minecraft ->
            minecraft.options.save()
            Files.readAllBytes(minecraft.gameDirectory.toPath().resolve("options.txt"))
        }
    }

    init {
        ClientTickEvents.END_CLIENT_TICK.register { tickSignal.release() }
    }

    override fun <T : Any> computeOnClient(action: (Minecraft) -> T): T {
        val result = CompletableFuture<T>()
        val minecraft = Minecraft.getInstance()
        if (minecraft.isSameThread) return action(minecraft)
        minecraft.execute {
            runCatching { action(minecraft) }
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally)
        }
        return try {
            result.get(CLIENT_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        }
    }

    override fun restoreDefaultGameOptions() {
        val options = initialOptions
        computeOnClient { minecraft ->
            Files.write(minecraft.gameDirectory.toPath().resolve("options.txt"), options)
            minecraft.options.load()
        }
    }

    override fun resizeWindow(size: IntSize) {
        computeOnClient { minecraft -> minecraft.window.setWindowed(size.width, size.height) }
    }

    override fun movePointerRaw(
        x: Double,
        y: Double,
    ) {
        computeOnClient { minecraft -> moveStandaloneMinecraftPointer(minecraft, x, y) }
    }

    override fun waitTicks(ticks: Int) {
        require(0 <= ticks) { "ticks cannot be negative" }
        tickSignal.drainPermits()
        repeat(ticks) {
            check(tickSignal.tryAcquire(TICK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for a loaded Minecraft client tick."
            }
        }
    }

    override fun waitFor(
        timeoutTicks: Int,
        condition: (Minecraft) -> Boolean,
    ) {
        require(0 < timeoutTicks) { "timeoutTicks must be positive" }
        repeat(timeoutTicks) {
            if (computeOnClient(condition)) return
            waitTicks(1)
        }
        check(computeOnClient(condition)) { "Timed out waiting for a loaded Minecraft client condition." }
    }

    override fun movePointer(position: IntOffset) {
        val physical =
            computeOnClient { minecraft ->
                val window = minecraft.window
                val rawX = position.x.toDouble() * window.screenWidth / window.guiScaledWidth
                val rawY = position.y.toDouble() * window.screenHeight / window.guiScaledHeight
                rawX to rawY
            }
        movePointerRaw(physical.first, physical.second)
    }

    override fun takeScreenshot(
        name: String,
        output: Path,
        size: IntSize,
    ): Path = captureStandaloneMinecraftScreenshot(this, name, output, size)

    override fun createSingleplayerWorld(): MinecraftLoadedTestWorld {
        computeOnClient { minecraft -> MinecraftStandaloneWorldAccess.open(minecraft) }
        waitFor(WORLD_TIMEOUT_TICKS) { minecraft -> MinecraftClientScreenAccess.currentScreen(minecraft) is CreateWorldScreen }
        waitTicks(2)

        val worldId =
            computeOnClient { minecraft ->
                val screen = MinecraftClientScreenAccess.currentScreen(minecraft) as? CreateWorldScreen ?: error("The create-world screen is not active.")
                screen.uiState.name = WORLD_NAME
                screen.uiState.worldType = screen.uiState.normalPresetList.single { it.preset()?.`is`(WorldPresets.FLAT) == true }
                screen.uiState.seed = "1"
                screen.uiState.isGenerateStructures = false
                MinecraftStandaloneWorldAccess.configure(screen)
                val targetFolder = screen.uiState.targetFolder
                pressButton(screen, "selectWorld.create")
                targetFolder
            }
        waitFor(WORLD_TIMEOUT_TICKS) { minecraft ->
            minecraft.level != null && minecraft.player != null && minecraft.singleplayerServer != null
        }
        val integratedServer = computeOnClient { minecraft -> checkNotNull(minecraft.singleplayerServer) }
        val world = StandaloneMinecraftLoadedTestWorld(this, integratedServer, worldId)
        val ready = runCatching { world.awaitReady() }
        if (ready.isFailure) runCanvasTestCleanup(ready.exceptionOrNull(), world::close)
        ready.getOrThrow()
        return world
    }

    private fun pressButton(
        screen: CreateWorldScreen,
        translationKey: String,
    ) {
        val expected = Component.translatable(translationKey).string
        val match = AtomicReference<AbstractButton?>()
        screen.children().forEach { child ->
            (child as? AbstractWidget)?.visitWidgets { widget ->
                if (match.get() == null && widget is AbstractButton && widget.message.string == expected) {
                    match.set(widget)
                }
            }
        }
        MinecraftStandaloneWorldAccess.press(checkNotNull(match.get()) { "Could not find the create-world button." })
    }

    private companion object {
        private const val CLIENT_TASK_TIMEOUT_SECONDS = 120L
        private const val TICK_TIMEOUT_SECONDS = 30L
        private const val WORLD_TIMEOUT_TICKS = 1_200
        private const val WORLD_NAME = "New World"
    }
}
