package dev.s7a.strata.integration.paper

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.paper.event.StrataUiClosedEvent
import dev.s7a.strata.paper.event.StrataUiOpenedEvent
import dev.s7a.strata.paper.event.StrataUiPresentationChangedEvent
import dev.s7a.strata.paper.open
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin

/**
 * Verifies two real remote HUDs, acknowledged switches, stable event identity, and terminal notifications.
 * The owning acceptance fixture advances this listener only on Paper's primary thread.
 */
internal class PaperUiPresentationVerification(
    plugin: Plugin,
    player: Player,
) : Listener,
    AutoCloseable {
    private val opened = mutableListOf<StrataUiOpenedEvent>()
    private val changed = mutableListOf<StrataUiPresentationChangedEvent>()
    private val closed = mutableListOf<StrataUiClosedEvent>()
    private val sessions = mutableListOf<UiSession>()
    private var phase = Phase.Huds

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        val admission =
            runCatching {
                repeat(2) { index -> sessions += UiDefinition("Strata HUD $index", presentation = UiPresentation.Hud) { Column { Text("HUD $index") } }.open(plugin, player) }
                check(sessions.all { it.presentation == null }) { "Opening must wait for native application." }
            }
        if (admission.isFailure) close()
        admission.getOrThrow()
    }

    /**
     * Advances only after native acknowledgement and requires event snapshots to agree with the applied state.
     */
    fun tick(): Boolean {
        check(Bukkit.isPrimaryThread())
        check(sessions.none { it.status is UiSessionStatus.Closed }) { "A remote HUD closed before verification." }
        val first = sessions.first()
        when (phase) {
            Phase.Huds -> {
                if (sessions.any { it.presentation == null }) return false
                check(opened.size == 2)
                check(opened.map { it.session }.toSet() == sessions.toSet())
                check(opened.all { it.presentation == UiPresentation.Hud })
                check(opened.map { it.identity }.toSet().size == 2)
                first.switch(UiPresentation.Screen)
                check(first.presentation == UiPresentation.Hud)
                phase = Phase.Screen
            }

            Phase.Screen -> {
                if (first.presentation != UiPresentation.Screen) return false
                check(changed.single().previous == UiPresentation.Hud)
                check(changed.single().presentation == UiPresentation.Screen)
                first.switch(UiPresentation.Hud)
                check(first.presentation == UiPresentation.Screen)
                phase = Phase.HudAgain
            }

            Phase.HudAgain -> {
                if (first.presentation != UiPresentation.Hud) return false
                check(changed.size == 2)
                val identity = opened.single { it.session === first }.identity
                check(changed.all { it.identity == identity && it.session === first })
                check(changed.last().previous == UiPresentation.Screen)
                check(changed.last().presentation == UiPresentation.Hud)
                sessions.forEach {
                    it.close()
                    it.close()
                }
                check(closed.map { it.session }.toSet() == sessions.toSet() && closed.size == 2)
                check(closed.all { it.presentation == UiPresentation.Hud && it.reason == UiCloseReason.Closed && it.session.status is UiSessionStatus.Closed })
                return true
            }
        }
        return false
    }

    /**
     * Captures committed opening snapshots without reading mutable client state.
     */
    @EventHandler
    fun opened(event: StrataUiOpenedEvent) {
        if (event.session in sessions) opened += event
    }

    /**
     * Records only changes belonging to this fixture's two handles.
     */
    @EventHandler
    fun changed(event: StrataUiPresentationChangedEvent) {
        if (event.session in sessions) changed += event
    }

    /**
     * Records terminal notifications, including duplicates that must fail the fixture.
     */
    @EventHandler
    fun closed(event: StrataUiClosedEvent) {
        if (event.session in sessions) closed += event
    }

    override fun close() {
        sessions.forEach(UiSession::close)
        HandlerList.unregisterAll(this)
    }

    private enum class Phase { Huds, Screen, HudAgain }
}
