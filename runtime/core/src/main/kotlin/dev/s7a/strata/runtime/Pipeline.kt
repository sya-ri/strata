package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.ParentDataModifierNode
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Executes retained measure and layout work through virtual modifier ancestry.
 *
 * Logical component scopes expose logical children only.
 * Each modifier scope exposes exactly one virtual child.
 */
@Suppress("TooManyFunctions")
@OptIn(InternalStrataRuntimeApi::class)
internal class Pipeline(
    private val threadGuard: ThreadGuard,
) {
    private val paintPipeline = PaintPipeline(threadGuard)
    private val inputPipeline = InputPipeline()
    private val semanticsPipeline = SemanticsPipeline(threadGuard)

    /**
     * Measures [root] under [constraints].
     *
     * @param root the installed logical root.
     * @param constraints the root constraints.
     * @return the measured effective root size.
     */
    fun measure(
        root: RetainedNode,
        constraints: Constraints,
    ): IntSize = measureEntry(root.effectiveRoot, constraints)

    /**
     * Lays out [root] after its effective measure pass.
     *
     * @param root the installed logical root.
     */
    fun layout(root: RetainedNode) {
        val effective = root.effectiveRoot
        effective.bounds = IntRect(0, 0, effective.measuredSize.width, effective.measuredSize.height)
        effective.placed = true
        layoutEntry(effective)
    }

    /**
     * Paints [root] in effective parent-before-child order.
     *
     * @param root the installed logical root.
     * @return commands in accumulated tree coordinates.
     */
    fun paint(root: RetainedNode): List<DrawCommand> = paintPipeline.paint(root.effectiveRoot)

    /**
     * Dispatches [event] through effective modifier and component ancestry.
     *
     * @param root the installed logical root.
     * @param event the tree-coordinate event.
     * @return consumed when one node handles the event, otherwise ignored.
     */
    fun dispatch(
        root: RetainedNode,
        event: PointerEvent,
    ): InputResult = inputPipeline.dispatch(root.effectiveRoot, event)

    /**
     * Clears hover from every placed capable node in [root].
     *
     * @param root the installed logical root retained across session detachment.
     * @throws Throwable when a hover callback rejects the exit transition.
     */
    fun clearPointerHover(root: RetainedNode) {
        inputPipeline.clearHover(root.effectiveRoot)
    }

    /**
     * Collects unresolved semantics through effective ancestry.
     *
     * @param root the installed logical root.
     * @return entries in effective parent-before-child order.
     */
    fun semantics(root: RetainedNode): List<SemanticsEntry> = semanticsPipeline.semantics(root.effectiveRoot)

    /**
     * Returns whether a measured subtree still has pending measurement work.
     *
     * @param root the installed logical root.
     * @return true when reachable effective entries need measurement.
     */
    fun hasPendingMeasure(root: RetainedNode): Boolean = pendingMeasure(root.effectiveRoot)

    /**
     * Checks that the effective root has completed measurement and has no pending measurement work.
     *
     * @param root the installed logical root.
     */
    fun requireMeasuredRoot(root: RetainedNode) {
        val effective = root.effectiveRoot
        check(effective.measured) { "The tree must be measured before layout." }
        check(pendingMeasure(effective).not()) { "The tree must be measured before layout." }
    }

    /**
     * Checks that the effective root has completed layout and has no pending geometry work.
     *
     * @param root the installed logical root.
     */
    fun requirePlacedRoot(root: RetainedNode) {
        val effective = root.effectiveRoot
        check(effective.placed) { "The tree must be laid out before this operation." }
        check(pendingMeasure(effective).not()) { "The tree must be laid out before this operation." }
        check(pendingLayout(effective).not()) { "The tree must be laid out before this operation." }
    }

    private fun measureEntry(
        retained: RetainedEntry,
        constraints: Constraints,
    ): IntSize {
        if (DirtyPhase.Measure in retained.dirty || retained.measuredConstraints != constraints) {
            retained.dirty -= DirtyMask.of(DirtyPhase.Measure)
            retained.dirty += DirtyMask.of(DirtyPhase.Layout, DirtyPhase.Paint, DirtyPhase.Semantics)
            val measuredChildren = HashSet<Int>()
            val guard = ScopeGuard(threadGuard)
            val scope =
                object : MeasureScope {
                    override val childCount: Int
                        get() {
                            guard.check()
                            return retained.effectiveChildCount
                        }

                    override fun measureChild(
                        index: Int,
                        constraints: Constraints,
                    ): IntSize {
                        guard.check()
                        require(index in 0 until childCount) { "Child index is outside the measurement scope." }
                        check(measuredChildren.add(index)) { "A child may be measured only once per pass." }
                        return measureEntry(retained.effectiveChildAt(index), constraints)
                    }

                    override fun <D : Any> childParentData(
                        index: Int,
                        key: ParentDataKey<D>,
                    ): D? {
                        guard.check()
                        require(index in 0 until childCount) { "Child index is outside the measurement scope." }
                        return resolveParentData(retained.effectiveChildAt(index), key)
                    }
                }
            val measured =
                try {
                    if (retained.node is MeasureNode) {
                        retained.node.measure(scope, constraints)
                    } else {
                        constraints.constrain(IntSize.Zero)
                    }
                } finally {
                    guard.close()
                }
            check(constraints.isSatisfiedBy(measured)) { "Node returned a size outside its constraints." }
            if (measuredChildren.isNotEmpty()) {
                check(retained.node is LayoutNode) { "A node that measures children must implement LayoutNode." }
            }
            retained.measuredSize = measured
            retained.measuredConstraints = constraints
            retained.measuredChildren = measuredChildren
            retained.measured = true
        }
        return retained.measuredSize
    }

    private fun layoutEntry(retained: RetainedEntry) {
        val mustLayout = retained.laidOut.not() || DirtyPhase.Layout in retained.dirty
        if (mustLayout) {
            retained.dirty -= DirtyMask.of(DirtyPhase.Layout)
            retained.placements.clear()
            val layoutCapability = retained.node as? LayoutNode
            if (layoutCapability != null) {
                val scope =
                    object : LayoutScope {
                        val guard = ScopeGuard(threadGuard)

                        override val size: IntSize
                            get() {
                                guard.check()
                                return retained.measuredSize
                            }

                        override val childCount: Int
                            get() {
                                guard.check()
                                return retained.effectiveChildCount
                            }

                        override fun measuredChildSize(index: Int): IntSize {
                            guard.check()
                            require(index in 0 until childCount) { "Child index is outside the layout scope." }
                            check(index in retained.measuredChildren) { "The child was not measured in this pass." }
                            return retained.effectiveChildAt(index).measuredSize
                        }

                        override fun <D : Any> childParentData(
                            index: Int,
                            key: ParentDataKey<D>,
                        ): D? {
                            guard.check()
                            require(index in 0 until childCount) { "Child index is outside the layout scope." }
                            return resolveParentData(retained.effectiveChildAt(index), key)
                        }

                        override fun placeChild(
                            index: Int,
                            offset: IntOffset,
                        ) {
                            guard.check()
                            require(index in 0 until childCount) { "Child index is outside the layout scope." }
                            check(index in retained.measuredChildren) { "The child was not measured in this pass." }
                            check(retained.placements.containsKey(index).not()) {
                                "A child may be placed only once per pass."
                            }
                            retained.placements[index] = offset
                        }
                    }
                try {
                    layoutCapability.layout(scope)
                } finally {
                    scope.guard.close()
                }
            }
            retained.laidOut = true
        }
        for (index in 0 until retained.effectiveChildCount) {
            val child = retained.effectiveChildAt(index)
            val offset = retained.placements[index]
            if (offset == null || retained.measuredChildren.contains(index).not()) {
                child.placed = false
                child.laidOut = false
            } else {
                val left = Math.addExact(retained.bounds.left, offset.x)
                val top = Math.addExact(retained.bounds.top, offset.y)
                child.bounds =
                    IntRect(
                        left,
                        top,
                        Math.addExact(left, child.measuredSize.width),
                        Math.addExact(top, child.measuredSize.height),
                    )
                child.placed = true
                layoutEntry(child)
            }
        }
    }

    private fun <D : Any> resolveParentData(
        child: RetainedEntry,
        key: ParentDataKey<D>,
    ): D? {
        var current: RetainedEntry = child
        var matchingProvider: ParentDataModifierNode<*>? = null
        while (current is RetainedModifier) {
            val provider = current.modifierNode as? ParentDataModifierNode<*>
            if (provider != null && provider.parentDataKey === key) {
                matchingProvider = provider
            }
            current = current.effectiveChildAt(0)
        }
        // Scan the complete chain before reading data so an outer provider is never invoked when shadowed.
        return matchingProvider?.let { provider -> key.castErased(provider.parentData()) }
    }

    private fun pendingMeasure(retained: RetainedEntry): Boolean {
        if (DirtyPhase.Measure in retained.dirty) {
            return true
        }
        for (index in 0 until retained.effectiveChildCount) {
            if (retained.measuredChildren.contains(index) && pendingMeasure(retained.effectiveChildAt(index))) {
                return true
            }
        }
        return false
    }

    private fun pendingLayout(retained: RetainedEntry): Boolean {
        if (DirtyPhase.Layout in retained.dirty) {
            return true
        }
        for (index in 0 until retained.effectiveChildCount) {
            val child = retained.effectiveChildAt(index)
            if (child.placed && pendingLayout(child)) {
                return true
            }
        }
        return false
    }
}
