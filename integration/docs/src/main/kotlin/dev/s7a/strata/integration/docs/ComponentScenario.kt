package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize

/**
 * Immutable metadata connecting one documented component to its dedicated minimal ScreenDefinition, verified full-frame dimensions, and complete component tree.
 *
 * Constructor inputs are snapshotted by their owning value types and are read synchronously by the documentation pipeline.
 */
internal class ComponentScenario internal constructor(
    internal val component: DocumentedComponent,
    override val source: SourceReference,
    internal val viewportMetadata: ShowcaseViewport,
    internal val tree: ShowcaseTree,
) : ShowcaseScenario {
    override val viewport: IntSize = viewportMetadata.size
    override val scale: Int = viewportMetadata.scale
}
