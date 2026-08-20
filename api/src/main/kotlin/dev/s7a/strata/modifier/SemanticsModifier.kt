package dev.s7a.strata.modifier

import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope

/**
 * Internal implementation of the semantics modifier.
 */
internal object SemanticsModifier {
    /**
     * Immutable unresolved semantics description.
     *
     * @property value the semantics payload emitted before the virtual child.
     */
    internal data class Element(
        val value: Semantics,
    ) : ModifierElement {
        /**
         * The stable semantics modifier token.
         */
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained node that emits exactly one local semantics payload.
     *
     * @param value the initial unresolved semantics payload.
     */
    internal class Node(
        value: Semantics,
    ) : ModifierNode(),
        SemanticsNode {
        private var value: Semantics = value

        /**
         * Emits this modifier's unresolved semantics payload.
         *
         * @param scope the local semantics collector.
         */
        override fun semantics(scope: SemanticsScope) {
            scope.emit(value)
        }

        /**
         * Replaces the retained semantics payload after a typed description update.
         *
         * @param element the incoming semantics description.
         * @return [DirtyPhase.Semantics] when the value changed, otherwise no dirty phase.
         */
        internal fun update(element: Element): DirtyMask {
            val changed = value != element.value
            value = element.value
            return if (changed) DirtyMask.of(DirtyPhase.Semantics) else DirtyMask.None
        }
    }

    /**
     * Stable token shared by every semantics modifier description.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { _ -> },
            createNode = { element -> Node(element.value) },
            updateNode = { _, current, node -> node.update(current) },
        )
}
