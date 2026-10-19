package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Native menu distribution modes decoded once from the adapter's normalized mouse input.
 */
internal enum class FabricMinecraftQuickCraftMode(
    private val code: Int,
) {
    /**
     * Distributes the carried stack across eligible slots.
     */
    DISTRIBUTE(0),

    /**
     * Places one carried item in each eligible slot.
     */
    ONE_PER_SLOT(1),

    /**
     * Fills eligible slots through the native creative clone protocol.
     */
    FILL(2),
    ;

    /**
     * Encodes this mode only when submitting a native menu quick-craft mask.
     */
    fun nativeCode(): Int = code

    /**
     * Decodes adapter-normalized input into native distribution modes.
     */
    companion object {
        /**
         * Decodes primary and secondary protocol buttons before the adapter-validated creative pick binding, returning null for unsupported input.
         */
        fun fromButton(
            button: Int,
            creativePick: Boolean,
        ): FabricMinecraftQuickCraftMode? =
            when (button) {
                0 -> DISTRIBUTE
                1 -> ONE_PER_SLOT
                else -> if (creativePick) FILL else null
            }
    }
}
