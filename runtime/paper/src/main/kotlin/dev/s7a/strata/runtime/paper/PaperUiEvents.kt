package dev.s7a.strata.runtime.paper

import dev.s7a.strata.paper.event.StrataClientDisconnectedEvent
import dev.s7a.strata.paper.event.StrataClientReadyEvent
import dev.s7a.strata.paper.event.StrataUiClosedEvent
import dev.s7a.strata.paper.event.StrataUiOpenedEvent
import dev.s7a.strata.paper.event.StrataUiPresentationChangedEvent
import dev.s7a.strata.runtime.remote.RemoteLifecycleEvent
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin

/**
 * Converts committed service notifications into platform events without reading mutable session state.
 */
internal fun paperUiEvent(
    player: Player,
    event: RemoteLifecycleEvent<Plugin>,
): Event =
    when (event) {
        is RemoteLifecycleEvent.Ready -> StrataClientReadyEvent(player, event.capabilities)
        is RemoteLifecycleEvent.Disconnected -> StrataClientDisconnectedEvent(player, event.reason)
        is RemoteLifecycleEvent.Opened -> StrataUiOpenedEvent(player, event.owner, event.identity, event.session, event.presentation, event.category)
        is RemoteLifecycleEvent.PresentationChanged -> StrataUiPresentationChangedEvent(player, event.owner, event.identity, event.session, event.previous, event.presentation, event.category)
        is RemoteLifecycleEvent.Closed -> StrataUiClosedEvent(player, event.owner, event.identity, event.session, event.presentation, event.category, event.reason)
    }
