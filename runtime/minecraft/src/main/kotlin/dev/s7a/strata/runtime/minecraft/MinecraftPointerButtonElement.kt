package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal immutable description for one profile-backed fixed-size pointer button.
 *
 * @param normalSprite normal unhovered sprite policy.
 * @param highlightedSprite enabled hovered sprite policy.
 * @param disabledSprite disabled sprite policy.
 * @param normalText normal glyph layers retained for enabled states.
 * @param inactiveText inactive glyph layers retained for disabled state.
 * @param label validated literal retained for semantics.
 * @param enabled whether the button uses enabled semantics and hover visuals.
 * @param modifier active behavior applied to the component.
 * @param key optional stable identity among direct siblings.
 */
@OptIn(InternalStrataRuntimeApi::class)
private class MinecraftPointerButtonElement private constructor(
    @get:JvmSynthetic
    internal val normalSprite: MinecraftButtonSpriteSnapshot,
    @get:JvmSynthetic
    internal val highlightedSprite: MinecraftButtonSpriteSnapshot,
    @get:JvmSynthetic
    internal val disabledSprite: MinecraftButtonSpriteSnapshot,
    @get:JvmSynthetic
    internal val normalText: MinecraftTextRun,
    @get:JvmSynthetic
    internal val inactiveText: MinecraftTextRun,
    @get:JvmSynthetic
    internal val label: UiText.Literal,
    @get:JvmSynthetic
    internal val enabled: Boolean,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Retained fixed-size node that paints the nine-slice button, observes hover, and emits semantics.
     */
    private class Node(
        initialNormalSprite: MinecraftButtonSpriteSnapshot,
        initialHighlightedSprite: MinecraftButtonSpriteSnapshot,
        initialDisabledSprite: MinecraftButtonSpriteSnapshot,
        initialNormalText: MinecraftTextRun,
        initialInactiveText: MinecraftTextRun,
        initialLabel: UiText.Literal,
        initialEnabled: Boolean,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        PointerHoverNode,
        SemanticsNode,
        LifecycleNode {
        private val buttonSize = IntSize(150, 20)
        private val centerX = 75
        private val textOriginY = 6
        private var normalSprite: MinecraftButtonSpriteSnapshot? = initialNormalSprite
        private var highlightedSprite: MinecraftButtonSpriteSnapshot? = initialHighlightedSprite
        private var disabledSprite: MinecraftButtonSpriteSnapshot? = initialDisabledSprite
        private var normalText: MinecraftTextRun? = initialNormalText
        private var inactiveText: MinecraftTextRun? = initialInactiveText
        private var label: UiText.Literal? = initialLabel
        private var enabled = initialEnabled
        private var hovered = false
        private var disposed = false

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.isSatisfiedBy(buttonSize)) {
                "Minecraft pointer buttons require constraints that contain 150 by 20."
            }
            return buttonSize
        }

        override fun paint(scope: PaintScope) {
            val currentSprite =
                if (enabled) {
                    if (hovered) checkNotNull(highlightedSprite) else checkNotNull(normalSprite)
                } else {
                    checkNotNull(disabledSprite)
                }
            paintSprite(scope, currentSprite)
            val currentText = if (enabled) checkNotNull(normalText) else checkNotNull(inactiveText)
            val textLeft = Math.subtractExact(centerX, currentText.size.width / 2)
            currentText.paint(scope, textLeft, textOriginY)
        }

        override fun onPointerHover(hovered: Boolean) {
            val next = enabled && hovered
            if (this.hovered != next) {
                this.hovered = next
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(
                Semantics(
                    label = checkNotNull(label),
                    role = SemanticsRole.Button,
                    disabled = enabled.not(),
                ),
            )
        }

        override fun attach() = Unit

        override fun detach() {
            hovered = false
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            normalSprite = null
            highlightedSprite = null
            disabledSprite = null
            normalText = null
            inactiveText = null
            label = null
            hovered = false
        }

        /**
         * Updates retained visual and semantic values from one reconciled element.
         *
         * @param current next immutable button description.
         * @return dirty phases required for the retained update.
         */
        @JvmSynthetic
        internal fun updateFrom(current: MinecraftPointerButtonElement): DirtyMask {
            val spriteChanged =
                normalSprite !== current.normalSprite ||
                    highlightedSprite !== current.highlightedSprite ||
                    disabledSprite !== current.disabledSprite
            val normalTextChanged = checkNotNull(normalText).equivalentTo(current.normalText).not()
            val inactiveTextChanged = checkNotNull(inactiveText).equivalentTo(current.inactiveText).not()
            val labelChanged = label != current.label
            val enabledChanged = enabled != current.enabled
            val wasHovered = hovered
            if (enabledChanged) {
                hovered = false
            }
            normalSprite = current.normalSprite
            highlightedSprite = current.highlightedSprite
            disabledSprite = current.disabledSprite
            normalText = current.normalText
            inactiveText = current.inactiveText
            label = current.label
            enabled = current.enabled
            var dirty = DirtyMask.None
            val paintChanged = spriteChanged || normalTextChanged || inactiveTextChanged
            if (paintChanged || (enabledChanged && wasHovered)) {
                dirty += DirtyMask.of(DirtyPhase.Paint)
            }
            if (labelChanged || enabledChanged) {
                dirty += DirtyMask.of(DirtyPhase.Semantics)
                dirty += DirtyMask.of(DirtyPhase.Paint)
            }
            return dirty
        }

        private fun paintSprite(
            scope: PaintScope,
            sprite: MinecraftButtonSpriteSnapshot,
        ) {
            val border = sprite.border
            val sourceHeight = 20
            scope.blitImage(
                sprite.image,
                IntRect(0, 0, border, sourceHeight),
                IntRect(0, 0, border, sourceHeight),
            )
            val centerSource =
                when (sprite.centerMode) {
                    MinecraftNineSliceCenterMode.Tiled -> IntRect(border, 0, 150 - border, sourceHeight)
                    MinecraftNineSliceCenterMode.Stretched -> IntRect(border, 0, 200 - border, sourceHeight)
                }
            scope.blitImage(
                sprite.image,
                centerSource,
                IntRect(border, 0, 150 - border, sourceHeight),
            )
            scope.blitImage(
                sprite.image,
                IntRect(200 - border, 0, 200, sourceHeight),
                IntRect(150 - border, 0, 150, sourceHeight),
            )
        }
    }

    /**
     * Owns the private element type token and construction entry point.
     */
    companion object {
        private val TYPE: ElementType<MinecraftPointerButtonElement, Node> =
            ElementType(
                elementClass = MinecraftPointerButtonElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.normalText.size.width <= 146) {
                        "Minecraft pointer button labels must fit within 146 logical pixels."
                    }
                    require(element.inactiveText.size == element.normalText.size) {
                        "Minecraft pointer button label layers must have equal natural sizes."
                    }
                },
                createNode = { element ->
                    Node(
                        element.normalSprite,
                        element.highlightedSprite,
                        element.disabledSprite,
                        element.normalText,
                        element.inactiveText,
                        element.label,
                        element.enabled,
                    )
                },
                updateNode = { _, current, node -> node.updateFrom(current) },
            )

        /**
         * Creates one private pointer-button element without exposing a constructor to Java callers.
         *
         * @param normalSprite normal unhovered sprite policy.
         * @param highlightedSprite enabled hovered sprite policy.
         * @param disabledSprite disabled sprite policy.
         * @param normalText enabled label layers.
         * @param inactiveText disabled label layers.
         * @param label validated literal retained for semantics.
         * @param enabled whether the button uses enabled semantics and hover visuals.
         * @param modifier active behavior.
         * @param key optional stable sibling identity.
         * @return a private fixed-size button element.
         */
        @JvmSynthetic
        internal fun create(
            normalSprite: MinecraftButtonSpriteSnapshot,
            highlightedSprite: MinecraftButtonSpriteSnapshot,
            disabledSprite: MinecraftButtonSpriteSnapshot,
            normalText: MinecraftTextRun,
            inactiveText: MinecraftTextRun,
            label: UiText.Literal,
            enabled: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element =
            MinecraftPointerButtonElement(
                normalSprite,
                highlightedSprite,
                disabledSprite,
                normalText,
                inactiveText,
                label,
                enabled,
                modifier,
                key,
            )
    }
}

/**
 * Creates one internal pointer-button description through the private retained implementation.
 *
 * @param normalSprite normal unhovered sprite policy.
 * @param highlightedSprite enabled hovered sprite policy.
 * @param disabledSprite disabled sprite policy.
 * @param normalText enabled label layers.
 * @param inactiveText disabled label layers.
 * @param label validated literal retained for semantics.
 * @param enabled whether the button uses enabled semantics and hover visuals.
 * @param modifier active behavior.
 * @param key optional stable sibling identity.
 * @return a private fixed-size button element.
 */
@JvmSynthetic
internal fun createMinecraftPointerButtonElement(
    normalSprite: MinecraftButtonSpriteSnapshot,
    highlightedSprite: MinecraftButtonSpriteSnapshot,
    disabledSprite: MinecraftButtonSpriteSnapshot,
    normalText: MinecraftTextRun,
    inactiveText: MinecraftTextRun,
    label: UiText.Literal,
    enabled: Boolean,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element =
    MinecraftPointerButtonElement.create(
        normalSprite,
        highlightedSprite,
        disabledSprite,
        normalText,
        inactiveText,
        label,
        enabled,
        modifier,
        key,
    )
