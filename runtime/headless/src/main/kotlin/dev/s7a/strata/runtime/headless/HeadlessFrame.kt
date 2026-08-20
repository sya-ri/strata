package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.semantics.SemanticsEntry

/**
 * An immutable, thread-safe result of one synchronous headless render.
 *
 * The frame retains the physical image and a defensive, unmodifiable snapshot of logical semantics.
 * Semantics are in core emission order, remain unscaled and unclipped, and no description, tree, or command list is retained.
 * The image size is exactly the checked logical viewport multiplied by [pixelScale].
 * All exposed frame, image, and semantics reads are safe from any thread after construction.
 *
 * @property viewport the logical fixed viewport used by the render.
 * @property pixelScale the positive logical-to-physical scale.
 * @property image the physical raster whose size equals viewport multiplied by pixelScale.
 * @property semantics the logical unscaled semantics entries in core emission order.
 */
public sealed interface HeadlessFrame {
    /**
     * The logical fixed viewport supplied to the render.
     */
    public val viewport: IntSize

    /**
     * The positive logical-to-physical pixel scale.
     */
    public val pixelScale: Int

    /**
     * The physical raster for this frame.
     */
    public val image: HeadlessImage

    /**
     * The defensive, unmodifiable logical semantics snapshot.
     */
    public val semantics: List<SemanticsEntry>
}
