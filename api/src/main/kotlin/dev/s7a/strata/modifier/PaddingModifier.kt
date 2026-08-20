package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode

/**
 * Internal implementation of the padding modifier family.
 */
internal object PaddingModifier {
    /**
     * Immutable padding description.
     *
     * @property insets the checked distances applied around the virtual child.
     */
    internal data class Element(
        val insets: Insets,
    ) : ModifierElement {
        /**
         * The stable padding modifier token.
         */
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained node that measures a child inside reduced constraints and restores the insets around it.
     *
     * @param insets the initial checked distances around the child.
     */
    internal class Node(
        private var insets: Insets,
    ) : ModifierNode() {
        /**
         * Measures the virtual child under constraints reduced by the four insets.
         *
         * @param scope the one-child modifier measurement scope.
         * @param constraints the incoming parent constraints.
         * @return the child size plus checked inset totals, constrained by the parent.
         * @throws ArithmeticException when adding the child size and inset totals overflows.
         */
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(scope.childCount == 1) { "A padding modifier must have exactly one virtual child." }
            val horizontal = Math.addExact(insets.left, insets.right)
            val vertical = Math.addExact(insets.top, insets.bottom)
            val childConstraints =
                Constraints(
                    minWidth = subtractMinimum(constraints.minWidth, horizontal),
                    maxWidth = subtractMaximum(constraints.maxWidth, horizontal),
                    minHeight = subtractMinimum(constraints.minHeight, vertical),
                    maxHeight = subtractMaximum(constraints.maxHeight, vertical),
                )
            val childSize = scope.measureChild(0, childConstraints)
            return constraints.constrain(
                IntSize(
                    width = Math.addExact(childSize.width, horizontal),
                    height = Math.addExact(childSize.height, vertical),
                ),
            )
        }

        /**
         * Places the virtual child at the inset origin.
         *
         * @param scope the one-child modifier layout scope.
         */
        override fun layout(scope: LayoutScope) {
            require(scope.childCount == 1) { "A padding modifier must have exactly one virtual child." }
            scope.placeChild(0, IntOffset(insets.left, insets.top))
        }

        /**
         * Replaces the retained inset value after a typed description update.
         *
         * @param element the incoming padding description.
         * @return [DirtyPhase.Measure] when the insets changed, otherwise no dirty phase.
         */
        internal fun update(element: Element): DirtyMask {
            val changed = insets != element.insets
            insets = element.insets
            return if (changed) DirtyMask.of(DirtyPhase.Measure) else DirtyMask.None
        }

        private fun subtractMinimum(
            value: Int,
            total: Int,
        ): Int = if (total <= value) value - total else 0

        private fun subtractMaximum(
            value: Int,
            total: Int,
        ): Int =
            if (value == Int.MAX_VALUE) {
                value
            } else if (total <= value) {
                value - total
            } else {
                0
            }
    }

    /**
     * Stable token shared by every padding modifier description.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { _ -> },
            createNode = { element -> Node(element.insets) },
            updateNode = { _, current, node -> node.update(current) },
        )
}
