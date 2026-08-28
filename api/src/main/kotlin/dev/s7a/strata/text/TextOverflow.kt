package dev.s7a.strata.text

/**
 * Immutable policy for text omitted by a multiline display's available space or line limit.
 *
 * The policy affects visible presentation without changing the source retained for semantics.
 * Values own no runtime resources and may be shared across threads.
 */
public enum class TextOverflow {
    /**
     * Omits content outside the available space without adding a marker.
     */
    Clip,

    /**
     * Marks truncation at the end of the last visible line when the marker fits.
     */
    Ellipsis,
}
