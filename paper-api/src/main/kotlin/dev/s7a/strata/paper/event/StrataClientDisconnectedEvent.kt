package dev.s7a.strata.paper.event

import dev.s7a.strata.ui.UiCloseReason
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * A previously ready Strata connection has ended; all its UIs are already terminal.
 * Retirement and shutdown callbacks do not grant native world access.
 * Ordinarily delivered synchronously on Paper's primary thread or the player's Folia region.
 * Notification only; it is not cancellable.
 */
public class StrataClientDisconnectedEvent(
    public val player: Player,
    public val reason: UiCloseReason,
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
