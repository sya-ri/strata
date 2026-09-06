package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
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
 * Owns retained logical focus, keyboard traversal, and focused keyboard and text dispatch.
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
     * An ignored Tab press then moves focus through visible accepting logical owners in paint order, with Shift reversing that order.
     *
     * @param root current logical root after placement.
     * @param event immutable key event.
     * @return consumed when focused behavior handles the event or Tab moves or retains an eligible focus target, otherwise ignored.
     */
    fun dispatchKeyboard(
        root: RetainedNode,
        event: KeyboardEvent,
    ): InputResult {
        focusedOwner?.let { owner ->
            focusedNodes(owner).forEach { node ->
                val input = node as? KeyboardInputNode
                if (input != null) {
                    val result = input.onKeyboardEvent(event)
                    if (result === InputResult.Consumed) return result
                }
            }
        }
        if (event is KeyboardEvent.Press && event.key == KeyCode.Tab) {
            return moveFocus(root, reverse = event.modifiers.shift)
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
        val previousTargets = focusedTargets
        val retainedTargets = focusTargets(previous)
        releaseRetainedReferences()
        val failures = FailureAccumulator()
        previousTargets.forEach { target ->
            if (retainedTargets.any { it === target }) {
                failures.capture { target.onFocusChanged(false) }
            }
        }
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
        val retainedEntries = focusTargetEntries(owner)
        val retained = retainedEntries.map(FocusTargetEntry::target)
        val previous = focusedTargets
        val accepting =
            retainedEntries
                .filter { entry -> entry.retained.placed && entry.target.acceptsFocus }
                .map(FocusTargetEntry::target)
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

    private fun requestsInitialFocus(owner: RetainedNode): Boolean =
        focusTargetEntries(owner).any { entry ->
            entry.retained.placed && entry.target.acceptsFocus && entry.target.requestsInitialFocus
        }

    private fun moveFocus(
        root: RetainedNode,
        reverse: Boolean,
    ): InputResult {
        val owners = logicalOwners(root)
        val currentIndex = owners.indexOfFirst { owner -> owner === focusedOwner }
        val traversal =
            when {
                currentIndex < 0 && reverse -> owners.asReversed()
                currentIndex < 0 -> owners
                reverse -> owners.subList(0, currentIndex).asReversed() + owners.subList(currentIndex, owners.size).asReversed()
                else -> owners.subList(currentIndex + 1, owners.size) + owners.subList(0, currentIndex + 1)
            }
        val next = traversal.firstOrNull { owner -> isTraversalCandidate(root, owner) } ?: return InputResult.Ignored
        setFocusedOwner(next)
        return InputResult.Consumed
    }

    private fun isTraversalCandidate(
        root: RetainedNode,
        owner: RetainedNode,
    ): Boolean =
        focusTargetEntries(owner).any { entry ->
            entry.retained.placed && entry.target.acceptsFocus && isVisible(root, entry.retained)
        }

    private fun isVisible(
        root: RetainedNode,
        target: RetainedEntry,
    ): Boolean {
        var visible = intersection(root.effectiveRoot.bounds, target.bounds) ?: return false
        var ancestor = target.parent
        while (ancestor != null) {
            if (ancestor.node is ClipChildrenNode) {
                visible = intersection(visible, ancestor.bounds) ?: return false
            }
            ancestor = ancestor.parent
        }
        return true
    }

    private fun intersection(
        first: IntRect,
        second: IntRect,
    ): IntRect? {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        return if (left < right && top < bottom) IntRect(left, top, right, bottom) else null
    }

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
                ((retained.node is ClipChildrenNode).not() || retained.contains(position))
        if (descendantsAllowHit) {
            for (index in (0 until retained.effectiveChildCount).reversed()) {
                val child = retained.effectiveChildAt(index)
                if (child.placed) {
                    val owner = focusOwnerAt(child, position, ancestorAllowsHit = true)
                    if (owner != null) return owner
                }
            }
        }
        if (ancestorAllowsHit && retained.acceptsFocusAt(position)) {
            return logicalOwner(retained)
        }
        return null
    }

    private fun focusedNodes(owner: RetainedNode): List<Node> = focusedEntries(owner).map(RetainedEntry::node)

    private fun focusedEntries(owner: RetainedNode): List<RetainedEntry> =
        buildList {
            owner.modifiers.asReversed().forEach(::add)
            add(owner)
        }

    private fun focusTargets(owner: RetainedNode): List<FocusTargetNode> = focusTargetEntries(owner).map(FocusTargetEntry::target)

    private fun focusTargetEntries(owner: RetainedNode): List<FocusTargetEntry> =
        if (owner.cleanupStarted) {
            emptyList()
        } else {
            focusedEntries(owner).mapNotNull { retained ->
                (retained.node as? FocusTargetNode)?.let { target -> FocusTargetEntry(retained, target) }
            }
        }

    private fun RetainedEntry.contains(position: IntOffset): Boolean = localToTree.contains(measuredSize, position)

    private fun RetainedEntry.acceptsFocusAt(position: IntOffset): Boolean = placed && contains(position) && (node as? FocusTargetNode)?.acceptsFocus == true

    private data class FocusTargetEntry(
        val retained: RetainedEntry,
        val target: FocusTargetNode,
    )
}
