package dev.s7a.strata.integration.external

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.text.UiText

/**
 * A third-party element implemented only against the public API contracts.
 *
 * @property width the preferred width.
 * @property color the fill color.
 * @property label unresolved semantics label.
 */
public class ExternalElement public constructor(
    private val probe: ExternalProbe = ExternalProbe(),
    key: ElementKey<*>? = null,
    private val width: Int = 4,
    private val color: ArgbColor = ArgbColor(0xFF00FF00.toInt()),
    private val label: UiText = UiText.Literal("external"),
    private val nodeId: ExternalNodeId = ExternalNodeId.Root,
    children: List<Element> = emptyList(),
    modifier: Modifier = Modifier.Empty,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = children,
        modifier = modifier,
    ) {
    init {
        require(0 <= width) { "Width must be non-negative." }
    }

    /**
     * The stable token and typed hooks for this primitive.
     */
    public companion object {
        /**
         * Stable singleton token for [ExternalElement].
         */
        public val TYPE: ElementType<ExternalElement, ExternalNode> =
            ElementType(
                elementClass = ExternalElement::class,
                nodeClass = ExternalNode::class,
                validateLocal = { element -> require(0 <= element.width) },
                createNode = { element ->
                    ExternalNode(element.probe, element.nodeId).also { node ->
                        node.width = element.width
                        node.color = element.color
                        node.label = element.label
                    }
                },
                updateNode = { previous, current, node ->
                    current.probe.componentUpdateCalls += 1
                    var dirty = DirtyMask.None
                    if (previous.width != current.width) {
                        node.width = current.width
                        dirty += DirtyMask.of(DirtyPhase.Measure)
                    }
                    if (previous.color != current.color) {
                        node.color = current.color
                        dirty += DirtyMask.of(DirtyPhase.Paint)
                    }
                    if (previous.label != current.label) {
                        node.label = current.label
                        dirty += DirtyMask.of(DirtyPhase.Semantics)
                    }
                    dirty
                },
            )
    }
}
