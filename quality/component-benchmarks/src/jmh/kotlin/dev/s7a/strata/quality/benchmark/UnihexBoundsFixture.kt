package dev.s7a.strata.quality.benchmark

/**
 * The frozen 24 geometry inputs: four admitted widths crossed with six independent packed-row patterns.
 * Names address external fixture records; they do not select runtime component behavior.
 *
 * @property width decoded source columns.
 */
public enum class UnihexBoundsFixture(
    public val width: Int,
) {
    W8Empty(8),
    W8AllInk(8),
    W8LeftEdge(8),
    W8RightEdge(8),
    W8CenteredSparse(8),
    W8DisjointExtrema(8),
    W16Empty(16),
    W16AllInk(16),
    W16LeftEdge(16),
    W16RightEdge(16),
    W16CenteredSparse(16),
    W16DisjointExtrema(16),
    W24Empty(24),
    W24AllInk(24),
    W24LeftEdge(24),
    W24RightEdge(24),
    W24CenteredSparse(24),
    W24DisjointExtrema(24),
    W32Empty(32),
    W32AllInk(32),
    W32LeftEdge(32),
    W32RightEdge(32),
    W32CenteredSparse(32),
    W32DisjointExtrema(32),
}
