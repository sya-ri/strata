package dev.s7a.strata.performance

/**
 * Non-native loaded window fixture used only to test mapped reflection routing.
 */
internal class MappedPerformanceWindow(
    private val handle: Long = 42L,
) {
    /**
     * Returns the supplied diagnostic value without consulting a native window API.
     */
    fun actualHandle(): Long = handle
}
