package dev.s7a.strata.quality.benchmark

/**
 * Fixed scalar-order font metrics outside the ordinary dyadic-width proof.
 */
public enum class ExceptionalTextWorkload {
    /**
     * Inexact positive and negative advances with a late first-fitting suffix.
     */
    SignedFractional,

    /**
     * Inexact cancellation whose finite absolute sum exceeds the Float range before an overwide tail.
     */
    FiniteCancellation,

    /**
     * Signed inexact spacing followed by an infinite advance, with different native overflow outcomes.
     */
    InfiniteTail,
}
