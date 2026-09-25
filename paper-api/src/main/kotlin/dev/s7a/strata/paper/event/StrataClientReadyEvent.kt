package dev.s7a.strata.paper.event

import dev.s7a.strata.ui.UiClientCapabilities
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Successful client negotiation. Emitted once per connection generation.
 * Delivered synchronously on Paper's primary thread or the player's Folia region. Notification only; it is not cancellable.
 */
public class StrataClientReadyEvent(
    public val player: Player,
    public val capabilities: UiClientCapabilities,
) : Event() {
    override fun getHandlers(): HandlerList = eventHandlers

    /**
     * Bukkit listener registration for this exact event type.
     */
    public companion object {
        private val eventHandlers = HandlerList()

        /**
         * Returns the handler list shared by every instance of this event.
         */
        @JvmStatic
        public fun getHandlerList(): HandlerList = eventHandlers
    }
}
