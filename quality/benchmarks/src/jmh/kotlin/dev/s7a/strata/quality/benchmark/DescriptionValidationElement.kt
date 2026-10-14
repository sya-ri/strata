package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase

/**
 * Immutable external primitive with one alternating geometry/paint value and stable membership.
 */
internal class DescriptionValidationElement(
    val index: Int,
    val value: Int,
    identity: ElementIdentity,
    children: List<Element>,
) : Element(identity, TYPE, children) {
    /**
     * Stable token shared by every declared primitive in the fixture.
     */
    companion object {
        private val TYPE =
            ElementType(
                elementClass = DescriptionValidationElement::class,
                nodeClass = DescriptionValidationNode::class,
                validateLocal = { element -> require(element.value in 0..1) },
                createNode = { element -> DescriptionValidationNode(element.index, element.value) },
                updateNode = { previous, current, node ->
                    if (previous.value == current.value) {
                        DirtyMask.None
                    } else {
                        node.value = current.value
                        DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint, DirtyPhase.Semantics)
                    }
                },
            )
    }
}
