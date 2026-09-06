package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment

/**
 * Measures the modified component subtree in a fixed content coordinate space and uniformly scales it to fit the resulting outer bounds without cropping.
 *
 * The virtual child is measured once at exactly [contentSize].
 * This modifier reports that natural size constrained independently on each axis by its parent, then contains the complete child bounds with one uniform scale and positions spare space using [contentAlignment].
 * When [allowUpscaling] is false, the resolved scale is capped at one so a larger outer viewport preserves the original logical content size.
 * When [allowUpscaling] is true and an outer modifier fills the viewport, the fit tracks viewport growth and shrinkage; on a fixed physical window this compensates for host GUI-density changes and keeps approximately the same physical proportions.
 * Earlier modifiers operate in outer coordinates, while later modifiers and the component subtree operate in the fixed content coordinates.
 * The same transform applies to descendant geometry, portable painting, clipping, input, focus visibility, and semantics; root-overlay commands remain in root coordinates and use only the transformed anchor.
 * Opaque platform draw commands currently require an exact integer translation and fail during painting under a non-unit scale or fractional translation because their payload cannot be transformed generically.
 * When either constrained outer dimension is zero, the virtual child remains measured but is not placed, excluding its subtree from later phases.
 * Child paint overflow is transformed but not implicitly clipped.
 *
 * The returned immutable chain retains only [contentSize], [contentAlignment], and [allowUpscaling], owns no external resources, and is safe to build or share between threads.
 * Retained execution remains confined to the owning tree thread, and child or pipeline failures propagate through that tree's ordinary failure contract.
 *
 * @param contentSize positive fixed logical extent used to measure the component subtree.
 * @param contentAlignment placement of the scaled content inside any spare outer width or height.
 * @param allowUpscaling whether a fitting scale greater than one may enlarge the content.
 * @return this chain with one appended active scale-to-fit modifier.
 * @throws IllegalArgumentException when either content dimension is zero.
 * @throws UnsupportedOperationException during painting when the transformed subtree emits an opaque platform draw command under a non-unit scale or fractional translation.
 */
public fun Modifier.scaleToFit(
    contentSize: IntSize,
    contentAlignment: Alignment = Alignment.Center,
    allowUpscaling: Boolean = false,
): Modifier {
    require(0 < contentSize.width && 0 < contentSize.height) { "Scale-to-fit content dimensions must be positive." }
    return then(ScaleToFitModifier.Element(contentSize, contentAlignment, allowUpscaling))
}
