package net.minecraft.client

/**
 * Test-only singleton window owner for adapter failure tests, without a loaded Minecraft claim.
 */
internal class Minecraft private constructor() {
    private val window = FixtureWindow()

    /**
     * Returns the deterministic fixture window.
     */
    fun getWindow(): FixtureWindow = window

    /**
     * Mirrors the named client lookup shape.
     */
    companion object {
        private val instance = Minecraft()

        /**
         * Returns the shared fixture owner.
         */
        @JvmStatic
        fun getInstance(): Minecraft = instance
    }
}
