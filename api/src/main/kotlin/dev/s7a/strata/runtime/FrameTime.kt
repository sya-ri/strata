package dev.s7a.strata.runtime

/**
 * Monotonic frame timestamp supplied by a platform host to time-aware retained nodes.
 *
 * Values are opaque nanosecond ticks from one host clock and may be negative because only subtraction and ordering within that clock are meaningful.
 *
 * @property nanoseconds host-clock timestamp in nanoseconds.
 */
public data class FrameTime(
    public val nanoseconds: Long,
)
