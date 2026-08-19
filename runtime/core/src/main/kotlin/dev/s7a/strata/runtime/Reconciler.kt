package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashSet

/**
 * Reconciles immutable descriptions into retained nodes with linear direct-sibling matching.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class Reconciler(
    private val lifecycle: LifecycleManager,
    private val dirtyTracker: DirtyTracker,
) {
    private val provisionalRoots: MutableSet<RetainedNode> = LinkedHashSet()

    /**
     * Reconciles [description] against [previous] without attaching new nodes.
     *
     * @param previous the currently installed root, if any.
     * @param description the validated incoming root description.
     * @return the root that should become installed before attachment.
     */
    fun reconcileRoot(
        previous: RetainedNode?,
        description: Element,
    ): RetainedNode {
        if (previous == null) {
            return createDetached(description)
        }
        if (isCompatible(previous, description)) {
            updateInPlace(previous, description)
            return previous
        }
        val replacement = createDetached(description)
        val oldFailure = lifecycle.cleanup(previous)
        if (oldFailure != null) {
            val failures = FailureAccumulator(oldFailure)
            failures.addOptional(lifecycle.cleanup(replacement))
            provisionalRoots.remove(replacement)
            failures.throwFirst()
        }
        return replacement
    }

    /**
     * Marks an installed root as no longer provisional.
     *
     * @param root the root now reachable from the tree.
     */
    fun markInstalled(root: RetainedNode) {
        provisionalRoots.remove(root)
    }

    /**
     * Cleans every detached provisional subtree left by a failed reconciliation.
     *
     * @return the first cleanup failure, with later failures suppressed.
     */
    fun cleanupProvisionals(): Throwable? {
        val failures = FailureAccumulator()
        provisionalRoots.toList().asReversed().forEach { provisional ->
            failures.addOptional(lifecycle.cleanup(provisional))
        }
        provisionalRoots.clear()
        return failures.first
    }

    private fun createDetached(description: Element): RetainedNode {
        val retained = createSubtree(description)
        provisionalRoots.add(retained)
        return retained
    }

    private fun createSubtree(description: Element): RetainedNode {
        val node = description.type.createErased(description)
        val retained = RetainedNode(description, node, null)
        lifecycle.bind(retained)
        val result =
            runCatching {
                description.children.forEach { childDescription ->
                    val child = createSubtree(childDescription)
                    child.parent = retained
                    retained.children.add(child)
                }
            }
        return result
            .getOrElse { failure ->
                val failures = FailureAccumulator(failure)
                failures.addOptional(lifecycle.cleanup(retained))
                failures.throwFirst()
            }.let { retained }
    }

    private fun updateInPlace(
        retained: RetainedNode,
        description: Element,
    ) {
        val previous = retained.element
        val mask = description.type.updateErased(previous, description, retained.node)
        retained.element = description
        dirtyTracker.record(retained, mask)
        reconcileChildren(retained, description.children)
    }

    private fun reconcileChildren(
        parent: RetainedNode,
        descriptions: List<Element>,
    ) {
        val oldChildren = parent.children.toList()
        val keyed = HashMap<ElementKey<*>, RetainedNode>()
        oldChildren.forEach { oldChild ->
            val identity = oldChild.element.identity
            if (identity is ElementIdentity.Keyed) {
                keyed[identity.key] = oldChild
            }
        }
        val used = Collections.newSetFromMap(IdentityHashMap<RetainedNode, Boolean>())
        val nextChildren = ArrayList<RetainedNode>(descriptions.size)
        val newlyCreated = ArrayList<RetainedNode>()
        descriptions.forEachIndexed { index, childDescription ->
            val candidate = findCandidate(oldChildren, keyed, used, childDescription, index)
            if (candidate == null) {
                val created = createDetached(childDescription)
                newlyCreated.add(created)
                nextChildren.add(created)
            } else {
                used.add(candidate)
                updateInPlace(candidate, childDescription)
                candidate.parent = parent
                nextChildren.add(candidate)
            }
        }
        val failures = FailureAccumulator()
        oldChildren.asReversed().forEach { oldChild ->
            if (used.contains(oldChild).not()) {
                failures.addOptional(lifecycle.cleanup(oldChild))
            }
        }
        if (failures.first != null) {
            newlyCreated.asReversed().forEach { created -> failures.addOptional(lifecycle.cleanup(created)) }
            newlyCreated.forEach(provisionalRoots::remove)
            failures.throwFirst()
        }
        val changed = sameChildren(oldChildren, nextChildren).not()
        parent.children.clear()
        parent.children.addAll(nextChildren)
        newlyCreated.forEach { created ->
            created.parent = parent
            provisionalRoots.remove(created)
        }
        if (changed) {
            dirtyTracker.structural(parent)
        }
    }

    private fun findCandidate(
        oldChildren: List<RetainedNode>,
        keyed: Map<ElementKey<*>, RetainedNode>,
        used: Set<RetainedNode>,
        description: Element,
        index: Int,
    ): RetainedNode? =
        when (val identity = description.identity) {
            ElementIdentity.Positional -> {
                val positional = oldChildren.getOrNull(index)
                val isAvailable = positional != null && used.contains(positional).not()
                val hasIdentity = positional?.element?.identity === ElementIdentity.Positional
                val hasType = positional?.element?.type === description.type
                if (isAvailable && hasIdentity && hasType) {
                    positional
                } else {
                    null
                }
            }

            is ElementIdentity.Keyed -> {
                val keyedCandidate = keyed[identity.key]
                if (keyedCandidate != null && used.contains(keyedCandidate).not() && keyedCandidate.element.type === description.type) {
                    keyedCandidate
                } else {
                    null
                }
            }
        }

    private fun isCompatible(
        retained: RetainedNode,
        description: Element,
    ): Boolean {
        if (retained.element.type !== description.type) {
            return false
        }
        return when (val identity = description.identity) {
            ElementIdentity.Positional -> {
                retained.element.identity === ElementIdentity.Positional
            }

            is ElementIdentity.Keyed -> {
                retained.element.identity == identity
            }
        }
    }

    private fun sameChildren(
        oldChildren: List<RetainedNode>,
        nextChildren: List<RetainedNode>,
    ): Boolean {
        if (oldChildren.size != nextChildren.size) {
            return false
        }
        var same = true
        oldChildren.forEachIndexed { index, oldChild ->
            if (oldChild !== nextChildren[index]) {
                same = false
            }
        }
        return same
    }
}
