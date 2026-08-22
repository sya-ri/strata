package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable declarative locator for one synchronized inventory slot.
 *
 * A binding owns no player, menu, item, callback, or platform reference and may be reused safely across descriptions and threads.
 * Runtime adapters resolve it against their active authoritative container protocol.
 */
public sealed interface SlotBinding {
    /**
     * Runtime coordinate system used to interpret [index].
     */
    @InternalStrataRuntimeApi
    public val source: Source

    /**
     * Non-negative index interpreted within [source].
     */
    @InternalStrataRuntimeApi
    public val index: Int

    /**
     * Closed coordinate systems understood by standard runtime adapters.
     */
    @InternalStrataRuntimeApi
    public enum class Source {
        /**
         * Active player's inventory as exposed by the current menu.
         */
        PlayerInventory,

        /**
         * Logical slot in the non-player container exposed by the current server-owned menu.
         */
        Container,

        /**
         * Raw slot in the current active menu.
         */
        ActiveMenu,
    }
}
