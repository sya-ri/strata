package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope

/**
 * Internal implementation of the background modifier.
 */
internal object BackgroundModifier {
    /**
     * Immutable background color description.
     *
     * @property color the local fill color.
     */
    internal data class Element(
        val color: ArgbColor,
    ) : ModifierElement {
        /**
         * The stable background modifier token.
         */
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained node that emits a local fill before its virtual child is painted.
     *
     * @param color the initial local fill color.
     */
    internal class Node(
        private var color: ArgbColor,
    ) : ModifierNode(),
        PaintNode {
        /**
         * Fills the complete local modifier bounds before descendant content.
         *
         * @param scope the local paint command scope.
         */
        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color)
        }

        /**
         * Replaces the retained color after a typed description update.
         *
         * @param element the incoming background description.
         * @return [DirtyPhase.Paint] when the color changed, otherwise no dirty phase.
         */
        internal fun update(element: Element): DirtyMask {
            val changed = color != element.color
            color = element.color
            return if (changed) DirtyMask.of(DirtyPhase.Paint) else DirtyMask.None
        }
    }

    /**
     * Stable token shared by every background modifier description.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { _ -> },
            createNode = { element -> Node(element.color) },
            updateNode = { _, current, node -> node.update(current) },
        )
}
