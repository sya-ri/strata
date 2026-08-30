package dev.s7a.strata.component

/**
 * Initial scale policy for fitting content into a pan-and-zoom viewport.
 */
public enum class PanZoomFit {
    /**
     * Fits the complete content inside the viewport and centers any spare axis.
     */
    Contain,

    /**
     * Fills the complete viewport and clips content on the overflowing axis.
     */
    Cover,
}
