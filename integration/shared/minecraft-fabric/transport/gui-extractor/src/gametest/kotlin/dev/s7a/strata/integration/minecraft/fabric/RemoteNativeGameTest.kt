package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.render.DrawCommand
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Verifies native custom payloads on exact unobfuscated clients with an isolated integrated server.
 * This client-transport receipt is separate from acceptance against a real Paper distribution.
 */
internal object RemoteNativeGameTest {
    /**
     * Confirms input, a server handler, visible revision delivery, and terminal notification across native packets.
     */
    fun run(context: MinecraftLoadedTestContext) {
        System.getProperty("strata.fabric.address")?.let { address ->
            dedicated(context, address)
            return
        }
        context.createSingleplayerWorld().use {
            context.waitFor { minecraft -> minecraft.player != null && MinecraftClientScreenAccess.currentScreen(minecraft) == null }
            val server = context.computeOnClient { checkNotNull(it.singleplayerServer) }
            resizeMinecraftTestWindow(context, IntSize(352, 240))
            context.runOnClient { minecraft ->
                minecraft.options.guiScale().set(1)
                minecraft.resizeGui()
            }
            waitOnServer(context, server, RemoteNativeServerFixture::ready)
            context.runOnClient { RemoteNativeCanvasFixture.open() }
            onServer(context, server) { RemoteNativeServerFixture.open(it) }
            context.waitForScreen(FabricMinecraftScreen::class.java)
            context.waitTicks(3)
            context.runOnClient { minecraft ->
                val screen = requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen)
                val field = MouseButtonEvent(5.0, 5.0, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))
                screen.mouseClicked(field, false)
                screen.mouseReleased(field)
                "native-日本語".codePoints().forEach { check(screen.charTyped(CharacterEvent(it))) }
                val button = MouseButtonEvent(5.0, 25.0, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))
                check(screen.mouseClicked(button, false))
                screen.mouseReleased(button)
            }
            waitOnServer(context, server, RemoteNativeServerFixture::accepted)
            context.waitFor { minecraft ->
                val screen = requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen)
                screen.captureCanvasFrame().filterIsInstance<DrawCommand.FillRectangle>().any { it.color == ArgbColor(-16776961) }
            }
            val output = Path.of(requireNotNull(System.getProperty("strata.minecraftParityOutput")))
            Files.createDirectories(output)
            context.takeScreenshot("native-remote", output)
            context.runOnClient { RemoteNativeCanvasFixture.verifyRendering() }
            RemoteNativeCanvasFixture.verifyPixels(output.resolve("native-remote.png"))
            context.runOnClient { minecraft ->
                requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen).onClose()
            }
            waitOnServer(context, server, RemoteNativeServerFixture::closed)
            context.waitFor { RemoteNativeCanvasFixture.released() }
            context.runOnClient { RemoteNativeCanvasFixture.close() }
            Files.writeString(output.resolve("native-remote.properties"), "runId=${UUID.randomUUID()}\nversion=${System.getProperty("strata.minecraftVersion")}\ntransport=fabric-api\ninput=confirmed\nupdate=visible\nclose=acknowledged\nserver=integrated\napi=Strata\n")
        }
    }

    /**
     * Reconnects twice to a production dedicated server and verifies independent native presentation lifetimes.
     */
    private fun dedicated(
        context: MinecraftLoadedTestContext,
        address: String,
    ) {
        val run = requireNotNull(System.getProperty("strata.fabric.run"))
        resizeMinecraftTestWindow(context, IntSize(352, 240))
        repeat(2) {
            context.runOnClient { minecraft ->
                minecraft.options.guiScale().set(1)
                minecraft.resizeGui()
                RemoteNativeCanvasFixture.open()
                ConnectScreen.startConnecting(TitleScreen(), minecraft, ServerAddress.parseString(address), ServerData("Strata Fabric acceptance", address, ServerData.Type.OTHER), false, null)
            }
            context.waitFor(timeoutTicks = 1200) { MinecraftClientScreenAccess.currentScreen(it) is FabricMinecraftScreen }
            context.waitTicks(3)
            context.runOnClient { minecraft ->
                val screen = requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen)
                val field = MouseButtonEvent(5.0, 5.0, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))
                screen.mouseClicked(field, false)
                screen.mouseReleased(field)
                "native-日本語".codePoints().forEach { check(screen.charTyped(CharacterEvent(it))) }
                val button = MouseButtonEvent(5.0, 25.0, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))
                check(screen.mouseClicked(button, false))
                screen.mouseReleased(button)
            }
            context.waitFor { minecraft ->
                val screen = requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen)
                screen.captureCanvasFrame().filterIsInstance<DrawCommand.FillRectangle>().any { it.color == ArgbColor(-16776961) }
            }
            context.runOnClient { minecraft ->
                RemoteNativeCanvasFixture.verifyRendering()
                requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen).onClose()
            }
            context.waitFor { RemoteNativeCanvasFixture.released() }
            context.waitTicks(5)
            context.runOnClient { minecraft ->
                RemoteNativeCanvasFixture.close()
                minecraft.disconnectWithSavingScreen()
                MinecraftClientScreenAccess.setScreen(minecraft, TitleScreen())
            }
            context.waitTicks(5)
        }
        val output = Path.of(requireNotNull(System.getProperty("strata.minecraftParityOutput")))
        Files.createDirectories(output)
        Files.writeString(output.resolve("fabric-client.properties"), "runId=$run\nserver=dedicated\ninput=confirmed\nupdate=visible\nreconnect=confirmed\n")
    }

    private fun waitOnServer(
        context: MinecraftLoadedTestContext,
        server: MinecraftServer,
        condition: (ServerPlayer) -> Boolean,
    ) {
        repeat(200) {
            if (onServer(context, server, condition)) return
            context.waitTicks(1)
        }
        error("The integrated remote peer did not reach the expected state.")
    }

    private fun <T> onServer(
        context: MinecraftLoadedTestContext,
        server: MinecraftServer,
        action: (ServerPlayer) -> T,
    ): T {
        val result = CompletableFuture<T>()
        server.execute {
            runCatching { action(server.playerList.players.single()) }.onSuccess(result::complete).onFailure(result::completeExceptionally)
        }
        // GameTest advances the integrated server only while its test thread yields through the context.
        context.waitFor { result.isDone }
        return result.join()
    }
}
