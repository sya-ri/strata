package dev.s7a.strata.quality.benchmark

/**
 * Typed workload names decoded by JMH from its external parameter values.
 */
public enum class ReactiveWorkload {
    /**
     * Reuses an unchanged frame.
     */
    Static,

    /**
     * Updates one observed leaf.
     */
    Single,

    /**
     * Projects changing inputs to an equal output.
     */
    MapEqual,

    /**
     * Projects changing inputs to a changed output.
     */
    MapChanged,

    /**
     * Updates a parent and nested child together.
     */
    Nested,

    /**
     * Changes one of 128 independent regions.
     */
    Independent128,

    /**
     * Changes one projection shared by 128 regions.
     */
    FanOut128,

    /**
     * Appends and removes one row beyond the visible list window.
     */
    ListAppend,

    /**
     * Prepends and removes one row while retaining the visible anchor.
     */
    ListPrepend,
}
