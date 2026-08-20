package dev.s7a.strata.integration.docs

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessFrame

/**
 * Immutable metadata for the overview showcase scenario.
 */
internal class OverviewScenario internal constructor(
    override val source: SourceReference,
    override val viewport: IntSize,
    override val scale: Int,
    override val tree: ShowcaseTree,
    override val description: () -> Element,
    override val render: () -> HeadlessFrame,
) : ShowcaseScenario
