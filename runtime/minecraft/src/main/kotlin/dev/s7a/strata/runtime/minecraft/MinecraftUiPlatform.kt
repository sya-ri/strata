package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread platform services used by Minecraft-backed components without exposing version-specific game types.
 *
 * One host owns the platform from successful construction through terminal close.
 * The platform may lend stable bindings to retained nodes, but [close] releases every observer, item snapshot, callback, and native reference.
 */
@InternalStrataRuntimeApi
public interface MinecraftUiPlatform : AutoCloseable {
    /**
     * Returns the stable retained-node binding for one declarative Slot locator.
     *
     * @param binding immutable locator interpreted against the active player and current menu.
     * @return a stable owner-thread binding owned by this platform.
     * @throws IllegalArgumentException when [binding] cannot be resolved by the current menu.
     * @throws IllegalStateException when called from another thread or after platform close.
     */
    public fun inventorySlot(binding: MinecraftSlotBinding): MinecraftInventorySlotBinding

    /**
     * Polls current game state and synchronously notifies bindings whose immutable item snapshots changed.
     *
     * The host invokes this immediately before every attached frame so direct inventory packets and menu transactions share one update boundary.
     *
     * @throws IllegalStateException when called from another thread or after platform close.
     * @throws Throwable when platform state cannot be read or an observer fails.
     */
    public fun refresh()

    /**
     * Permanently releases platform bindings and native references on the owner thread.
     *
     * Close is idempotent after completion.
     *
     * @throws IllegalStateException when called from another thread.
     * @throws Throwable when cleanup fails.
     */
    override fun close()
}
