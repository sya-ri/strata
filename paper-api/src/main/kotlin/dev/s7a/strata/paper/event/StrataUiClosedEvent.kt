package dev.s7a.strata.paper.event

import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin

/**
 * UI ownership has ended, including failed opening. Emitted once; the session cannot reopen.
 * Retirement and shutdown callbacks do not grant native world access.
 * Ordinarily delivered synchronously on Paper's primary thread or the player's Folia region.
 * Notification only; it is not cancellable.
 */
public class StrataUiClosedEvent(
    public val player: Player,
    public val ownerPlugin: Plugin,
    public val identity: Long,
    public val session: UiSession,
    public val presentation: UiPresentation?,
    public val category: UiCategory?,
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
