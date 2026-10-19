package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Native menu actions encoded into the owning version's external click enum only at submission.
 */
internal enum class FabricMinecraftInventoryAction {
    PICKUP,
    QUICK_MOVE,
    SWAP,
    CLONE,
    THROW,
    QUICK_CRAFT,
    PICKUP_ALL,
}
