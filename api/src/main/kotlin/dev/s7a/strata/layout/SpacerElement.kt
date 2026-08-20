package dev.s7a.strata.layout

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal immutable description for an empty spacer.
 *
 * The base [Element] retains the immutable modifier chain.
 * After submission, the runtime owns this description and the retained [Node] created from it.
 *
 * @param key optional stable identity among direct siblings.
 * @param modifier active behavior that supplies the spacer's requested or constrained extent.
 */
internal class SpacerElement(
    key: ElementKey<*>?,
    modifier: Modifier,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Retained spacer node that reports the constrained zero extent.
     */
    internal class Node :
        RetainedNode(),
        MeasureNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize.Zero)
    }

    /**
     * Stable token for the spacer component.
     */
    companion object {
        internal val TYPE: ElementType<SpacerElement, Node> =
            ElementType(
                elementClass = SpacerElement::class,
                nodeClass = Node::class,
                validateLocal = { _ -> },
                createNode = { _ -> Node() },
                updateNode = { _, _, _ -> DirtyMask.None },
            )
    }
}
