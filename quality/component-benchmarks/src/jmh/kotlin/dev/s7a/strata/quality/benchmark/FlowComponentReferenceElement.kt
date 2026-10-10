package dev.s7a.strata.quality.benchmark

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
internal class FlowComponentReferenceElement(
    val horizontalSpacing: Int,
    val verticalSpacing: Int,
    val horizontalArrangement: Arrangement,
    val verticalAlignment: VerticalAlignment,
    children: List<Element>,
    key: ElementKey<*>? = null,
    modifier: Modifier = Modifier.Empty,
) : Element(key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional, TYPE, children = children, modifier = modifier) {
    private companion object {
        val TYPE =
            ElementType(
                elementClass = FlowComponentReferenceElement::class,
                nodeClass = FlowComponentReferenceNode::class,
                validateLocal = { element -> require(0 <= element.horizontalSpacing && 0 <= element.verticalSpacing) },
                createNode = { element -> FlowComponentReferenceNode(element.horizontalSpacing, element.verticalSpacing, element.horizontalArrangement, element.verticalAlignment) },
                updateNode = { previous, current, node -> node.update(previous, current) },
            )
    }
}
