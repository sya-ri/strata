package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode

/**
 * Internal implementation of the size constraint modifier family.
 */
internal object SizeModifier {
    /**
     * Immutable size policy description shared by all size extension functions.
     *
     * @property width the width resolution policy.
     * @property height the height resolution policy.
     */
    internal data class Element(
        val width: AxisConstraint,
        val height: AxisConstraint,
    ) : ModifierElement {
        /**
         * The stable size modifier token.
         */
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained size node that resolves both child axes from its incoming constraints.
     *
     * @param width the initial width resolution policy.
     * @param height the initial height resolution policy.
     */
    internal class Node(
        private var width: AxisConstraint,
        private var height: AxisConstraint,
    ) : ModifierNode() {
        /**
         * Measures exactly one virtual child under the resolved width and height ranges.
         *
         * @param scope the one-child modifier measurement scope.
         * @param constraints the incoming parent constraints.
         * @return the child size returned by the resolved constraints.
         */
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(scope.childCount == 1) { "A size modifier must have exactly one virtual child." }
            val resolvedWidth = width.resolve(constraints.minWidth, constraints.maxWidth)
            val resolvedHeight = height.resolve(constraints.minHeight, constraints.maxHeight)
            return scope.measureChild(
                0,
                Constraints(
                    minWidth = resolvedWidth.min,
                    maxWidth = resolvedWidth.max,
                    minHeight = resolvedHeight.min,
                    maxHeight = resolvedHeight.max,
                ),
            )
        }

        /**
         * Replaces the retained axis policies after a typed description update.
         *
         * @param element the incoming size description.
         * @return the phases affected by the policy change.
         */
        internal fun update(element: Element): DirtyMask {
            val changed = width != element.width || height != element.height
            width = element.width
            height = element.height
            return if (changed) DirtyMask.of(DirtyPhase.Measure) else DirtyMask.None
        }
    }

    /**
     * Stable token shared by every size modifier description.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { _ -> },
            createNode = { element -> Node(element.width, element.height) },
            updateNode = { _, current, node -> node.update(current) },
        )
}
