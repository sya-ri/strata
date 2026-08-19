package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry

/**
 * Executes retained measure and layout work and delegates other pipeline stages.
 */
internal class Pipeline(
    private val threadGuard: ThreadGuard,
) {
    private val paintPipeline = PaintPipeline(threadGuard)
    private val inputPipeline = InputPipeline()
    private val semanticsPipeline = SemanticsPipeline(threadGuard)

    /**
     * Measures [root] under [constraints].
     *
     * @param root the installed retained root.
     * @param constraints the root constraints.
     * @return the measured root size.
     */
    fun measure(
        root: RetainedNode,
        constraints: Constraints,
    ): IntSize = measureNode(root, constraints)

    /**
     * Lays out [root] after its measure pass.
     *
     * @param root the installed retained root.
     */
    fun layout(root: RetainedNode) {
        root.bounds = IntRect(0, 0, root.measuredSize.width, root.measuredSize.height)
        root.placed = true
        layoutNode(root)
    }

    /**
     * Paints [root] in parent-before-child order.
     *
     * @param root the installed laid-out root.
     * @return commands in accumulated tree coordinates.
     */
    fun paint(root: RetainedNode): List<DrawCommand> = paintPipeline.paint(root)

    /**
     * Dispatches [event] in reverse paint order.
     *
     * @param root the installed laid-out root.
     * @param event the tree-coordinate event.
     * @return consumed when a node handles the event, otherwise ignored.
     */
    fun dispatch(
        root: RetainedNode,
        event: PointerEvent,
    ): InputResult = inputPipeline.dispatch(root, event)

    /**
     * Collects semantics from [root] in parent-before-child order.
     *
     * @param root the installed laid-out root.
     * @return immutable tree-coordinate entries.
     */
    fun semantics(root: RetainedNode): List<SemanticsEntry> = semanticsPipeline.semantics(root)

    /**
     * Returns whether a measured subtree still has pending measurement work.
     *
     * @param root the installed root.
     * @return true when reachable measured geometry is stale.
     */
    fun hasPendingMeasure(root: RetainedNode): Boolean = pendingMeasure(root)

    /**
     * Returns whether a measured subtree still has pending geometry work.
     *
     * @param root the installed root.
     * @return true when measure or layout work remains.
     */
    fun hasPendingGeometry(root: RetainedNode): Boolean = pendingMeasure(root) || pendingLayout(root)

    private fun pendingMeasure(retained: RetainedNode): Boolean {
        if (DirtyPhase.Measure in retained.dirty) {
            return true
        }
        retained.children.forEachIndexed { index, child ->
            if (retained.measuredChildren.contains(index) && pendingMeasure(child)) {
                return true
            }
        }
        return false
    }

    private fun pendingLayout(retained: RetainedNode): Boolean {
        if (DirtyPhase.Layout in retained.dirty) {
            return true
        }
        retained.children.forEach { child ->
            if (child.placed && pendingLayout(child)) {
                return true
            }
        }
        return false
    }

    private fun measureNode(
        retained: RetainedNode,
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
                            return retained.children.size
                        }

                    override fun measureChild(
                        index: Int,
                        constraints: Constraints,
                    ): IntSize {
                        guard.check()
                        require(index in 0 until childCount) { "Child index is outside the measurement scope." }
                        check(measuredChildren.add(index)) { "A child may be measured only once per pass." }
                        return measureNode(retained.children[index], constraints)
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

    private fun layoutNode(retained: RetainedNode) {
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
                                return retained.children.size
                            }

                        override fun measuredChildSize(index: Int): IntSize {
                            guard.check()
                            require(index in 0 until childCount) { "Child index is outside the layout scope." }
                            check(index in retained.measuredChildren) { "The child was not measured in this pass." }
                            return retained.children[index].measuredSize
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
        retained.children.forEachIndexed { index, child ->
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
                layoutNode(child)
            }
        }
    }
}
