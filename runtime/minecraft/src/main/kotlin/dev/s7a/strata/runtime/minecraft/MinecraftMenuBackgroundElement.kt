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
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal immutable description for the profile-backed menu-background tiling policy.
 *
 * @param image immutable 16 by 16 menu texture retained without a pixel copy.
 * @param modifier active behavior applied to the component.
 * @param key optional stable identity among direct siblings.
 */
private class MinecraftMenuBackgroundElement private constructor(
    private val image: DrawImage,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Retained menu-background node with bounded fill measurement and row-major 32-pixel tiling.
     */
    private class Node(
        var image: DrawImage,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode {
        private val source = IntRect(0, 0, 16, 16)
        private val tile = IntSize(32, 32)

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.maxWidth != Int.MAX_VALUE) { "Menu background width must be bounded." }
            require(constraints.maxHeight != Int.MAX_VALUE) { "Menu background height must be bounded." }
            return IntSize(constraints.maxWidth, constraints.maxHeight)
        }

        override fun paint(scope: PaintScope) {
            if (scope.size.width == 0 || scope.size.height == 0) return
            checkFinalTileEdges(scope.size)
            var top = 0
            while (top < scope.size.height) {
                var left = 0
                while (left < scope.size.width) {
                    scope.blitImage(
                        image,
                        source,
                        IntRect(
                            left,
                            top,
                            Math.addExact(left, tile.width),
                            Math.addExact(top, tile.height),
                        ),
                    )
                    left = Math.addExact(left, tile.width)
                }
                top = Math.addExact(top, tile.height)
            }
        }

        private fun checkFinalTileEdges(size: IntSize) {
            val finalLeft = Math.multiplyExact((size.width - 1) / tile.width, tile.width)
            val finalTop = Math.multiplyExact((size.height - 1) / tile.height, tile.height)
            Math.addExact(finalLeft, tile.width)
            Math.addExact(finalTop, tile.height)
        }
    }

    /**
     * Owns the private element type token and constructor-only factory.
     */
    companion object {
        private val TYPE: ElementType<MinecraftMenuBackgroundElement, Node> =
            ElementType(
                elementClass = MinecraftMenuBackgroundElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.image.size == IntSize(16, 16)) {
                        "Menu background image must be 16 by 16 pixels."
                    }
                },
                createNode = { element -> Node(element.image) },
                updateNode = { previous, current, node ->
                    if (previous.image == current.image) {
                        DirtyMask.None
                    } else {
                        node.image = current.image
                        DirtyMask.of(DirtyPhase.Paint)
                    }
                },
            )

        /**
         * Creates one immutable menu-background description without exposing its constructor to Java.
         *
         * @param image immutable 16 by 16 profile image.
         * @param modifier active component behavior.
         * @param key optional stable sibling identity.
         * @return a profile-backed menu-background element.
         */
        @JvmSynthetic
        internal fun create(
            image: DrawImage,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftMenuBackgroundElement(image, modifier, key)
    }
}

/**
 * Creates one internal menu-background description through the private retained implementation.
 *
 * @param image immutable 16 by 16 profile image.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 * @return a profile-backed menu-background element.
 */
@JvmSynthetic
internal fun createMinecraftMenuBackgroundElement(
    image: DrawImage,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftMenuBackgroundElement.create(image, modifier, key)
