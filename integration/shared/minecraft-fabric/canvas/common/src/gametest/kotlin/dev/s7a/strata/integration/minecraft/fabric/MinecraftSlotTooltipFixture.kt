package dev.s7a.strata.integration.minecraft.fabric

/**
 * Server-owned inventory inputs selected by the independent native tooltip acceptance scene.
 */
internal enum class MinecraftSlotTooltipFixture {
    /**
     * Named, enchanted sword with nonzero damage.
     */
    OriginalSword,

    /**
     * Different name and durability while the pointer remains stationary.
     */
    UpdatedSword,

    /**
     * Native item-provided tooltip image and carried-item policy.
     */
    Bundle,

    /**
     * Empty authoritative slot.
     */
    Empty,

    /**
     * Original sword with an independently server-owned carried stack.
     */
    CarriedSword,

    /**
     * Bundle with an independently server-owned carried stack.
     */
    CarriedBundle,
}
