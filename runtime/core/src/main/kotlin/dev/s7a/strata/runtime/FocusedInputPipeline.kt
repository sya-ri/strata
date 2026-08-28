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
import dev.s7a.strata.runtime.spi.RuntimeTextInputFocus
import dev.s7a.strata.spi.InternalStrataRuntimeApi

// Why: focus acquisition, reconciliation, transitions, and both focused protocols share one owner identity and ordering contract.

/**
 * Owns retained logical focus and dispatches keyboard and text events through one focused component.
 *
 * Every operation is synchronous on the tree thread supplied by the enclosing pipeline, and callback failures escape unchanged.
 * Only the current owner's accepting target identities are retained, so changes to a target's acceptance receive distinct transitions without selecting another owner.
 * Reconciliation forgets removed targets without invoking disposed nodes; clearing or terminal release drops every target reference.
 * Its optional native text-input token is stable for the current editable owner and target identities, and retains no target references itself.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions")
internal class FocusedInputPipeline {
    private var focusedOwner: RetainedNode? = null
    private var focusedTargets: List<FocusTargetNode> = emptyList()
    private var textInputTargets: List<FocusTargetNode> = emptyList()

    /**
     * Detached identity of the current editable focus interval, read only on the enclosing tree's owner thread.
     */
    var textInputFocus: RuntimeTextInputFocus? = null
        private set

    /**
     * Reconciles retained focus after layout and applies one unambiguous initial-focus request.
     *
     * @param root current logical root after placement.
     */
    fun layoutCommitted(root: RetainedNode) {
        val owners = logicalOwners(root)
        val current = focusedOwner
        if (current != null && owners.contains(current).not()) {
            releaseRetainedReferences()
        }
        val requested = owners.filter(::requestsInitialFocus)
        check(requested.size <= 1) { "A retained tree may have at most one placed initial focus target." }
        if (focusedOwner == null) {
            requested.singleOrNull()?.let(::setFocusedOwner)
        } else {
            reconcileFocusTargets(checkNotNull(focusedOwner))
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
     * Ownership is cleared first, and every focus observer is attempted even when another observer fails.
     *
     * @throws Throwable when focused behavior rejects the loss transition.
     */
    fun clear() {
        val previous = focusedOwner ?: return
        focusedOwner = null
        val failures = FailureAccumulator()
        focusTargets(previous).forEach { target -> failures.capture { target.onFocusChanged(false) } }
        failures.throwIfPresent()
    }

    /**
     * Releases the retained focus owner without invoking focus callbacks.
     *
     * Terminal tree cleanup owns lifecycle notification separately, and this method severs every owner and target reference.
     */
    fun releaseRetainedReferences() {
        focusedOwner = null
        focusedTargets = emptyList()
        textInputTargets = emptyList()
        textInputFocus = null
    }

    private fun setFocusedOwner(owner: RetainedNode?) {
        val previous = focusedOwner
        if (previous === owner) return
        val previousTargets = focusedTargets
        val retainedTargets = previous?.let(::focusTargets).orEmpty()
        releaseRetainedReferences()
        previousTargets.forEach { target ->
            if (retainedTargets.any { it === target }) target.onFocusChanged(false)
        }
        focusedOwner = owner
        owner?.let(::reconcileFocusTargets)
    }

    private fun reconcileFocusTargets(owner: RetainedNode) {
        val retained = focusTargets(owner)
        val previous = focusedTargets
        val accepting = retained.filter(FocusTargetNode::acceptsFocus)
        focusedTargets = accepting
        previous.forEach { target ->
            if (retained.any { it === target } && accepting.none { it === target }) target.onFocusChanged(false)
        }
        accepting.forEach { target ->
            if (previous.none { it === target }) target.onFocusChanged(true)
        }
        val editable = accepting.filter(FocusTargetNode::requiresTextInput)
        val unchanged = editable.size == textInputTargets.size && editable.indices.all { editable[it] === textInputTargets[it] }
        if (unchanged.not()) {
            textInputTargets = editable
            textInputFocus = if (editable.isEmpty()) null else RuntimeTextInputFocus.create()
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

    private fun focusTargets(owner: RetainedNode): List<FocusTargetNode> = if (owner.cleanupStarted) emptyList() else focusedNodes(owner).filterIsInstance<FocusTargetNode>()
}
