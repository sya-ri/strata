package dev.s7a.strata.component

/**
 * Immutable geometry snapshot shared by one scroll area and any linked scrollbars.
 *
 * @property offset current non-negative logical content displacement.
 * @property viewportExtent visible logical extent along the scrolling axis.
 * @property contentExtent complete logical content extent along the scrolling axis.
 */
public data class ScrollMetrics(
    public val offset: Double = 0.0,
    public val viewportExtent: Int = 0,
    public val contentExtent: Int = 0,
) {
    init {
        require(offset.isFinite() && 0.0 <= offset) { "Scroll offset must be finite and non-negative." }
        require(0 <= viewportExtent) { "Scroll viewport extent must be non-negative." }
        require(0 <= contentExtent) { "Scroll content extent must be non-negative." }
    }

    /**
     * Maximum legal content displacement.
     */
    public val maximumOffset: Double
        get() = maxOf(0, contentExtent - viewportExtent).toDouble()

    /**
     * Whether the content currently exceeds its viewport.
     */
    public val canScroll: Boolean
        get() = 0.0 < maximumOffset
}
