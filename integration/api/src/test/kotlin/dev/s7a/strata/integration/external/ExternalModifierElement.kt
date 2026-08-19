package dev.s7a.strata.integration.external

import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.text.UiText

/**
 * Third-party modifier description implemented only against the public modifier SPI.
 *
 * @property probe the explicit external observation owner.
 * @property color the local paint color.
 * @property label the unresolved local semantics label.
 * @property valid whether typed local validation accepts this description.
 */
public data class ExternalModifierElement(
    public val probe: ExternalProbe,
    public val color: ArgbColor = ArgbColor(0xFFFF00FF.toInt()),
    public val label: UiText = UiText.Literal("modifier"),
    public val valid: Boolean = true,
) : ModifierElement {
    /**
     * The stable modifier node type token.
     */
    override val type: ModifierNodeType<ExternalModifierElement, ExternalModifierNode>
        get() = TYPE

    /**
     * Stable token and typed node bridge for this external modifier.
     */
    public companion object {
        /**
         * Stable singleton token retained by every description instance.
         */
        public val TYPE: ModifierNodeType<ExternalModifierElement, ExternalModifierNode> =
            ModifierNodeType(
                elementClass = ExternalModifierElement::class,
                nodeClass = ExternalModifierNode::class,
                validateLocal = { element -> require(element.valid) },
                createNode = { element ->
                    ExternalModifierNode(element.probe).also { node ->
                        element.probe.modifierNode = node
                        node.color = element.color
                        node.label = element.label
                    }
                },
                updateNode = { previous, current, node ->
                    current.probe.modifierUpdateCalls += 1
                    var mask = DirtyMask.None
                    if (previous.color != current.color) {
                        node.color = current.color
                        mask += DirtyMask.of(DirtyPhase.Paint)
                    }
                    if (previous.label != current.label) {
                        node.label = current.label
                        mask += DirtyMask.of(DirtyPhase.Semantics)
                    }
                    mask
                },
            )
    }
}
