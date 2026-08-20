package dev.s7a.strata.layout

/**
 * Main-axis placement policy for a linear layout.
 */
public enum class Arrangement {
    /**
     * Places children from the start edge.
     */
    Start,

    /**
     * Centers children in the available main-axis space.
     */
    Center,

    /**
     * Places children against the end edge.
     */
    End,

    /**
     * Distributes slack between children.
     */
    SpaceBetween,

    /**
     * Distributes slack around and between children.
     */
    SpaceAround,

    /**
     * Distributes slack evenly before, between, and after children.
     */
    SpaceEvenly,
}
