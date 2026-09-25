package dev.s7a.strata.integration.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiOperationResult
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import dev.s7a.strata.velocity.VelocityUi
import dev.s7a.strata.velocity.event.StrataUiClosedEvent
import dev.s7a.strata.velocity.event.StrataUiOpenedEvent
import dev.s7a.strata.velocity.event.StrataUiPresentationChangedEvent
import java.util.concurrent.CompletableFuture

/**
 * Verifies real proxy event delivery and acknowledged HUD switches without blocking asynchronous listeners.
 * Event threads inspect snapshots only; every live session read or operation is queued to its owner.
 */
internal class VelocityUiPresentationVerification(
    private val plugin: VelocityAcceptancePlugin,
    private val proxy: ProxyServer,
    private val player: Player,
) : AutoCloseable {
    private val handle = CompletableFuture<UiSession>()
    private val opened = CompletableFuture<StrataUiOpenedEvent>()
    private val screen = CompletableFuture<StrataUiPresentationChangedEvent>()
    private val hud = CompletableFuture<StrataUiPresentationChangedEvent>()
    private val closed = CompletableFuture<StrataUiClosedEvent>()

    /**
     * Completes only after native opening, both switches, and the terminal notification agree on identity.
     */
    fun start(): CompletableFuture<Unit> {
        proxy.eventManager.register(plugin, this)
        return VelocityUi
            .open(plugin, player) {
                UiDefinition("Strata proxy HUD", presentation = UiPresentation.Hud) { Column { Text("Proxy HUD") } }
            }.thenCompose { session ->
                handle.complete(session)
                VelocityUi.execute(plugin) {
                    check(session.status !is UiSessionStatus.Closed) { "Proxy HUD admission failed: ${session.status}" }
                }
            }.thenCompose { opened }
            .thenCompose { event ->
                VelocityUi.execute(plugin) {
                    check(event.presentation == UiPresentation.Hud && event.session.presentation == UiPresentation.Hud)
                    check(event.session.switch(UiPresentation.Screen) == UiOperationResult.Accepted)
                    check(event.session.presentation == UiPresentation.Hud)
                }
            }.thenCompose { screen }
            .thenCompose { event ->
                VelocityUi.execute(plugin) {
                    check(event.identity == opened.getNow(null)?.identity && event.previous == UiPresentation.Hud)
                    check(event.session.presentation == UiPresentation.Screen)
                    check(event.session.switch(UiPresentation.Hud) == UiOperationResult.Accepted)
                    check(event.session.presentation == UiPresentation.Screen)
                }
            }.thenCompose { hud }
            .thenCompose { event ->
                VelocityUi.execute(plugin) {
                    check(event.identity == opened.getNow(null)?.identity && event.previous == UiPresentation.Screen)
                    check(event.session.presentation == UiPresentation.Hud)
                    event.session.close()
                    event.session.close()
                }
            }.thenCompose { closed }
            .thenCompose { event ->
                VelocityUi.execute(plugin) {
                    check(event.identity == opened.getNow(null)?.identity)
                    check(event.reason == UiCloseReason.Closed && event.presentation == UiPresentation.Hud)
                    check(event.session.status == UiSessionStatus.Closed(UiCloseReason.Closed))
                }
            }.whenComplete { _, failure ->
                proxy.eventManager.unregisterListener(plugin, this)
                if (failure != null) close()
            }
    }

    /**
     * Receives the committed opening snapshot from Velocity's actual event manager.
     */
    @Subscribe
    fun opened(event: StrataUiOpenedEvent) {
        if (event.player === player && event.session === handle.getNow(null)) opened.complete(event)
    }

    /**
     * Resolves each expected presentation transition independently of listener completion order.
     */
    @Subscribe
    fun changed(event: StrataUiPresentationChangedEvent) {
        if (event.player !== player || event.session !== handle.getNow(null)) return
        when (event.presentation) {
            UiPresentation.Screen -> screen.complete(event)
            UiPresentation.Hud -> hud.complete(event)
        }
    }

    /**
     * Receives termination without touching the live owner-thread handle on the event thread.
     */
    @Subscribe
    fun closed(event: StrataUiClosedEvent) {
        if (event.player === player && event.session === handle.getNow(null)) closed.complete(event)
    }

    override fun close() {
        proxy.eventManager.unregisterListener(plugin, this)
        handle.thenAccept { session -> VelocityUi.execute(plugin, session::close) }
    }
}
