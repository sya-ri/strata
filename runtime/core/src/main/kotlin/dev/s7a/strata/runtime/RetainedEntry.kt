package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import dev.s7a.strata.semantics.Semantics

/**
 * Common retained pipeline state for a component node or active modifier node.
 *
 * The runtime owns one entry until its lifecycle and node binding cleanup completes.
 * The parent link is effective pipeline ancestry, while component logical children remain on [RetainedNode].
 */
internal sealed class RetainedEntry(
    val node: Node,
) {
    /**
     * Number of direct children in the effective pipeline tree.
     */
    abstract val effectiveChildCount: Int

    /**
     * Returns one direct child from the effective pipeline tree.
     *
     * @param index the validated direct-child index.
     * @return the retained child that participates in pipeline traversal.
     */
    abstract fun effectiveChildAt(index: Int): RetainedEntry

    /**
     * Effective parent used for dirty propagation.
     */
    var parent: RetainedEntry? = null

    /**
     * Phases that still require work.
     */
    var dirty: DirtyMask = DirtyMask.All

    /**
     * Child placement offsets from the most recent layout pass.
     */
    val placements: MutableMap<Int, IntOffset> = HashMap()

    /**
     * Direct virtual children measured by the most recent measure pass.
     */
    var measuredChildren: Set<Int> = emptySet()

    /**
     * Constraints used by the most recent measure pass.
     */
    var measuredConstraints: Constraints? = null

    /**
     * Size returned by the most recent measure pass.
     */
    var measuredSize: IntSize = IntSize.Zero

    /**
     * Accumulated tree-coordinate bounds.
     */
    var bounds: IntRect = IntRect(0, 0, 0, 0)

    /**
     * Whether this entry has completed a measure pass.
     */
    var measured: Boolean = false

    /**
     * Whether this entry has completed a layout pass.
     */
    var laidOut: Boolean = false

    /**
     * Whether this entry participates in the current laid-out tree.
     */
    var placed: Boolean = false

    /**
     * Cached local commands, or null before the first paint.
     */
    var localCommands: List<LocalDrawCommand>? = null

    /**
     * Cached local post-child overlay commands, or null before the first paint.
     */
    var localOverlayCommands: List<LocalDrawCommand>? = null

    /**
     * Cached root-coordinate overlay commands, or null before the first paint.
     */
    var rootOverlayCommands: List<LocalDrawCommand>? = null

    /**
     * Anchor bounds used to produce [rootOverlayCommands].
     */
    var rootOverlayAnchor: IntRect? = null

    /**
     * Root viewport used to produce [rootOverlayCommands].
     */
    var rootOverlayViewport: IntSize? = null

    /**
     * Cached immutable local semantics payloads, or null before the first pass.
     */
    var localSemantics: List<Semantics>? = null

    /**
     * Binding release returned by the node runtime bridge.
     */
    var bindingRelease: (() -> Unit)? = null

    /**
     * Whether cleanup has begun for this entry.
     */
    var cleanupStarted: Boolean = false

    /**
     * Whether the parent-first attach walk reached this entry.
     */
    var attachAttempted: Boolean = false

    /**
     * Whether detach has already been attempted.
     */
    var detachAttempted: Boolean = false

    /**
     * Whether dispose has already been attempted.
     */
    var disposeAttempted: Boolean = false
}
