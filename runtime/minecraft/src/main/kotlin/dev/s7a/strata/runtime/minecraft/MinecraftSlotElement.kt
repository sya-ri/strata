package dev.s7a.strata.runtime.minecraft

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
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal immutable description for one 26.2 container Slot visual and hit region.
 *
 * The 18 by 18 component corresponds to Minecraft's one-pixel-expanded hit region around the 16 by 16 item anchor.
 * Its optional child occupies the 16 by 16 inner item region, while the two 24 by 24 highlight assets overflow by three pixels and preserve native back-child-front paint order.
 *
 * @param backHighlight immutable highlight painted behind the optional item child.
 * @param frontHighlight immutable highlight painted over the optional item child.
 * @param highlightable whether pointer hover selects the two highlight layers.
 * @param child optional sole item description.
 * @param modifier active behavior applied to the component.
 * @param key optional stable identity among direct siblings.
 */
private class MinecraftSlotElement private constructor(
    @get:JvmSynthetic
    internal val backHighlight: DrawImage,
    @get:JvmSynthetic
    internal val frontHighlight: DrawImage,
    @get:JvmSynthetic
    internal val highlightable: Boolean,
    child: Element?,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = child?.let(::listOf).orEmpty(),
        modifier = modifier,
    ) {
    /**
     * Retained slot node implementing fixed measure, optional item placement, hover invalidation, and split highlight paint.
     */
    private class Node(
        var backHighlight: DrawImage,
        var frontHighlight: DrawImage,
        var highlightable: Boolean,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        OverlayPaintNode,
        PointerHoverNode {
        private val slotSize = IntSize(18, 18)
        private val itemSize = IntSize(16, 16)
        private val highlightSource = IntRect(0, 0, 24, 24)
        private val highlightDestination = IntRect(-3, -3, 21, 21)
        private var hovered = false

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.isSatisfiedBy(slotSize)) {
                "Minecraft Slot constraints must contain exactly 18 by 18 logical pixels."
            }
            check(scope.childCount <= 1) { "Minecraft Slot retains at most one item child." }
            if (scope.childCount == 1) {
                scope.measureChild(0, Constraints.fixed(itemSize.width, itemSize.height))
            }
            return slotSize
        }

        override fun layout(scope: LayoutScope) {
            check(scope.childCount <= 1) { "Minecraft Slot lays out at most one item child." }
            if (scope.childCount == 1) {
                scope.placeChild(0, IntOffset(1, 1))
            }
        }

        override fun paint(scope: PaintScope) {
            if (hovered) {
                scope.blitImage(backHighlight, highlightSource, highlightDestination)
            }
        }

        override fun paintOverlay(scope: PaintScope) {
            if (hovered) {
                scope.blitImage(frontHighlight, highlightSource, highlightDestination)
            }
        }

        override fun onPointerHover(hovered: Boolean) {
            val next = highlightable && hovered
            if (this.hovered != next) {
                this.hovered = next
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
        }

        /**
         * Updates retained profile images and highlightability from one reconciled description.
         *
         * @param current next immutable Slot description.
         * @return Paint when observable visual output changes, otherwise no dirty phase.
         */
        @Suppress("unused")
        @JvmSynthetic
        internal fun updateFrom(current: MinecraftSlotElement): DirtyMask {
            val imagesChanged = backHighlight != current.backHighlight || frontHighlight != current.frontHighlight
            val highlightabilityChanged = highlightable != current.highlightable
            val wasHovered = hovered
            if (current.highlightable.not()) hovered = false
            backHighlight = current.backHighlight
            frontHighlight = current.frontHighlight
            highlightable = current.highlightable
            return if (imagesChanged || (highlightabilityChanged && wasHovered)) {
                DirtyMask.of(DirtyPhase.Paint)
            } else {
                DirtyMask.None
            }
        }
    }

    /**
     * Owns the private element type token and constructor-only factory.
     */
    companion object {
        private val highlightSize = IntSize(24, 24)
        private val TYPE: ElementType<MinecraftSlotElement, Node> =
            ElementType(
                elementClass = MinecraftSlotElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.backHighlight.size == highlightSize) {
                        "Minecraft Slot back highlight must be 24 by 24 pixels."
                    }
                    require(element.frontHighlight.size == highlightSize) {
                        "Minecraft Slot front highlight must be 24 by 24 pixels."
                    }
                    require(element.children.size <= 1) { "Minecraft Slot accepts at most one item child." }
                },
                createNode = { element -> Node(element.backHighlight, element.frontHighlight, element.highlightable) },
                updateNode = { _, current, node -> node.updateFrom(current) },
            )

        /**
         * Creates one private Slot description.
         *
         * @param backHighlight immutable back-highlight image.
         * @param frontHighlight immutable front-highlight image.
         * @param highlightable whether hover paints the highlight layers.
         * @param child optional sole 16 by 16 item child.
         * @param modifier active component behavior.
         * @param key optional stable sibling identity.
         * @return one fixed-size Slot element.
         */
        @JvmSynthetic
        internal fun create(
            backHighlight: DrawImage,
            frontHighlight: DrawImage,
            highlightable: Boolean,
            child: Element?,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftSlotElement(backHighlight, frontHighlight, highlightable, child, modifier, key)
    }
}

/**
 * Creates one internal Slot description through the private retained implementation.
 *
 * @param backHighlight immutable back-highlight image.
 * @param frontHighlight immutable front-highlight image.
 * @param highlightable whether hover paints the highlight layers.
 * @param child optional sole 16 by 16 item child.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 * @return one fixed-size Slot element.
 */
@JvmSynthetic
internal fun createMinecraftSlotElement(
    backHighlight: DrawImage,
    frontHighlight: DrawImage,
    highlightable: Boolean,
    child: Element?,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftSlotElement.create(backHighlight, frontHighlight, highlightable, child, modifier, key)
