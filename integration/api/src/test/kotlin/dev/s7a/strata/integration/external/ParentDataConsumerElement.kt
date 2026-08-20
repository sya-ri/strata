package dev.s7a.strata.integration.external

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase

/**
 * External component description whose node consumes direct-child parent data.
 *
 * @property probe the external observation owner.
 * @property parentDataKey the referential key read from the direct child.
 * @param key the optional logical identity used by the retained component.
 * @param children the immutable direct-child descriptions exposed to the consumer node.
 * @param modifier the active modifier chain applied around the consumer component.
 */
public class ParentDataConsumerElement public constructor(
    public val probe: ParentDataProbe,
    public val parentDataKey: ParentDataKey<ParentDataValue> = ParentDataModifierElement.KEY,
    key: ElementKey<*>? = null,
    children: List<Element> = emptyList(),
    modifier: Modifier = Modifier.Empty,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = children,
        modifier = modifier,
    ) {
    /**
     * Stable token and typed node bridge for this external component.
     */
    public companion object {
        /**
         * Stable singleton token for [ParentDataConsumerElement].
         */
        public val TYPE: ElementType<ParentDataConsumerElement, ParentDataConsumerNode> =
            ElementType(
                elementClass = ParentDataConsumerElement::class,
                nodeClass = ParentDataConsumerNode::class,
                validateLocal = { },
                createNode = { element ->
                    ParentDataConsumerNode(element.probe).also { node ->
                        node.parentDataKey = element.parentDataKey
                    }
                },
                updateNode = { previous, current, node ->
                    node.parentDataKey = current.parentDataKey
                    if (previous.parentDataKey !== current.parentDataKey) {
                        DirtyMask.of(DirtyPhase.Measure)
                    } else {
                        DirtyMask.None
                    }
                },
            )
    }
}
