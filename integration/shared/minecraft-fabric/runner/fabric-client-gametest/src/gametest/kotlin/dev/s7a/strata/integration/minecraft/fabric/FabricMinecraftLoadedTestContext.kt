@file:Suppress("UnstableApiUsage") // Fabric intentionally exposes its client GameTest helpers as experimental APIs.

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Path
import java.util.function.Predicate

/**
 * Adapts the existing acceptance suite to Fabric's controlled client/server ticks and owned test helpers.
 * The Fabric runner owns [delegate] and the calling GameTest thread; callers close every returned world.
 */
internal class FabricMinecraftLoadedTestContext(
    private val delegate: ClientGameTestContext,
) : MinecraftLoadedTestContext {
    override fun <T : Any> computeOnClient(action: (Minecraft) -> T): T = delegate.computeOnClient(FailableFunction<Minecraft, T, RuntimeException> { action(it) })

    override fun waitTicks(ticks: Int) {
        delegate.waitTicks(ticks)
    }

    override fun waitFor(
        timeoutTicks: Int,
        condition: (Minecraft) -> Boolean,
    ) {
        try {
            delegate.waitFor(Predicate(condition), timeoutTicks)
        } catch (failure: AssertionError) {
            throw IllegalStateException(failure.message, failure)
        }
    }

    override fun restoreDefaultGameOptions() {
        delegate.restoreDefaultGameOptions()
    }

    override fun resizeWindow(size: IntSize) {
        delegate.input.resizeWindow(size.width, size.height)
    }

    override fun movePointerRaw(
        x: Double,
        y: Double,
    ) {
        delegate.input.setCursorPos(x, y)
    }

    override fun movePointer(position: IntOffset) {
        val physical =
            computeOnClient { minecraft ->
                val window = minecraft.window
                position.x.toDouble() * window.screenWidth / window.guiScaledWidth to
                    position.y.toDouble() * window.screenHeight / window.guiScaledHeight
            }
        movePointerRaw(physical.first, physical.second)
    }

    override fun takeScreenshot(
        name: String,
        output: Path,
        size: IntSize,
    ): Path =
        delegate.takeScreenshot(
            TestScreenshotOptions
                .of(name)
                .disableCounterPrefix()
                .withDestinationDir(output)
                .withSize(size.width, size.height),
        )

    override fun createSingleplayerWorld(): MinecraftLoadedTestWorld {
        val world = delegate.worldBuilder().setUseConsistentSettings(true).create()
        val integratedServer = world.server.computeOnServer(FailableFunction<MinecraftServer, MinecraftServer, RuntimeException> { it })
        return object : MinecraftLoadedTestWorld {
            override val server: MinecraftServer = integratedServer

            override fun <T : Any> computeOnServer(action: (MinecraftServer) -> T): T = world.server.computeOnServer(FailableFunction<MinecraftServer, T, RuntimeException> { action(it) })

            override fun awaitReady() {
                world.awaitFabricChunks()
            }

            override fun close() {
                world.close()
            }
        }
    }
}
