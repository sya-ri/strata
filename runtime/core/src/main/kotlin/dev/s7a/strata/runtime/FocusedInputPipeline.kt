package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.KeyboardInputNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.TextInputNode

// Why: focus acquisition, reconciliation, transitions, and both focused protocols share one owner identity and ordering contract.

/**
 * Owns retained logical focus and dispatches keyboard and text events through one focused component.
 *
 * Every operation is synchronous on the tree thread supplied by the enclosing pipeline, and callback failures escape unchanged.
 */
@Suppress("TooManyFunctions")
internal class FocusedInputPipeline {
    private var focusedOwner: RetainedNode? = null

    /**
     * Reconciles retained focus after layout and applies one unambiguous initial-focus request.
     *
     * @param root current logical root after placement.
     */
    fun layoutCommitted(root: RetainedNode) {
        val owners = logicalOwners(root)
        val current = focusedOwner
        if (current != null && owners.contains(current).not()) {
            focusedOwner = null
        }
        val requested = owners.filter(::requestsInitialFocus)
        check(requested.size <= 1) { "A retained tree may have at most one placed initial focus target." }
        if (focusedOwner == null) {
            requested.singleOrNull()?.let(::setFocusedOwner)
        }
    }

    /**
     * Acquires focus for the deepest and latest-painted logical component at one pointer position when it has an accepting target.
     *
     * @param root current logical root after placement.
     * @param position pointer position in root coordinates.
     */
    fun acquireFromPointer(
        root: RetainedNode,
        position: IntOffset,
    ) {
        setFocusedOwner(focusOwnerAt(root.effectiveRoot, position, ancestorAllowsHit = true))
    }

    /**
     * Dispatches one keyboard event through the focused component from its innermost modifier toward its node.
     *
     * @param event immutable key event.
     * @return consumed when focused behavior handles the event, otherwise ignored.
     */
    fun dispatchKeyboard(event: KeyboardEvent): InputResult {
        val owner = focusedOwner ?: return InputResult.Ignored
        focusedNodes(owner).forEach { node ->
            val input = node as? KeyboardInputNode
            if (input != null) {
                val result = input.onKeyboardEvent(event)
                if (result === InputResult.Consumed) return result
            }
        }
        return InputResult.Ignored
    }

    /**
     * Dispatches one text-input event through the focused component from its innermost modifier toward its node.
     *
     * @param event immutable committed-character or preedit event.
     * @return consumed when focused behavior handles the event, otherwise ignored.
     */
    fun dispatchTextInput(event: TextInputEvent): InputResult {
        val owner = focusedOwner ?: return InputResult.Ignored
        focusedNodes(owner).forEach { node ->
            val input = node as? TextInputNode
            if (input != null) {
                val result = input.onTextInput(event)
                if (result === InputResult.Consumed) return result
            }
        }
        return InputResult.Ignored
    }

    /**
     * Clears one retained focus owner and delivers its distinct loss transition.
     *
     * @throws Throwable when focused behavior rejects the loss transition.
     */
    fun clear() {
        setFocusedOwner(null)
    }

    private fun setFocusedOwner(owner: RetainedNode?) {
        val previous = focusedOwner
        if (previous === owner) return
        focusedOwner = null
        previous?.let { current -> notifyFocus(current, false) }
        focusedOwner = owner
        owner?.let { current -> notifyFocus(current, true) }
    }

    private fun notifyFocus(
        owner: RetainedNode,
        focused: Boolean,
    ) {
        focusTargets(owner).forEach { target ->
            if (target.acceptsFocus || focused.not()) {
                target.onFocusChanged(focused)
            }
        }
    }

    private fun requestsInitialFocus(owner: RetainedNode): Boolean = owner.effectiveRoot.placed && focusTargets(owner).any { target -> target.acceptsFocus && target.requestsInitialFocus }

    private fun logicalOwners(root: RetainedNode): List<RetainedNode> {
        val output = ArrayList<RetainedNode>()

        fun visit(current: RetainedNode) {
            if (current.effectiveRoot.placed) {
                output += current
                current.children.forEach(::visit)
            }
        }
        visit(root)
        return output
    }

    private fun logicalOwner(entry: RetainedEntry): RetainedNode {
        var current = entry
        while (current is RetainedModifier) {
            current = current.effectiveChildAt(0)
        }
        return current as RetainedNode
    }

    private fun focusOwnerAt(
        retained: RetainedEntry,
        position: IntOffset,
        ancestorAllowsHit: Boolean,
    ): RetainedNode? {
        val descendantsAllowHit =
            ancestorAllowsHit &&
                ((retained.node is ClipChildrenNode).not() || position in retained.bounds)
        if (descendantsAllowHit) {
            for (index in (0 until retained.effectiveChildCount).reversed()) {
                val child = retained.effectiveChildAt(index)
                if (child.placed) {
                    val owner = focusOwnerAt(child, position, ancestorAllowsHit = true)
                    if (owner != null) return owner
                }
            }
        }
        if (ancestorAllowsHit && retained.placed && position in retained.bounds) {
            val owner = logicalOwner(retained)
            if (focusTargets(owner).any { target -> target.acceptsFocus }) return owner
        }
        return null
    }

    private fun focusedNodes(owner: RetainedNode): List<Node> =
        buildList {
            owner.modifiers.asReversed().forEach { modifier -> add(modifier.node) }
            add(owner.node)
        }

    private fun focusTargets(owner: RetainedNode): List<FocusTargetNode> = focusedNodes(owner).filterIsInstance<FocusTargetNode>()
}
