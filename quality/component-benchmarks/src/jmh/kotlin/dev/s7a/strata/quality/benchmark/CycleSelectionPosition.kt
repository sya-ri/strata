package dev.s7a.strata.quality.benchmark

/**
 * Frozen selected positions for each of the three immutable option counts.
 */
public enum class CycleSelectionPosition {
    First,
    Middle,
    Last,
    ;

    /**
     * Returns the specified canonical index without inspecting a current selected value.
     */
    public fun index(size: Int): Int =
        when (this) {
            First -> 0
            Middle -> size / 2
            Last -> size - 1
        }
}
