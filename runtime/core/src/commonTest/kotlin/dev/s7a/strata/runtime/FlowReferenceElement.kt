package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier

/**
 * Public-SPI retained adapter for the complete frozen original FlowRow node.
 */
internal class FlowReferenceElement(
    val horizontalSpacing: Int,
    val verticalSpacing: Int,
    val horizontalArrangement: Arrangement,
    val verticalAlignment: VerticalAlignment,
    children: List<Element>,
    key: ElementKey<*>? = null,
) : Element(key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional, TYPE, children = children, modifier = Modifier.Empty) {
    private companion object {
        val TYPE = ElementType(
            elementClass = FlowReferenceElement::class,
            nodeClass = FlowReferenceNode::class,
            validateLocal = { element -> require(0 <= element.horizontalSpacing && 0 <= element.verticalSpacing) },
            createNode = { element -> FlowReferenceNode(element.horizontalSpacing, element.verticalSpacing, element.horizontalArrangement, element.verticalAlignment) },
            updateNode = { previous, current, node -> node.update(previous, current) },
        )
    }
}
