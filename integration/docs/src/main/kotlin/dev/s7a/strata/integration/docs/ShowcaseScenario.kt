package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize

/**
 * Typed immutable metadata for one loaded-game-verified showcase crop.
 */
internal sealed interface ShowcaseScenario {
    /**
     * Source file containing this scenario's marker-delimited compiled source.
     */
    val source: SourceReference

    /**
     * Logical dimensions of the verified GameTest crop.
     */
    val viewport: IntSize

    /**
     * Logical-to-physical pixel scale recorded by the verified GameTest crop.
     */
    val scale: Int
}
