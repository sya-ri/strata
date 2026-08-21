package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize

/**
 * Immutable metadata connecting the overview to its compiled GameTest source, verified crop dimensions, and layout-only tree.
 *
 * Constructor inputs are snapshotted by their owning value types and are read synchronously by the documentation pipeline.
 */
internal class OverviewScenario internal constructor(
    override val source: SourceReference,
    override val viewport: IntSize,
    override val scale: Int,
    override val tree: ShowcaseTree,
) : ShowcaseScenario
