package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.ParentDataDelegateNode
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Common retained pipeline state for a component node or active modifier node.
 *
 * The runtime owns one entry until its lifecycle and node binding cleanup completes.
 * The parent link is effective pipeline ancestry, while component logical children remain on [RetainedNode].
 */
@OptIn(InternalStrataRuntimeApi::class)
internal sealed class RetainedEntry(
    val node: Node,
) {
    /**
     * Session-local diagnostic identity, assigned lazily on first monitoring and retained without a collector.
     */
    var diagnosticId: Long = 0L

    /**
     * Tree-local declaration identity, assigned only when a remote projection first visits this entry.
     */
    var declarationId: Long = 0L

    /**
     * Immutable node capability cached at ownership creation, avoiding repeated interface checks during parent layout.
     * The capability remains valid for this entry's node lifetime and is released with the entry.
     */
    val parentDataDelegate: ParentDataDelegateNode? = node as? ParentDataDelegateNode

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
     * Identity of the most recent completed child measurement pass, or null without children.
     * Markers contain no scope, callback, node, or historical geometry and belong to this entry's owner.
     */
    var childMeasurePass: Any? = null

    /**
     * Identity of the most recent child placement pass, or null without children.
     */
    var childLayoutPass: Any? = null

    /**
     * Parent measure pass that admitted this child; unequal markers make old participation irrelevant.
     */
    var parentMeasurePass: Any? = null

    /**
     * Parent layout pass that placed this child; unequal markers make the old offset irrelevant.
     */
    var parentLayoutPass: Any? = null

    /**
     * One parent-local offset borrowed by the current matching placement pass.
     * Cleanup clears this value and all participation markers before lifecycle callbacks.
     */
    var parentOffset: IntOffset? = null

    /**
     * Returns whether [child] participated in this entry's most recent completed measure pass.
     * Only current direct children may be supplied, under the retained tree's execution owner.
     */
    fun measuredChild(child: RetainedEntry): Boolean = childMeasurePass != null && child.parentMeasurePass === childMeasurePass

    /**
     * Returns [child]'s offset only when its placement belongs to this entry's current layout pass.
     * Only current direct children may be supplied, under the retained tree's execution owner.
     */
    fun childOffset(child: RetainedEntry): IntOffset? = if (childLayoutPass != null && child.parentLayoutPass === childLayoutPass) child.parentOffset else null

    /**
     * Constraints used by the most recent measure pass.
     */
    var measuredConstraints: Constraints? = null

    /**
     * Size returned by the most recent measure pass.
     */
    var measuredSize: IntSize = IntSize.Zero

    /**
     * Additional transform supplied by the effective parent during its most recent layout pass.
     */
    var transformFromParent: ChildTransform = ChildTransform.Identity

    /**
     * Accumulated continuous transform from this entry's local coordinates into root coordinates.
     */
    var localToTree: TreeTransform = TreeTransform.Identity

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
     * Current transformed local paint and overlays; keyed and bounded by [RetainedPaintCommands].
     * Transient attachment preserves this immutable state; terminal entry cleanup clears it before callbacks.
     */
    var transformedPaint: RetainedPaintCommands? = null

    /**
     * Current shared subtree output; bounded by current child membership and cleared before cleanup callbacks.
     */
    var paintSnapshot: RetainedPaintSnapshot? = null

    /**
     * Whether this entry or a descendant has pending paint work; propagation does not dirty local callbacks.
     */
    var paintSubtreeDirty: Boolean = true

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
