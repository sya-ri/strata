package dev.s7a.strata.integration.paper

import dev.s7a.strata.runtime.paper.PaperScreens
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Explicitly installed test-only plugin; never runs a scene until the connected client invokes its command.
 * Fixtures run and release on the primary thread and write invocation-bound evidence only after server assertions pass.
 */
public class PaperAcceptancePlugin : JavaPlugin() {
    private val fixtures = mutableMapOf<UUID, PaperVerificationSession>()

    override fun onEnable() {
        server.scheduler.runTaskTimer(
            this,
            Runnable {
                fixtures.values.toList().forEach { session ->
                    if (session.tick()) fixtures.remove(session.playerId)
                }
            },
            1,
            1,
        )
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        val player = sender as? Player ?: return false
        checkNotNull(PaperScreens.capabilities(player)) { "The acceptance client has not negotiated." }
        fixtures.remove(player.uniqueId)?.close()
        fixtures[player.uniqueId] =
            when (Mode.entries.single { it.command.contentEquals(command.name) }) {
                Mode.Automated -> PaperAcceptanceSession(this, player)
                Mode.ManualIme -> PaperManualImeSession(this, player)
            }
        return true
    }

    override fun onDisable() {
        val owned = fixtures.values.toList()
        fixtures.clear()
        owned.forEach(PaperVerificationSession::close)
    }

    /**
     * Exact plugin command names decoded once at the Bukkit boundary.
     */
    private enum class Mode(
        val command: String,
    ) {
        Automated("strata-verify"),
        ManualIme("strata-verify-ime"),
    }
}
