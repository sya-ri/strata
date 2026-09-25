package dev.s7a.strata.examples.paper

import dev.s7a.strata.paper.PaperUi
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSessionStatus
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * Installable external consumer of the published Paper API; requires the Strata plugin and a negotiated Fabric client.
 * Each command creates independent server state owned by this plugin's screen session.
 */
public class PaperDemoPlugin : JavaPlugin() {
    override fun onEnable() {
        DemoRemoteExtensions.types.forEach { PaperUi.register(this, it) }
        requireNotNull(getCommand("strata-input-demo")) { "The input demo command must be registered in plugin.yml." }
            .setExecutor { sender, _, _, _ -> openScreen(sender, PaperInputScreens::editor) }
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean = openScreen(sender, PaperDemoScreens::counter)

    private fun openScreen(
        sender: CommandSender,
        definition: () -> UiDefinition,
    ): Boolean {
        val player = sender as? Player ?: return false
        if (PaperUi.capabilities(player) == null) {
            player.sendMessage("Install the matching Strata Fabric runtime and reconnect before opening this screen.")
            return true
        }
        val session = PaperUi.open(this, player, definition = definition)
        val status = PaperUi.execute(player) { session.status }
        if (status is UiSessionStatus.Closed) player.sendMessage("The screen could not open: ${status.reason}.")
        return true
    }
}
