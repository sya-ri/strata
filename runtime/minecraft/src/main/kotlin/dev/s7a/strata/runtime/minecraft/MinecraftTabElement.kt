package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Private retained implementation of one externally controlled tab.
 *
 * The element owns a profile-backed button surface, label, optional selected indicator child, hover state, and tab semantics.
 */
private class MinecraftTabElement private constructor(
    internal val normalSprite: MinecraftButtonSpriteSnapshot,
    internal val highlightedSprite: MinecraftButtonSpriteSnapshot,
    internal val disabledSprite: MinecraftButtonSpriteSnapshot,
    internal val normalText: MinecraftTextRun,
    internal val inactiveText: MinecraftTextRun,
    internal val label: UiText,
    internal val width: Int,
    internal val enabled: Boolean,
    internal val selected: Boolean,
    internal val underlined: Boolean,
    customIndicator: Element?,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = listOfNotNull(customIndicator),
        modifier = modifier,
    ) {
    internal val custom: Boolean = customIndicator != null

    /**
     * Retained tab measurement, placement, drawing, hover, lifecycle, and semantics implementation.
     */
    private class Node(
        initial: MinecraftTabElement,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        PointerHoverNode,
        SemanticsNode,
        LifecycleNode {
        private var normalSprite: MinecraftButtonSpriteSnapshot? = initial.normalSprite
        private var highlightedSprite: MinecraftButtonSpriteSnapshot? = initial.highlightedSprite
        private var disabledSprite: MinecraftButtonSpriteSnapshot? = initial.disabledSprite
        private var normalText: MinecraftTextRun? = initial.normalText
        private var inactiveText: MinecraftTextRun? = initial.inactiveText
        private var label: UiText? = initial.label
        private var width: Int = initial.width
        private var enabled: Boolean = initial.enabled
        private var selected: Boolean = initial.selected
        private var underlined: Boolean = initial.underlined
        private var custom: Boolean = initial.custom
        private var hovered: Boolean = false
        private var disposed: Boolean = false

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            if (custom) {
                scope.measureChild(
                    0,
                    Constraints(
                        minWidth = 0,
                        maxWidth = width,
                        minHeight = 0,
                        maxHeight = BUTTON_HEIGHT,
                    ),
                )
            }
            val size = IntSize(width, BUTTON_HEIGHT)
            require(constraints.isSatisfiedBy(size)) {
                "Minecraft Tab constraints must contain its requested width by 20."
            }
            return size
        }

        override fun layout(scope: LayoutScope) {
            if (custom) {
                val childSize = scope.measuredChildSize(0)
                scope.placeChild(
                    0,
                    IntOffset(
                        Math.subtractExact(width, childSize.width) / 2,
                        Math.subtractExact(BUTTON_HEIGHT, childSize.height),
                    ),
                )
            }
        }

        override fun paint(scope: PaintScope) {
            val sprite =
                if (enabled) {
                    if (hovered) checkNotNull(highlightedSprite) else checkNotNull(normalSprite)
                } else {
                    checkNotNull(disabledSprite)
                }
            paintSprite(scope, sprite)
            val text = if (enabled) checkNotNull(normalText) else checkNotNull(inactiveText)
            text.paint(scope, Math.subtractExact(width, text.size.width) / 2, TEXT_ORIGIN_Y)
            if (underlined) {
                val underlineLeft = width / 2 - UNDERLINE_CENTER_OFFSET
                scope.fillRectangle(
                    IntRect(
                        underlineLeft + 1,
                        UNDERLINE_SHADOW_Y,
                        Math.addExact(underlineLeft, UNDERLINE_WIDTH + 1),
                        UNDERLINE_SHADOW_Y + 1,
                    ),
                    UNDERLINE_SHADOW_COLOR,
                )
                scope.fillRectangle(
                    IntRect(underlineLeft, UNDERLINE_Y, Math.addExact(underlineLeft, UNDERLINE_WIDTH), UNDERLINE_Y + 1),
                    UNDERLINE_COLOR,
                )
            }
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
                    role = SemanticsRole.Tab,
                    disabled = enabled.not(),
                    selected = selected,
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
         * Reconciles tab assets, state, geometry, and indicator presentation.
         *
         * @param current incoming immutable description.
         * @return invalidation required by changed measurement, pixels, or semantics.
         */
        @JvmSynthetic
        internal fun updateFrom(current: MinecraftTabElement): DirtyMask {
            val widthChanged = width != current.width
            val customChanged = custom != current.custom
            val labelChanged = label != current.label
            val enabledChanged = enabled != current.enabled
            val selectedChanged = selected != current.selected
            val paintChanged = appearanceChanged(current) || enabledChanged || selectedChanged
            if (enabledChanged) {
                hovered = false
            }
            normalSprite = current.normalSprite
            highlightedSprite = current.highlightedSprite
            disabledSprite = current.disabledSprite
            normalText = current.normalText
            inactiveText = current.inactiveText
            label = current.label
            width = current.width
            enabled = current.enabled
            selected = current.selected
            underlined = current.underlined
            custom = current.custom

            var dirty = DirtyMask.None
            if (widthChanged || customChanged) {
                dirty += DirtyMask.of(DirtyPhase.Measure)
            } else if (paintChanged) {
                dirty += DirtyMask.of(DirtyPhase.Paint)
            }
            if (labelChanged || enabledChanged || selectedChanged) {
                dirty += DirtyMask.of(DirtyPhase.Semantics)
            }
            return dirty
        }

        private fun appearanceChanged(current: MinecraftTabElement): Boolean =
            normalSprite !== current.normalSprite ||
                highlightedSprite !== current.highlightedSprite ||
                disabledSprite !== current.disabledSprite ||
                checkNotNull(normalText).equivalentTo(current.normalText).not() ||
                checkNotNull(inactiveText).equivalentTo(current.inactiveText).not() ||
                underlined != current.underlined

        private fun paintSprite(
            scope: PaintScope,
            sprite: MinecraftButtonSpriteSnapshot,
        ) {
            val border = sprite.border
            scope.blitImage(
                sprite.image,
                IntRect(0, 0, border, BUTTON_HEIGHT),
                IntRect(0, 0, border, BUTTON_HEIGHT),
            )
            val centerSource =
                when (sprite.centerMode) {
                    NineSliceCenterMode.Tiled -> IntRect(border, 0, width - border, BUTTON_HEIGHT)
                    NineSliceCenterMode.Stretched -> IntRect(border, 0, SOURCE_BUTTON_WIDTH - border, BUTTON_HEIGHT)
                }
            scope.blitImage(
                sprite.image,
                centerSource,
                IntRect(border, 0, width - border, BUTTON_HEIGHT),
            )
            scope.blitImage(
                sprite.image,
                IntRect(SOURCE_BUTTON_WIDTH - border, 0, SOURCE_BUTTON_WIDTH, BUTTON_HEIGHT),
                IntRect(width - border, 0, width, BUTTON_HEIGHT),
            )
        }

        private companion object {
            private const val SOURCE_BUTTON_WIDTH = 200
            private const val BUTTON_HEIGHT = 20
            private const val TEXT_ORIGIN_Y = 6
            private const val UNDERLINE_WIDTH = 13
            private const val UNDERLINE_CENTER_OFFSET = 7
            private const val UNDERLINE_Y = 14
            private const val UNDERLINE_SHADOW_Y = 15
            private val UNDERLINE_COLOR = ArgbColor(0xFFFFFFFF.toInt())
            private val UNDERLINE_SHADOW_COLOR = ArgbColor(0xFF3F3F3F.toInt())
        }
    }

    companion object {
        private val TYPE: ElementType<MinecraftTabElement, Node> =
            ElementType(
                elementClass = MinecraftTabElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.underlined.not() || element.custom.not()) {
                        "A Tab cannot use standard and custom indicators together."
                    }
                    require(element.normalText.size.width <= element.width - 4) {
                        "Minecraft Tab labels must fit within two-pixel horizontal margins."
                    }
                    require(element.inactiveText.size == element.normalText.size) {
                        "Minecraft Tab label layers must have equal natural sizes."
                    }
                },
                createNode = { element -> Node(element) },
                updateNode = { _, current, node -> node.updateFrom(current) },
            )

        @JvmSynthetic
        internal fun create(
            normalSprite: MinecraftButtonSpriteSnapshot,
            highlightedSprite: MinecraftButtonSpriteSnapshot,
            disabledSprite: MinecraftButtonSpriteSnapshot,
            normalText: MinecraftTextRun,
            inactiveText: MinecraftTextRun,
            label: UiText,
            width: Int,
            enabled: Boolean,
            selected: Boolean,
            underlined: Boolean,
            customIndicator: Element?,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element =
            MinecraftTabElement(
                normalSprite,
                highlightedSprite,
                disabledSprite,
                normalText,
                inactiveText,
                label,
                width,
                enabled,
                selected,
                underlined,
                customIndicator,
                modifier,
                key,
            )
    }
}

