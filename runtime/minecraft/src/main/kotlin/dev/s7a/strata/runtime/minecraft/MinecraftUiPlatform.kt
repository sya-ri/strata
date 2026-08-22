package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
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
    public fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding

    /**
     * Resolves one resource-pack image through the active resource manager.
     *
     * @param resource platform-neutral resource identifier.
     * @return detached immutable pixels reflecting the currently active pack stack.
     * @throws IllegalArgumentException when the resource is missing or cannot be decoded as an image.
     * @throws IllegalStateException when called from another thread or after platform close.
     */
    public fun image(resource: ResourceId): DrawImage

    /**
     * Starts one player-skin lookup owned by the returned binding.
     *
     * Pixel sources are not passed to this operation.
     * Lookup completion may occur off-thread, while observable state is committed only by [refresh].
     *
     * @param source current-player, name, or UUID lookup source.
     * @return a new owner-thread binding initially pending or already resolved from cache.
     * @throws IllegalArgumentException when [source] contains direct pixels.
     * @throws IllegalStateException when called from another thread or after platform close.
     */
    public fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding

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
