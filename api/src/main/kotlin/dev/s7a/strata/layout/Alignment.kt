package dev.s7a.strata.layout

/**
 * Typed two-axis placement policy for box children.
 *
 * @property horizontalAlignment the horizontal placement policy.
 * @property verticalAlignment the vertical placement policy.
 */
public enum class Alignment(
    public val horizontalAlignment: HorizontalAlignment,
    public val verticalAlignment: VerticalAlignment,
) {
    /**
     * Top-start placement.
     */
    TopStart(HorizontalAlignment.Start, VerticalAlignment.Top),

    /**
     * Top-center placement.
     */
    TopCenter(HorizontalAlignment.Center, VerticalAlignment.Top),

    /**
     * Top-end placement.
     */
    TopEnd(HorizontalAlignment.End, VerticalAlignment.Top),

    /**
     * Center-start placement.
     */
    CenterStart(HorizontalAlignment.Start, VerticalAlignment.Center),

    /**
     * Center placement.
     */
    Center(HorizontalAlignment.Center, VerticalAlignment.Center),

    /**
     * Center-end placement.
     */
    CenterEnd(HorizontalAlignment.End, VerticalAlignment.Center),

    /**
     * Bottom-start placement.
     */
    BottomStart(HorizontalAlignment.Start, VerticalAlignment.Bottom),

    /**
     * Bottom-center placement.
     */
    BottomCenter(HorizontalAlignment.Center, VerticalAlignment.Bottom),

    /**
     * Bottom-end placement.
     */
    BottomEnd(HorizontalAlignment.End, VerticalAlignment.Bottom),
}
