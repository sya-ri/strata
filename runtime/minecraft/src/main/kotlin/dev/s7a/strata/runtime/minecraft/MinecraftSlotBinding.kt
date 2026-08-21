package dev.s7a.strata.runtime.minecraft

/**
 * Immutable declarative locator for one Minecraft inventory Slot.
 *
 * A binding owns no player, menu, item, callback, or platform reference and may be reused safely across descriptions and threads.
 * The version adapter resolves [source] and [index] against the active host on its owner thread, then sends mutations through Minecraft's authoritative container protocol.
 */
public sealed interface MinecraftSlotBinding {
    /**
     * The typed menu coordinate system used to interpret [index].
     */
    public val source: MinecraftSlotSource

    /**
     * The non-negative index interpreted within [source].
     */
    public val index: Int
}
