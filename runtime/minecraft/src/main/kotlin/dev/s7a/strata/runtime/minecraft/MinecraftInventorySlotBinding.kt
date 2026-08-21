package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread binding between one retained Slot and a platform-managed Minecraft inventory slot.
 *
 * The binding snapshots platform item rendering, delegates pointer transactions, and notifies its one retained consumer when synchronized content changes.
 * Implementations retain no observer after the returned subscription closes and reject use after their owning [MinecraftUiPlatform] closes.
 */
@InternalStrataRuntimeApi
public interface MinecraftInventorySlotBinding {
    /**
     * Returns the current immutable item draw payload, or null for an empty slot.
     *
     * @return a platform snapshot safe to retain in one completed frame.
     * @throws IllegalStateException when called from another thread or after platform close.
     */
    public fun drawCommand(): PlatformDrawCommand?

    /**
     * Registers the sole retained-node observer for synchronized content changes.
     *
     * The callback runs synchronously on the owner thread from [MinecraftUiPlatform.refresh] and may invalidate the retained node.
     *
     * @param observer callback invoked only when the item snapshot changes.
     * @return idempotent owner-thread subscription release.
     * @throws IllegalStateException when called from another thread, after platform close, or while another observer is active.
     */
    public fun observe(observer: () -> Unit): AutoCloseable

    /**
     * Delegates one hit-tested pointer event to Minecraft's authoritative container-input path.
     *
     * Platform adapters may combine the common event with native modifier, double-click, and drag transaction state installed around host dispatch.
     *
     * @param event tree-coordinate event delivered to this Slot.
     * @return whether the Minecraft inventory transaction consumed the event.
     * @throws IllegalStateException when called from another thread or after platform close.
     * @throws Throwable when the platform container operation fails.
     */
    public fun dispatchPointer(event: PointerEvent): InputResult
}
