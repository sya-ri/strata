package dev.s7a.strata.runtime.minecraft

/**
 * Visual side of a soft-wrap insertion offset; survives reflow without storing a line index.
 */
internal enum class MinecraftTextCaretAffinity {
    /**
     * Prefer the preceding line's end when both lines describe the same insertion offset.
     */
    Upstream,

    /**
     * Prefer the following line's start, also used for ordinary scalar editing and external value replacement.
     */
    Downstream,
}
