package dev.s7a.strata.component

/**
 * Immutable nearest-sampled mapping policy for expandable nine-slice segments.
 *
 * Values own no resource and are safe to retain across threads.
 */
public sealed interface NineSliceCenterMode {
    /**
     * Repeats expandable source pixels and crops the final repetition.
     */
    public data object Tiled : NineSliceCenterMode

    /**
     * Scales each complete expandable source segment once.
     */
    public data object Stretched : NineSliceCenterMode
}
