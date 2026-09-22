package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.render.DrawCommand
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate

/**
 * Verifies native custom payloads on exact unobfuscated clients with an isolated integrated server.
 * This client-transport receipt is separate from acceptance against a real Paper distribution.
 */
internal object RemoteNativeGameTest {
    /**
     * Confirms input, a server handler, visible revision delivery, and terminal notification across native packets.
     */
    fun run(context: ClientGameTestContext) {
        context.worldBuilder().setUseConsistentSettings(true).create().use {
            context.waitFor(Predicate { minecraft -> minecraft.player != null && MinecraftClientScreenAccess.currentScreen(minecraft) == null })
            val server = context.computeOnClient(FailableFunction<Minecraft, MinecraftServer, RuntimeException> { checkNotNull(it.singleplayerServer) })
            context.input.resizeWindow(352, 240)
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    minecraft.options.guiScale().set(1)
                    minecraft.resizeGui()
                },
            )
            waitOnServer(context, server, RemoteNativeServerFixture::ready)
            context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { RemoteNativeCanvasFixture.open() })
            onServer(context, server) { RemoteNativeServerFixture.open(it) }
            context.waitForScreen(FabricMinecraftScreen::class.java)
            context.waitTicks(3)
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    val screen = requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen)
                    val field = MouseButtonEvent(5.0, 5.0, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))
                    screen.mouseClicked(field, false)
                    screen.mouseReleased(field)
                    "native-日本語".codePoints().forEach { check(screen.charTyped(CharacterEvent(it))) }
                    val button = MouseButtonEvent(5.0, 25.0, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))
                    check(screen.mouseClicked(button, false))
                    screen.mouseReleased(button)
                },
            )
            waitOnServer(context, server, RemoteNativeServerFixture::accepted)
            context.waitFor(
                Predicate { minecraft ->
                    val screen = requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen)
                    screen.captureCanvasFrame().filterIsInstance<DrawCommand.FillRectangle>().any { it.color == ArgbColor(-16776961) }
                },
            )
            val output = Path.of(requireNotNull(System.getProperty("strata.minecraftParityOutput")))
            Files.createDirectories(output)
            context.takeScreenshot(TestScreenshotOptions.of("native-remote").disableCounterPrefix().withDestinationDir(output))
            context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { RemoteNativeCanvasFixture.verifyRendering() })
            RemoteNativeCanvasFixture.verifyPixels(output.resolve("native-remote.png"))
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen).onClose()
                },
            )
            waitOnServer(context, server, RemoteNativeServerFixture::closed)
            context.waitFor(Predicate { RemoteNativeCanvasFixture.released() })
            context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { RemoteNativeCanvasFixture.close() })
            Files.writeString(output.resolve("native-remote.properties"), "runId=${UUID.randomUUID()}\nversion=${System.getProperty("strata.minecraftVersion")}\ntransport=native-custom-payload\ninput=confirmed\nupdate=visible\nclose=acknowledged\nserver=integrated\n")
        }
    }

    private fun waitOnServer(
        context: ClientGameTestContext,
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
        context: ClientGameTestContext,
        server: MinecraftServer,
        action: (ServerPlayer) -> T,
    ): T {
        val result = CompletableFuture<T>()
        server.execute {
            runCatching { action(server.playerList.players.single()) }.onSuccess(result::complete).onFailure(result::completeExceptionally)
        }
        // GameTest advances the integrated server only while its test thread yields through the context.
        context.waitFor(Predicate { result.isDone })
        return result.join()
    }
}