/**
 * Creates one private tab description.
 *
 * @param normalSprite normal unhovered sprite policy.
 * @param highlightedSprite enabled hovered sprite policy.
 * @param disabledSprite disabled sprite policy.
 * @param normalText enabled label layers.
 * @param inactiveText disabled label layers.
 * @param label validated literal retained for semantics.
 * @param width fixed logical tab width.
 * @param enabled whether the tab accepts enabled appearance and semantics.
 * @param selected externally controlled selected state.
 * @param underlined whether the standard selected underline is painted.
 * @param customIndicator optional selected custom indicator root.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 * @return retained tab element.
 */
@JvmSynthetic
internal fun createMinecraftTabElement(
    normalSprite: MinecraftButtonSpriteSnapshot,
    highlightedSprite: MinecraftButtonSpriteSnapshot,
    disabledSprite: MinecraftButtonSpriteSnapshot,
    normalText: MinecraftTextRun,
    inactiveText: MinecraftTextRun,
    label: UiText,
    width: Int,
    enabled: Boolean,
    selected: Boolean,
    underlined: Boolean,
    customIndicator: Element?,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element =
    MinecraftTabElement.create(
        normalSprite,
        highlightedSprite,
        disabledSprite,
        normalText,
        inactiveText,
        label,
        width,
        enabled,
        selected,
        underlined,
        customIndicator,
        modifier,
        key,
    )
