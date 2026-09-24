package dev.s7a.strata.runtime.velocity

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.runtime.remote.RemoteLifecycleEvent
import dev.s7a.strata.velocity.event.StrataClientDisconnectedEvent
import dev.s7a.strata.velocity.event.StrataClientReadyEvent
import dev.s7a.strata.velocity.event.StrataUiClosedEvent
import dev.s7a.strata.velocity.event.StrataUiOpenedEvent
import dev.s7a.strata.velocity.event.StrataUiPresentationChangedEvent

/**
 * Converts committed service notifications into platform events without reading mutable session state.
 */
internal fun velocityUiEvent(
    player: Player,
    event: RemoteLifecycleEvent<Any>,
): Any =
    when (event) {
        is RemoteLifecycleEvent.Ready -> StrataClientReadyEvent(player, event.capabilities)
        is RemoteLifecycleEvent.Disconnected -> StrataClientDisconnectedEvent(player, event.reason)
        is RemoteLifecycleEvent.Opened -> StrataUiOpenedEvent(player, event.owner, event.identity, event.session, event.presentation, event.category)
        is RemoteLifecycleEvent.PresentationChanged -> StrataUiPresentationChangedEvent(player, event.owner, event.identity, event.session, event.previous, event.presentation, event.category)
        is RemoteLifecycleEvent.Closed -> StrataUiClosedEvent(player, event.owner, event.identity, event.session, event.presentation, event.category, event.reason)
    }
