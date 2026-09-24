package dev.s7a.strata.integration.paper

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.examples.paper.DemoRemoteExtensions
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.paper.PaperScreens
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteScreenSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.mutableStateOf
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * One real player's command, input, extension, vanilla inventory, and screen-replacement transaction.
 * Mutable state and native inventory access remain on the player's execution owner; the original stack is restored on every exit.
 */
internal class PaperAcceptanceSession(
    private val plugin: Plugin,
    private val player: Player,
    private val verifyMigration: Boolean = false,
    moveInMinecart: Boolean = false,
) : PaperVerificationSession {
    override val playerId: UUID = player.uniqueId
    private val field = TextFieldState(maxLength = 64)
    private val updates = mutableStateOf(0)
    private val original = player.inventory.getItem(9)?.clone()
    private var applied = 0
    private var activated = 0
    private var pickedUp = false
    private val journey = if (moveInMinecart) PaperMinecartJourney(player) else null
    private var journeyFinished = journey == null
    private var phase =
        when {
            journey != null -> Phase.Riding
            verifyMigration -> Phase.Migrating
            else -> Phase.Controls
        }
    private var ticks = 0
    private var closed = false
    private var handle: RemoteScreenSession = PaperScreens.open(plugin, player, if (phase != Phase.Controls) ScreenDefinition("Strata verification moving") { Column { Text("Moving") } } else controls())
    private val migration = if (verifyMigration) player.teleportAsync(player.location.add(4096.0, 0.0, 0.0)) else CompletableFuture.completedFuture(true)

    /**
     * Advances the server assertions and returns true after terminal cleanup or an explicit failure.
     */
    override fun tick(): Boolean {
        return closed ||
            runCatching {
                check(player.isOnline) { "Acceptance client disconnected before completing." }
                check(ticks++ < 1200) { "Paper acceptance timed out in $phase." }
                journeyFinished = journey?.tick() ?: true
                if (prepareControls().not()) return@runCatching false
                check(handle.status !is RemoteSessionStatus.Closed) { "Acceptance screen closed: ${handle.status}" }
                when (phase) {
                    Phase.Migrating, Phase.Riding -> {
                        error("Migration must complete before controls advance.")
                    }

                    Phase.Controls -> {
                        updates.value++
                        if (applied == 1 && activated == 1) {
                            check(field.value.contentEquals("remote-日本語")) { "Server text does not match confirmed native input." }
                            player.inventory.setItem(9, ItemStack(Material.DIRT, 7))
                            handle = PaperScreens.open(plugin, player, inventory())
                            phase = Phase.Inventory
                        }
                    }

                    Phase.Inventory -> {
                        if (inventoryRestored() && journeyFinished) {
                            complete()
                            return@runCatching true
                        }
                    }
                }
                false
            }.getOrElse { failure ->
                plugin.logger.severe("Paper acceptance failed: ${failure.message}")
                close()
                true
            }
    }

    private fun inventoryRestored(): Boolean {
        val carried = player.itemOnCursor
        val stored = player.inventory.getItem(9)
        if (carried.type == Material.DIRT && carried.amount == 7 && stored?.type?.isAir != false) {
            pickedUp = true
        }
        return pickedUp && carried.type.isAir && stored?.amount == 7
    }

    private fun prepareControls(): Boolean {
        if (phase == Phase.Riding) {
            check(handle.status !is RemoteSessionStatus.Closed) { "The mounted screen must remain open while moving." }
            if (checkNotNull(journey).travelled < 8.0) return false
            handle = PaperScreens.open(plugin, player, controls())
            phase = Phase.Controls
        }
        if (phase != Phase.Migrating) return true
        if (migration.isDone.not()) return false
        check(migration.getNow(false)) { "The region migration did not complete." }
        check(handle.status == RemoteSessionStatus.Closed(RemoteFailure.ContainerChanged)) { "Teleportation must retire the old native container." }
        updates.value += 1
        handle = PaperScreens.open(plugin, player, controls())
        phase = Phase.Controls
        return true
    }

    private fun controls(): ScreenDefinition =
        ScreenDefinition("Strata verification controls") {
            Column(
                Modifier.Empty
                    .size(176, 120)
                    .background(ArgbColor(0xFF202020.toInt()))
                    .padding(8),
                spacing = 4,
            ) {
                TextField(field, IntSize(160, 20))
                Button("Apply", 160, modifier = Modifier.Empty.onActivate { applied++ })
                element(DemoRemoteExtensions.marker(ArgbColor(0xFFFF0000.toInt()), DemoRemoteExtensions.activation { activated++ }))
                Text("Updates: ${updates.value}")
            }
        }

    private fun inventory(): ScreenDefinition =
        ScreenDefinition("Strata verification inventory") {
            Column(
                Modifier.Empty
                    .size(176, 80)
                    .background(ArgbColor(0xFF202020.toInt()))
                    .padding(8),
            ) {
                Slot(Slots.playerInventory(9))
                Text("Pick up and restore seven dirt items.")
            }
        }

    private fun complete() {
        val run = requireNotNull(System.getProperty("strata.paper.run"))
        val directory = plugin.dataFolder.toPath()
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("server.properties"), "runId=$run\nplayer=$playerId\nversion=${plugin.server.bukkitVersion}\ntext=confirmed\ncustomAction=$activated\nbutton=$applied\nslot=round-trip\nupdates=${updates.value}\nregionMigration=$verifyMigration\nminecartDistance=${journey?.travelled ?: 0.0}\n")
        handle = PaperScreens.open(plugin, player, ScreenDefinition("Strata verification complete") { Column { Text("Complete") } })
        player.inventory.setItem(9, original)
        journey?.close()
        closed = true
        plugin.logger.info("Strata Paper acceptance passed for $playerId")
    }

    override fun close() {
        handle.close()
        journey?.close()
        if (closed.not()) {
            closed = true
            player.setItemOnCursor(null)
            player.inventory.setItem(9, original)
        }
    }

    /**
     * Server-owned acceptance progression decoded without protocol string discriminators.
     */
    private enum class Phase { Migrating, Riding, Controls, Inventory }
}
