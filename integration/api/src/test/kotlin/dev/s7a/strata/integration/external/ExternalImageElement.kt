package dev.s7a.strata.integration.external

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.DrawImage

/**
 * A third-party image element that uses only the public drawing capability.
 *
 * @param probe the caller-owned lifecycle observation sink.
 * @param image the immutable source image retained by the element description.
 * @param source the nonempty source rectangle in image coordinates.
 * @param destination the nonempty local destination rectangle.
 * @param key the optional typed retained identity.
 * @param nodeId the typed lifecycle identity used by the test sink.
 * @param modifier the active modifier description.
 */
public class ExternalImageElement public constructor(
    private val probe: ExternalProbe = ExternalProbe(),
    private val image: DrawImage,
    private val source: IntRect,
    private val destination: IntRect,
    key: ElementKey<*>? = null,
    private val nodeId: ExternalNodeId = ExternalNodeId.Root,
    modifier: Modifier = Modifier.Empty,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Stable typed hooks for the external image element.
     */
    public companion object {
        /**
         * Stable element type token used by the test-owned external implementation.
         */
        public val TYPE: ElementType<ExternalImageElement, ExternalImageNode> =
            ElementType(
                elementClass = ExternalImageElement::class,
                nodeClass = ExternalImageNode::class,
                validateLocal = { },
                createNode = { element ->
                    ExternalImageNode(element.probe, element.nodeId, element.image, element.source, element.destination)
                },
                updateNode = { previous, current, node ->
                    if (previous.image == current.image && previous.source == current.source &&
                        previous.destination == current.destination
                    ) {
                        DirtyMask.None
                    } else {
                        node.image = current.image
                        node.source = current.source
                        node.destination = current.destination
                        DirtyMask.of(DirtyPhase.Paint)
                    }
                },
            )
    }
}
