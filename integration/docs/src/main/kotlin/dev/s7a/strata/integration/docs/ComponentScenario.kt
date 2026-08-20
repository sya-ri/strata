package dev.s7a.strata.integration.docs

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessFrame

/**
 * Immutable metadata for one documented built-in component scenario.
 */
internal class ComponentScenario internal constructor(
    internal val component: DocumentedComponent,
    override val source: SourceReference,
    internal val viewportMetadata: ShowcaseViewport,
    override val tree: ShowcaseTree,
    override val description: () -> Element,
    override val render: () -> HeadlessFrame,
) : ShowcaseScenario {
    override val viewport: IntSize = viewportMetadata.size
    override val scale: Int = viewportMetadata.scale
}
