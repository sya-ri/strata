package dev.s7a.strata.integration.paper

import dev.s7a.strata.runtime.paper.PaperScreens
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Explicitly installed test-only plugin; never runs a scene until the connected client invokes its command.
 * Fixtures run and release on the player owner and write invocation-bound evidence only after server assertions pass.
 */
public class PaperAcceptancePlugin :
    JavaPlugin(),
    Listener {
    private val fixtures = ConcurrentHashMap<UUID, PaperVerificationSession>()
    private val minecart = System.getProperty("strata.paper.minecart").toBoolean()
    private val regionized = runCatching { Class.forName("io.papermc.paper.threadedregions.RegionizedServer") }.isSuccess

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        if (regionized) return
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
        PaperScreens.execute(player) {
            fixtures.remove(player.uniqueId)?.close()
            fixtures[player.uniqueId] =
                when (Mode.entries.single { it.command.contentEquals(command.name) }) {
                    Mode.Automated -> PaperAcceptanceSession(this, player, verifyMigration = regionized && minecart.not(), moveInMinecart = minecart)
                    Mode.ManualIme -> PaperManualImeSession(this, player)
                }
        }
        if (regionized) {
            player.scheduler.runAtFixedRate(this, RegionTicker(player), { fixtures.remove(player.uniqueId) }, 1L, 1L)
        }
        return true
    }

    override fun onDisable() {
        val owned = fixtures.values.toList()
        fixtures.clear()
        if (regionized.not()) owned.forEach(PaperVerificationSession::close)
    }

    /**
     * Releases test-owned inventory before Strata disconnects the player's UI owner.
     */
    @Suppress("unused") // Bukkit invokes this registered event handler reflectively.
    @EventHandler(priority = EventPriority.LOWEST)
    public fun onQuit(event: PlayerQuitEvent) {
        fixtures.remove(event.player.uniqueId)?.let { fixture ->
            PaperScreens.execute(event.player) { fixture.close() }
        }
    }

    /**
     * Keeps Folia-only method signatures out of the listener class inspected by Paper 1.20.
     */
    private inner class RegionTicker(
        private val player: Player,
    ) : Consumer<ScheduledTask> {
        override fun accept(task: ScheduledTask) {
            val fixture = fixtures[player.uniqueId]
            if (fixture == null) {
                task.cancel()
            } else {
                PaperScreens.execute(player) {
                    if (fixture.tick()) {
                        fixtures.remove(player.uniqueId, fixture)
                        task.cancel()
                    }
                }
            }
        }
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
