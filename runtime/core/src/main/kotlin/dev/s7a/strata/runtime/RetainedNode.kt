package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.node.DeclarationProjectionNode
import dev.s7a.strata.node.DynamicChildrenNode
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.node.StateObserverNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation

/**
 * Stores one owned node, its immutable description, and retained pipeline state.
 *
 * The runtime owns this storage until cleanup completes.
 * Lifecycle attempt flags make cleanup idempotent after a failed operation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RetainedNode(
    var element: Element,
    node: Node,
    var logicalParent: RetainedNode?,
) : RetainedEntry(node) {
    /**
     * Last immutable dynamic-child list already validated and reconciled; cached access requires no sibling diff.
     */
    var dynamicDescriptions: List<Element>? = null

    /**
     * State reads of the current deferred callback, released before node cleanup.
     */
    var contentObservation: StateObservation? = null

    override val effectiveChildCount: Int
        get() = children.size

    /**
     * Active modifier entries owned by this logical component.
     */
    val modifiers: MutableList<RetainedModifier> = ArrayList()

    /**
     * Direct children in declared order.
     */
    val children: MutableList<RetainedNode> = ArrayList()

    /**
     * Current direct children with content capabilities or unfinished attachment, in declaration order.
     * These summaries follow the current membership contract in docs/development/performance.md.
     */
    var refreshChildren: List<RetainedNode> = emptyList()
        private set

    /**
     * Current direct children containing a component or modifier focus target.
     */
    var focusChildren: List<RetainedNode> = emptyList()
        private set

    /**
     * Current direct children containing a component or modifier semantics capability.
     */
    var semanticsChildren: List<RetainedNode> = emptyList()
        private set

    /**
     * Current direct children with an attachment attempt still pending in their subtree.
     */
    var attachmentChildren: List<RetainedNode> = emptyList()
        private set

    /**
     * Whether this component or a modifier supplies a focus target, independently of mutable acceptance.
     */
    var ownsFocusTargets: Boolean = node is FocusTargetNode
        private set

    /**
     * Whether this subtree supplies dynamic content, source observation, or declaration preparation.
     */
    var hasContentParticipants: Boolean = localContentParticipant()
        private set

    /**
     * Whether this subtree contains any immutable focus capability.
     */
    var hasFocusTargets: Boolean = ownsFocusTargets
        private set

    /**
     * Whether this subtree contains any immutable semantics capability.
     */
    var hasSemantics: Boolean = node is SemanticsNode
        private set

    /**
     * Whether any current component or modifier in this subtree has not had its attachment attempted.
     */
    var hasPendingAttachments: Boolean = true
        private set

    /**
     * Replaces direct-child summaries after reconciliation or completed attachment.
     * Capability identity comes from current nodes and modifiers; acceptance, values and geometry remain live.
     * Bottom-up attachment walkers pass false because their caller updates the enclosing parent once.
     */
    fun refreshTraversalSummary(propagate: Boolean = true) {
        if (cleanupStarted) {
            clearTraversalSummary()
            return
        }
        val oldContent = hasContentParticipants
        val oldFocus = hasFocusTargets
        val oldSemantics = hasSemantics
        val oldAttachments = hasPendingAttachments
        refreshChildren = selectChildren(refreshChildren) { it.hasContentParticipants || it.hasPendingAttachments }
        focusChildren = selectChildren(focusChildren, RetainedNode::hasFocusTargets)
        semanticsChildren = selectChildren(semanticsChildren, RetainedNode::hasSemantics)
        attachmentChildren = selectChildren(attachmentChildren, RetainedNode::hasPendingAttachments)
        ownsFocusTargets = localFocusParticipant()
        hasContentParticipants = localContentParticipant() || children.any { it.cleanupStarted.not() && it.hasContentParticipants }
        hasFocusTargets = ownsFocusTargets || focusChildren.isNotEmpty()
        hasSemantics = localSemanticsParticipant() || semanticsChildren.isNotEmpty()
        hasPendingAttachments = localAttachmentPending() || attachmentChildren.isNotEmpty()
        val changed =
            oldContent != hasContentParticipants || oldFocus != hasFocusTargets ||
                oldSemantics != hasSemantics || oldAttachments != hasPendingAttachments
        if (propagate && changed) logicalParent?.refreshTraversalSummary()
    }

    /**
     * Drops all additional child references before cleanup callbacks, including failed reconciliation.
     */
    fun clearTraversalSummary() {
        refreshChildren = emptyList()
        focusChildren = emptyList()
        semanticsChildren = emptyList()
        attachmentChildren = emptyList()
        ownsFocusTargets = false
        hasContentParticipants = false
        hasFocusTargets = false
        hasSemantics = false
        hasPendingAttachments = false
    }

    private fun localContentParticipant(): Boolean =
        node is DynamicChildrenNode || node is StateObserverNode || node is DeclarationProjectionNode ||
            modifiers.any { it.node is StateObserverNode }

    private fun localFocusParticipant(): Boolean = node is FocusTargetNode || modifiers.any { it.node is FocusTargetNode }

    private fun localSemanticsParticipant(): Boolean = node is SemanticsNode || modifiers.any { it.node is SemanticsNode }

    private fun localAttachmentPending(): Boolean = attachAttempted.not() || modifiers.any { it.attachAttempted.not() }

    private inline fun selectChildren(
        previous: List<RetainedNode>,
        predicate: (RetainedNode) -> Boolean,
    ): List<RetainedNode> {
        var selected: MutableList<RetainedNode>? = null
        var matched = 0
        for (index in children.indices) {
            val child = children[index]
            if (child.cleanupStarted.not() && predicate(child)) {
                val output = selected
                if (output != null) {
                    output.add(child)
                } else if (previous.getOrNull(matched) === child) {
                    matched += 1
                } else {
                    selected = ArrayList<RetainedNode>().also {
                        for (previousIndex in 0 until matched) it.add(previous[previousIndex])
                        it.add(child)
                    }
                }
            }
        }
        return selected ?: if (matched == previous.size) previous else previous.take(matched)
    }

    override fun effectiveChildAt(index: Int): RetainedEntry = children[index].effectiveRoot

    /**
     * Outermost retained entry that represents this logical component in the pipeline tree.
     */
    val effectiveRoot: RetainedEntry
        get() = modifiers.firstOrNull() ?: this
}
