package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.world.item.Items
import org.apache.commons.lang3.function.FailableConsumer
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate

/**
 * Optional real Paper connection using native custom payloads, native input callbacks, and vanilla container packets.
 * The complete ordinary client suite still runs; the supplied server must be an isolated exact-version acceptance instance.
 * Synthetic confirmed input here does not claim operating-system composition acceptance.
 */
internal object PaperRemoteGameTest {
    /**
     * Connects only when the runner explicitly supplies a server address and writes a fresh completion receipt.
     */
    fun run(context: ClientGameTestContext) {
        val address = System.getProperty("strata.paper.address") ?: return
        val run = requireNotNull(System.getProperty("strata.paper.run"))
        context.input.resizeWindow(352, 240)
        onClient(context) { minecraft ->
            minecraft.options.guiScale().set(1)
            minecraft.resizeGui()
            ConnectScreen.startConnecting(TitleScreen(), minecraft, ServerAddress.parseString(address), ServerData("Strata acceptance", address, ServerData.Type.OTHER), false, null)
        }
        context.waitFor(Predicate { minecraft -> 176 <= minecraft.window.guiScaledWidth && 120 <= minecraft.window.guiScaledHeight })
        context.waitFor(Predicate { minecraft -> minecraft.player != null && MinecraftClientScreenAccess.currentScreen(minecraft) == null }, 1200)
        context.waitTicks(40)
        onClient(context) { checkNotNull(it.connection).sendCommand("strata-verify") }
        await(context, Stage.Controls)
        capture(context, "controls")
        click(context, 12.0, 12.0, consumesPress = false)
        onClient(context) { minecraft ->
            val screen = requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen)
            "remote-日本語".codePoints().forEach { check(screen.charTyped(CharacterEvent(it))) }
        }
        click(context, 20.0, 38.0)
        click(context, 12.0, 62.0)
        await(context, Stage.Inventory)
        capture(context, "inventory")
        context.waitFor(
            Predicate { minecraft ->
                minecraft.player
                    ?.inventory
                    ?.getItem(9)
                    ?.count == 7
            },
        )
        click(context, 16.0, 16.0)
        context.waitFor(
            Predicate { minecraft ->
                val menu = minecraft.player?.containerMenu ?: return@Predicate false
                menu.carried.item === Items.DIRT && menu.carried.count == 7
            },
        )
        context.waitTicks(5)
        click(context, 16.0, 16.0)
        await(context, Stage.Complete)
        val output = Path.of(requireNotNull(System.getProperty("strata.minecraftParityOutput")))
        Files.createDirectories(output)
        Files.writeString(output.resolve("paper-client.properties"), "runId=$run\nversion=${System.getProperty("strata.minecraftVersion")}\ncontrols=confirmed\ncustomExtension=confirmed\nslot=round-trip\n")
        onClient(context) { minecraft ->
            (MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen)?.onClose()
            minecraft.disconnectWithSavingScreen()
            MinecraftClientScreenAccess.setScreen(minecraft, TitleScreen())
        }
        context.waitTicks(5)
    }

    private fun await(
        context: ClientGameTestContext,
        stage: Stage,
    ) {
        context.waitFor(
            Predicate { minecraft ->
                val screen = MinecraftClientScreenAccess.currentScreen(minecraft)
                screen is FabricMinecraftScreen && Stage.decode(screen.title.string) == stage
            },
            600,
        )
        context.waitTicks(3)
        onClient(context) { minecraft ->
            val screen = MinecraftClientScreenAccess.currentScreen(minecraft)
            check(screen is FabricMinecraftScreen && Stage.decode(screen.title.string) == stage) { "The $stage screen failed during presentation." }
        }
    }

    private fun click(
        context: ClientGameTestContext,
        x: Double,
        y: Double,
        consumesPress: Boolean = true,
    ) {
        onClient(context) { minecraft ->
            val screen = requireNotNull(MinecraftClientScreenAccess.currentScreen(minecraft) as? FabricMinecraftScreen)
            val event = MouseButtonEvent(x, y, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))
            check(screen.mouseClicked(event, false) == consumesPress) { "Unexpected consumption at ($x, $y) in ${screen.title.string}." }
            screen.mouseReleased(event)
        }
        context.waitTicks(2)
    }

    private fun capture(
        context: ClientGameTestContext,
        stage: String,
    ) {
        val output = Path.of(requireNotNull(System.getProperty("strata.minecraftParityOutput")))
        context.takeScreenshot(TestScreenshotOptions.of("paper-$stage").disableCounterPrefix().withDestinationDir(output))
    }

    private fun onClient(
        context: ClientGameTestContext,
        action: (Minecraft) -> Unit,
    ) {
        context.runOnClient(FailableConsumer<Minecraft, RuntimeException>(action))
    }

    /**
     * Exact fixture captions are decoded at the native screen boundary before assertions inspect domain state.
     */
    private enum class Stage(
        private val caption: String,
    ) {
        Controls("Strata verification controls"),
        Inventory("Strata verification inventory"),
        Complete("Strata verification complete"),
        ;

        companion object {
            fun decode(caption: String): Stage? = entries.firstOrNull { it.caption == caption }
        }
    }
}
