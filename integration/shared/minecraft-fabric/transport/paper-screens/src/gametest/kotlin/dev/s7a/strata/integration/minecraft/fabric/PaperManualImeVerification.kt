package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.diagnostics.UiRenderNodeId
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import java.lang.Boolean.getBoolean
import java.nio.file.Files
import java.nio.file.Path

/**
 * Normal-client Paper fixture that leaves all keyboard and composition input to the operating system.
 * A current invocation receipt requires server confirmation and retained client nodes across remote revisions.
 */
@OptIn(InternalStrataRuntimeApi::class)
public class PaperManualImeVerification : ClientModInitializer {
    private var stage = Stage.Title
    private var ticks = 0
    private var connectedTicks = 0
    private var monitor: UiRenderMonitor? = null
    private var nodes: List<UiRenderNodeId> = emptyList()
    private var revisions = 0L

    override fun onInitializeClient() {
        if (getBoolean("strata.paper.ime.manual").not()) return
        check(System.getProperty("fabric.client.gametest") == null) { "Manual OS input cannot run under GameTest." }
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
    }

    private fun tick(minecraft: Minecraft) {
        check(ticks++ < 18_000) { "Timed out awaiting manual remote OS IME verification." }
        val screen = MinecraftClientScreenAccess.currentScreen(minecraft)
        when (stage) {
            Stage.Title -> {
                if (screen is TitleScreen && MinecraftClientScreenAccess.hasOverlay(minecraft).not()) {
                    val address = requireNotNull(System.getProperty("strata.paper.address"))
                    minecraft.options.guiScale().set(2)
                    minecraft.resizeGui()
                    stage = Stage.Connecting
                    ConnectScreen.startConnecting(TitleScreen(), minecraft, ServerAddress.parseString(address), ServerData("Strata manual IME", address, ServerData.Type.OTHER), false, null)
                }
            }

            Stage.Connecting -> {
                if (minecraft.player != null && screen == null && 40 <= connectedTicks++) {
                    checkNotNull(minecraft.connection).sendCommand("strata-verify-ime")
                    stage = Stage.Editing
                }
            }

            Stage.Editing -> {
                if (screen is FabricMinecraftScreen) {
                    when (Caption.decode(screen.title.string)) {
                        Caption.Editor -> {
                            observe(screen)
                        }

                        Caption.Complete -> {
                            finish(minecraft, screen)
                        }

                        null -> {}
                    }
                }
            }

            Stage.Closed -> {
            }
        }
    }

    private fun observe(screen: FabricMinecraftScreen) {
        val collector = monitor ?: screen.startRenderMonitoring().also { monitor = it }
        val snapshot = collector.snapshot()
        val current = snapshot.nodes.filter { it.retired.not() }.map { it.id }
        if (current.isEmpty()) return
        if (nodes.isEmpty()) nodes = current
        check(current == nodes) { "A remote revision replaced retained editor nodes." }
        check(snapshot.overflowed.not())
        revisions = snapshot.counts.getValue(UiRenderMetric.RootEvaluation)
    }

    private fun finish(
        minecraft: Minecraft,
        screen: FabricMinecraftScreen,
    ) {
        check(nodes.isNotEmpty() && 1L < revisions)
        val output = Path.of(requireNotNull(System.getProperty("strata.paper.ime.output")))
        Files.createDirectories(output)
        val run = requireNotNull(System.getProperty("strata.paper.run"))
        Files.writeString(output.resolve("manual-client.properties"), "runId=$run\ninput=OS-keyboard-only\neditorIdentity=retained\nupdates=$revisions\n")
        stage = Stage.Closed
        monitor?.close()
        monitor = null
        nodes = emptyList()
        screen.onClose()
        minecraft.stop()
    }

    private enum class Stage { Title, Connecting, Editing, Closed }

    private enum class Caption(
        private val text: String,
    ) {
        Editor("Strata remote OS IME verification"),
        Complete("Strata remote OS IME complete"),
        ;

        companion object {
            fun decode(text: String): Caption? = entries.firstOrNull { it.text.contentEquals(text) }
        }
    }
}
