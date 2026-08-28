package dev.s7a.strata.runtime.minecraft

/**
 * Visual side of an insertion offset shared by two soft-wrapped lines.
 *
 * The value owns no layout or state and remains meaningful after reflow without retaining an obsolete line index.
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
