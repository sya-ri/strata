package dev.s7a.strata.performance

/**
 * Loaded namespace fixture whose JVM method names differ from development Minecraft names.
 */
internal class MappedPerformanceClient {
    /**
     * Returns a separate fixture window through a mapped instance method.
     */
    fun actualWindow(): MappedPerformanceWindow = MappedPerformanceWindow()

    /**
     * Supplies the mapped static accessor shape without native state.
     */
    companion object {
        /**
         * Creates the non-native owner used by reflection routing tests.
         */
        @JvmStatic
        fun actualInstance(): MappedPerformanceClient = MappedPerformanceClient()
    }
}
