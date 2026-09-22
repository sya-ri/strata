package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.world.item.Items
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exact-version Paper acceptance through native transport, input, and vanilla inventory packets.
 * The runner owns the loopback server; this fixture owns its connection and restores the title screen.
 * Confirmed-character injection does not establish operating-system composition acceptance.
 */
internal object PaperRemoteGameTest {
    /**
     * Runs only when the isolated Paper runner supplies a server address and invocation ID.
     */
    fun run(context: MinecraftLoadedTestContext) {
        val address = System.getProperty("strata.paper.address") ?: return
        val run = requireNotNull(System.getProperty("strata.paper.run"))
        val pauseOnLostFocus =
            context.computeOnClient { minecraft ->
                minecraft.options.pauseOnLostFocus.also { minecraft.options.pauseOnLostFocus = false }
            }
        try {
            verify(context, address, run)
        } finally {
            context.computeOnClient { it.options.pauseOnLostFocus = pauseOnLostFocus }
        }
    }

    private fun verify(
        context: MinecraftLoadedTestContext,
        address: String,
        run: String,
    ) {
        context.configureVerificationViewport(IntSize(352, 240), 1)
        context.computeOnClient { connectPaperTest(it, address) }
        awaitConnection(context)
        context.waitTicks(40)
        verifyPaper(context, run)
        System.getProperty("strata.velocity.run")?.let { verifyProxy(context, it) }
        context.computeOnClient { minecraft ->
            (minecraft.screen as? FabricMinecraftScreen)?.onClose()
            disconnectPaperTest(minecraft)
            minecraft.setScreen(TitleScreen())
        }
        context.waitTicks(5)
    }

    private fun verifyPaper(
        context: MinecraftLoadedTestContext,
        run: String,
    ) {
        context.computeOnClient { checkNotNull(it.connection).sendCommand("strata-verify") }
        await(context, Stage.Controls)
        capture(context, "controls")
        click(context, IntOffset(12, 12), consumesPress = false)
        context.computeOnClient { minecraft ->
            val screen = requireNotNull(minecraft.screen as? FabricMinecraftScreen)
            "remote-日本語".codePoints().forEach { check(typePaperTestCharacter(screen, it)) }
        }
        click(context, IntOffset(20, 38))
        click(context, IntOffset(12, 62))
        await(context, Stage.Inventory)
        capture(context, "inventory")
        context.waitFor {
            it.player
                ?.inventory
                ?.getItem(9)
                ?.count == 7
        }
        click(context, IntOffset(16, 16))
        context.waitFor { minecraft ->
            val menu = minecraft.player?.containerMenu ?: return@waitFor false
            menu.carried.item === Items.DIRT && menu.carried.count == 7
        }
        context.waitTicks(5)
        click(context, IntOffset(16, 16))
        await(context, Stage.Complete)
        val output = output()
        Files.createDirectories(output)
        Files.writeString(output.resolve("paper-client.properties"), "runId=$run\nversion=${System.getProperty("strata.minecraftVersion")}\ncontrols=confirmed\ncustomExtension=confirmed\nslot=round-trip\n")
    }

    private fun verifyProxy(
        context: MinecraftLoadedTestContext,
        run: String,
    ) {
        context.computeOnClient { checkNotNull(it.connection).sendCommand("strata-proxy-verify") }
        await(context, Stage.ProxyControls)
        typeProxy(context, "proxy-日本語")
        context.waitFor(1200) { it.player != null && it.screen == null }
        context.waitTicks(40)
        context.computeOnClient { checkNotNull(it.connection).sendCommand("strata-proxy-resume") }
        await(context, Stage.ProxyResumed)
        typeProxy(context, "resumed-日本語")
        await(context, Stage.ProxyComplete)
        verifyPaper(context, requireNotNull(System.getProperty("strata.paper.run")))
        val output = output()
        Files.writeString(output.resolve("velocity-client.properties"), "runId=$run\ntext=confirmed\nbackendSwitch=confirmed\npaperAfterSwitch=confirmed\n")
    }

    private fun typeProxy(
        context: MinecraftLoadedTestContext,
        value: String,
    ) {
        click(context, IntOffset(12, 12), consumesPress = false)
        context.computeOnClient { minecraft ->
            val screen = requireNotNull(minecraft.screen as? FabricMinecraftScreen)
            value.codePoints().forEach { check(typePaperTestCharacter(screen, it)) }
        }
        click(context, IntOffset(20, 38))
    }

    private fun awaitConnection(context: MinecraftLoadedTestContext) {
        try {
            context.waitFor(1200) { it.player != null && it.screen == null }
        } catch (failure: IllegalStateException) {
            val state = context.computeOnClient { "playerPresent=${it.player != null}, screen=${it.screen?.javaClass?.simpleName}" }
            throw IllegalStateException("Paper connection did not enter gameplay: $state", failure)
        }
    }

    private fun await(
        context: MinecraftLoadedTestContext,
        stage: Stage,
    ) {
        context.waitFor(600) { minecraft -> matches(minecraft, stage) }
        context.waitTicks(3)
        context.computeOnClient { check(matches(it, stage)) { "The $stage screen failed during presentation." } }
    }

    private fun matches(
        minecraft: Minecraft,
        stage: Stage,
    ): Boolean {
        val screen = minecraft.screen
        return screen is FabricMinecraftScreen && Stage.decode(screen.title.string) == stage
    }

    private fun click(
        context: MinecraftLoadedTestContext,
        position: IntOffset,
        consumesPress: Boolean = true,
    ) {
        context.computeOnClient { minecraft ->
            val screen = requireNotNull(minecraft.screen as? FabricMinecraftScreen)
            check(pressMinecraftScreen(screen, position) == consumesPress) { "Unexpected consumption at $position in ${screen.title.string}." }
            releaseMinecraftScreen(screen, position)
        }
        context.waitTicks(2)
    }

    private fun capture(
        context: MinecraftLoadedTestContext,
        stage: String,
    ) {
        val output = output()
        Files.createDirectories(output)
        context.takeScreenshot("paper-$stage", output, IntSize(352, 240))
    }

    private fun output(): Path = Path.of(requireNotNull(System.getProperty("strata.minecraftLegacyOutput")))

    /**
     * Decodes fixture captions at the native screen boundary before assertions inspect domain state.
     */
    private enum class Stage(
        private val caption: String,
    ) {
        Controls("Strata verification controls"),
        Inventory("Strata verification inventory"),
        Complete("Strata verification complete"),
        ProxyControls("Strata proxy controls"),
        ProxyResumed("Strata proxy resumed"),
        ProxyComplete("Strata proxy complete"),
        ;

        companion object {
            fun decode(caption: String): Stage? = entries.firstOrNull { it.caption == caption }
        }
    }
}
