package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import dev.s7a.strata.semantics.Semantics

/**
 * Stores one owned node, its immutable description, and retained pipeline state.
 *
 * The runtime owns this storage until cleanup completes.
 * Lifecycle attempt flags make cleanup idempotent after a failed operation.
 */
internal class RetainedNode(
    var element: Element,
    val node: Node,
    var parent: RetainedNode?,
) {
    /**
     * Direct children in declared order.
     */
    val children: MutableList<RetainedNode> = ArrayList()

    /**
     * Phases that still require work.
     */
    var dirty: DirtyMask = DirtyMask.All

    /**
     * Child placement offsets from the most recent layout pass.
     */
    val placements: MutableMap<Int, IntOffset> = HashMap()

    /**
     * Direct children measured by the most recent measure pass.
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
     * Whether this node has completed a measure pass.
     */
    var measured: Boolean = false

    /**
     * Whether this node has completed a layout pass.
     */
    var laidOut: Boolean = false

    /**
     * Whether this node participates in the current laid-out tree.
     */
    var placed: Boolean = false

    /**
     * Cached local commands, or null before the first paint.
     */
    var localCommands: List<LocalDrawCommand>? = null

    /**
     * Cached immutable local semantics payloads, or null before the first pass.
     */
    var localSemantics: List<Semantics>? = null

    /**
     * Binding release returned by the node runtime bridge.
     */
    var bindingRelease: (() -> Unit)? = null

    /**
     * Whether cleanup has begun for this node.
     */
    var cleanupStarted: Boolean = false

    /**
     * Whether the node was reached by the parent-first attach walk.
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
