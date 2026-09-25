package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.render.DrawCommand
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Executes the installed native transport in an exact integrated client/server pair, independently of Paper availability.
 * Client callbacks and server assertions run on their respective owner threads through the loaded test context.
 */
internal object RemoteNativeGameTest {
    /**
     * Verifies a negotiated screen, confirmed input, a business action, a visible update, and peer-driven cleanup.
     */
    fun run(context: MinecraftLoadedTestContext) {
        val pauseOnLostFocus =
            context.computeOnClient { minecraft ->
                minecraft.options.pauseOnLostFocus.also { minecraft.options.pauseOnLostFocus = false }
            }
        try {
            context.createSingleplayerWorld().use { world ->
                world.awaitReady()
                context.waitFor { it.screen == null }
                context.configureVerificationViewport(IntSize(352, 240), 1)
                waitOnServer(context, world, RemoteNativeServerFixture::ready)
                context.computeOnClient { RemoteNativeCanvasFixture.open() }
                world.computeOnServer { RemoteNativeServerFixture.open(it.playerList.players.single()) }
                context.waitFor { it.screen is FabricMinecraftScreen }
                context.waitTicks(3)
                context.computeOnClient { minecraft ->
                    val screen = requireNotNull(minecraft.screen as? FabricMinecraftScreen)
                    pressMinecraftScreen(screen, IntOffset(5, 5))
                    releaseMinecraftScreen(screen, IntOffset(5, 5))
                    "native-日本語".codePoints().forEach { check(typePaperTestCharacter(screen, it)) }
                    check(pressMinecraftScreen(screen, IntOffset(5, 25)))
                    releaseMinecraftScreen(screen, IntOffset(5, 25))
                }
                waitOnServer(context, world, RemoteNativeServerFixture::accepted)
                context.waitFor { minecraft ->
                    val screen = requireNotNull(minecraft.screen as? FabricMinecraftScreen)
                    screen.captureCanvasFrame().filterIsInstance<DrawCommand.FillRectangle>().any { it.color == ArgbColor(-16776961) }
                }
                val output = Path.of(requireNotNull(System.getProperty("strata.minecraftLegacyOutput")))
                Files.createDirectories(output)
                context.takeScreenshot("native-remote", output, IntSize(352, 240))
                context.computeOnClient { RemoteNativeCanvasFixture.verifyRendering() }
                RemoteNativeCanvasFixture.verifyPixels(output.resolve("native-remote.png"))
                context.computeOnClient { (it.screen as? FabricMinecraftScreen)?.onClose() ?: error("The remote screen disappeared.") }
                waitOnServer(context, world, RemoteNativeServerFixture::closed)
                context.waitFor { RemoteNativeCanvasFixture.released() }
                context.computeOnClient { RemoteNativeCanvasFixture.close() }
                Files.writeString(output.resolve("native-remote.properties"), "runId=${UUID.randomUUID()}\nversion=${System.getProperty("strata.minecraftVersion")}\ntransport=native-custom-payload\ninput=confirmed\nupdate=visible\nclose=acknowledged\nserver=integrated\n")
            }
        } finally {
            context.computeOnClient { it.options.pauseOnLostFocus = pauseOnLostFocus }
        }
    }

    private fun waitOnServer(
        context: MinecraftLoadedTestContext,
        world: MinecraftLoadedTestWorld,
        condition: (ServerPlayer) -> Boolean,
    ) {
        repeat(200) {
            if (world.computeOnServer { server -> condition(server.playerList.players.single()) }) return
            context.waitTicks(1)
        }
        error("The integrated remote peer did not reach the expected state.")
    }
}
