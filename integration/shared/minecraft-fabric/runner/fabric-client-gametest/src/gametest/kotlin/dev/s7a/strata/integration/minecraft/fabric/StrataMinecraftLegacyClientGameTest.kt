package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Path
import java.util.function.Predicate

/**
 * Runs Strata's shared loaded-client assertions through Fabric Client GameTest on releases that provide it.
 *
 * Fabric owns the supplied context and every world created through it. This entrypoint closes each owned world before returning and propagates assertion and runner failures.
 */
public class StrataMinecraftLegacyClientGameTest : FabricClientGameTest {
    /**
     * Verifies the configured legacy release in a loaded client.
     *
     * @param context Fabric-owned client, tick, screenshot, and integrated-world coordinator.
     * @throws Throwable when Minecraft, Fabric, or a Strata acceptance assertion fails.
     */
    override fun runTest(context: ClientGameTestContext) {
        context.restoreDefaultGameOptions()
        FabricContext(context).runLegacySuite()
        context.restoreDefaultGameOptions()
    }

    private class FabricContext(
        private val delegate: ClientGameTestContext,
    ) : MinecraftLoadedTestContext,
        ClientGameTestContext by delegate {
        override fun <T : Any> computeOnClient(action: (Minecraft) -> T): T =
            delegate.computeOnClient(
                FailableFunction<Minecraft, T, RuntimeException> { minecraft -> action(minecraft) },
            )

        override fun waitTicks(ticks: Int) {
            delegate.waitTicks(ticks)
        }

        override fun waitFor(
            timeoutTicks: Int,
            condition: (Minecraft) -> Boolean,
        ) {
            delegate.waitFor(Predicate(condition), timeoutTicks)
        }

        override fun movePointer(position: IntOffset) {
            delegate.input.setCursorPos(position.x.toDouble(), position.y.toDouble())
        }

        override fun takeScreenshot(
            name: String,
            output: Path,
            size: IntSize,
        ): Path {
            delegate.takeScreenshot(
                TestScreenshotOptions
                    .of(name)
                    .disableCounterPrefix()
                    .withSize(size.width, size.height)
                    .withDestinationDir(output),
            )
            return output.resolve("$name.png")
        }

        override fun createSingleplayerWorld(): MinecraftLoadedTestWorld {
            val world = delegate.worldBuilder().setUseConsistentSettings(true).create()
            val integratedServer = computeOnClient { minecraft -> checkNotNull(minecraft.singleplayerServer) }
            return object : MinecraftLoadedTestWorld {
                override val server = integratedServer

                override fun <T : Any> computeOnServer(action: (MinecraftServer) -> T): T =
                    world.server.computeOnServer(
                        FailableFunction<MinecraftServer, T, RuntimeException> { server -> action(server) },
                    )

                override fun awaitReady() {
                    world.clientWorld.waitForChunksRender()
                }

                override fun close() {
                    world.close()
                }
            }
        }
    }
}
