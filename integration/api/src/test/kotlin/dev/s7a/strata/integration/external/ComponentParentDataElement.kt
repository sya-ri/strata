package dev.s7a.strata.integration.external

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask

/**
 * Adversarial component description whose node also exposes a modifier-only parent-data capability.
 *
 * @property parentDataProbe the observations owned by the parent-data integration test.
 * @param children the immutable direct-child descriptions owned by this component.
 * @param modifier the active modifier chain applied around this component.
 */
public class ComponentParentDataElement public constructor(
    private val parentDataProbe: ParentDataProbe,
    children: List<Element> = emptyList(),
    modifier: Modifier = Modifier.Empty,
) : Element(
        identity = ElementIdentity.Positional,
        type = TYPE,
        children = children,
        modifier = modifier,
    ) {
    /**
     * Stable token and typed hooks for the adversarial component description.
     */
    public companion object {
        /**
         * Stable singleton token for [ComponentParentDataElement].
         */
        public val TYPE: ElementType<ComponentParentDataElement, ComponentParentDataNode> =
            ElementType(
                elementClass = ComponentParentDataElement::class,
                nodeClass = ComponentParentDataNode::class,
                validateLocal = { },
                createNode = { element -> ComponentParentDataNode(element.parentDataProbe) },
                updateNode = { _, _, _ -> DirtyMask.None },
            )
    }
}
