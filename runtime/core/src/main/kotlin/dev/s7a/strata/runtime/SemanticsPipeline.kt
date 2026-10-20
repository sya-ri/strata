package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.semantics.SemanticsOwner
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope

/**
 * Collects unresolved semantics from laid-out retained nodes.
 */
internal class SemanticsPipeline(
    private val ownerGuard: OwnerGuard,
    private val focusedInput: FocusedInputPipeline = FocusedInputPipeline(),
) {
    private var nextIdentity = 1L

    /**
     * Collects [root] semantics in parent-before-child order.
     *
     * @param root the laid-out retained root.
     * @return immutable tree-coordinate entries.
     */
    fun semantics(
        root: RetainedNode,
        ownership: Boolean = false,
    ): List<SemanticsEntry> =
        buildList {
            semanticsNode(root.effectiveRoot, this, root, ownership, 0, root.effectiveRoot.bounds)
        }

    private fun semanticsNode(
        retained: RetainedEntry,
        output: MutableList<SemanticsEntry>,
        root: RetainedNode,
        ownership: Boolean,
        paintOrder: Int,
        clip: IntRect,
    ) {
        if (DirtyPhase.Semantics in retained.dirty || retained.localSemantics == null) {
            retained.dirty -= DirtyMask.of(DirtyPhase.Semantics)
            retained.localSemantics = collectLocalSemantics(retained)
        }
        retained.localSemantics.orEmpty().forEach { semantics ->
            val owner = if (ownership) owner(retained, root, paintOrder, clip) else null
            output.add(if (owner == null) SemanticsEntry(retained.bounds, semantics) else SemanticsEntry(retained.bounds, semantics, owner))
        }
        if (ownership) {
            ownedChildren(retained, output, root, paintOrder, clip)
        } else if (retained is RetainedNode) {
            val children = retained.semanticsChildren
            for (index in children.indices) {
                val child = children[index].effectiveRoot
                if (child.placed) semanticsNode(child, output, root, false, 0, clip)
            }
        } else {
            for (index in 0 until retained.effectiveChildCount) {
                val child = retained.effectiveChildAt(index)
                if (child.placed) semanticsNode(child, output, root, false, 0, clip)
            }
        }
    }

    private fun ownedChildren(
        retained: RetainedEntry,
        output: MutableList<SemanticsEntry>,
        root: RetainedNode,
        paintOrder: Int,
        clip: IntRect,
    ) {
        val childClip = if (retained.node is ClipChildrenNode) intersection(clip, retained.bounds) else clip
        var order = paintOrder + (retained.transformedPaint?.beforeChildren?.size ?: 0)
        for (index in 0 until retained.effectiveChildCount) {
            val child = retained.effectiveChildAt(index)
            if (child.placed) {
                semanticsNode(child, output, root, true, order, childClip)
                order += child.paintSnapshot?.commands?.size ?: 0
            }
        }
    }

    private fun owner(
        entry: RetainedEntry,
        root: RetainedNode,
        order: Int,
        clip: IntRect,
    ): SemanticsOwner {
        var current = entry
        var contentOrder = order
        while (current is RetainedModifier) {
            contentOrder += current.transformedPaint?.beforeChildren?.size ?: 0
            current = current.effectiveChildAt(0)
        }
        val owner = current as RetainedNode
        if (owner.semanticsId == 0L) {
            check(nextIdentity < Long.MAX_VALUE) { "Native semantics identity space is exhausted." }
            owner.semanticsId = nextIdentity++
        }
        return SemanticsOwner(owner.semanticsId, focusedInput.isFocusable(root, owner), focusedInput.isFocused(owner), order, contentOrder, intersection(clip, entry.bounds))
    }

    private fun intersection(
        first: IntRect,
        second: IntRect,
    ): IntRect {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        return IntRect(left, top, maxOf(left, minOf(first.right, second.right)), maxOf(top, minOf(first.bottom, second.bottom)))
    }

    /**
     * Refreshes one node's detached payload and closes every actual callback scope before returning or throwing.
     */
    private fun collectLocalSemantics(retained: RetainedEntry): List<Semantics> {
        val semanticsCapability = retained.node as? SemanticsNode
        if (semanticsCapability == null) {
            ownerGuard.check()
            return emptyList()
        }
        val collector = SemanticsCollector(ownerGuard)
        try {
            semanticsCapability.semantics(collector)
            return collector.snapshot()
        } finally {
            collector.close()
        }
    }

    /**
     * Collects one node's unresolved semantics during its active callback.
     */
    private class SemanticsCollector(
        private val ownerGuard: OwnerGuard,
    ) : SemanticsScope {
        private var closed: Boolean = false
        private var first: Semantics? = null
        private var values: MutableList<Semantics>? = null

        override fun emit(semantics: Semantics) {
            checkActive()
            val buffer = values
            if (buffer != null) {
                buffer.add(semantics)
                return
            }
            val initial = first
            if (initial == null) {
                first = semantics
            } else {
                values =
                    ArrayList<Semantics>().also {
                        it.add(initial)
                        it.add(semantics)
                    }
                first = null
            }
        }

        /**
         * Snapshots emitted payloads while the callback scope remains active.
         */
        fun snapshot(): List<Semantics> {
            checkActive()
            return values?.toList() ?: first?.let { listOf(it) } ?: emptyList()
        }

        /**
         * Closes this collector after the semantics callback.
         */
        fun close() {
            ownerGuard.check()
            closed = true
            first = null
            values = null
        }

        private fun checkActive() {
            ownerGuard.check()
            check(closed.not()) { "The callback scope is no longer active." }
        }
    }
}
