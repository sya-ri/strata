package dev.s7a.strata.integration.docs

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessFrame

/**
 * Typed immutable metadata and compiled callbacks for one showcase render.
 */
internal sealed interface ShowcaseScenario {
    /**
     * Source file containing this scenario's marker-delimited compiled source.
     */
    val source: SourceReference

    /**
     * Logical viewport supplied to the headless renderer.
     */
    val viewport: IntSize

    /**
     * Logical-to-physical pixel scale supplied to the headless renderer.
     */
    val scale: Int

    /**
     * Expected logical tree topology.
     */
    val tree: ShowcaseTree

    /**
     * Compiled public element description callback.
     */
    val description: () -> Element

    /**
     * Compiled headless render callback.
     */
    val render: () -> HeadlessFrame
}
