package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize

/**
 * Immutable metadata connecting the overview to its compiled GameTest source, verified frame dimensions, and ordered Minecraft-component forest.
 *
 * Constructor inputs are snapshotted by their owning value types and are read synchronously by the documentation pipeline.
 */
internal class OverviewScenario internal constructor(
    override val source: SourceReference,
    override val viewport: IntSize,
    override val scale: Int,
    trees: List<ShowcaseTree>,
) : ShowcaseScenario {
    /**
     * Ordered Minecraft component roots after platform-neutral layout scaffolding is omitted.
     */
    internal val trees: List<ShowcaseTree> = trees.toList()
}
