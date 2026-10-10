package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.modifier.Modifier

/**
 * Retained public-SPI adapter for the frozen whole original linear node.
 */
internal class LinearReferenceElement(
    val orientation: LinearReferenceNode.Orientation,
    val spacing: Int,
    val arrangement: Arrangement,
    children: List<Element>,
) : Element(ElementIdentity.Positional, TYPE, children = children, modifier = Modifier.Empty) {
    private companion object {
        val TYPE = ElementType(
            elementClass = LinearReferenceElement::class,
            nodeClass = LinearReferenceNode::class,
            validateLocal = { element -> require(0 <= element.spacing) },
            createNode = { element -> LinearReferenceNode(element.orientation, element.spacing, element.arrangement) },
            updateNode = { previous, current, node -> node.update(previous, current) },
        )
    }
}
