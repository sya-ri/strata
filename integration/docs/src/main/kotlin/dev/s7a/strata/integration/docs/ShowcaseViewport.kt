package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize

/**
 * Immutable logical viewport and pixel scale metadata for one showcase render.
 */
internal class ShowcaseViewport internal constructor(
    internal val size: IntSize,
    internal val scale: Int,
)
