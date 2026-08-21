package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope

/**
 * Internal active modifier that paints the selected Minecraft menu texture behind content.
 */
private object MinecraftMenuBackgroundModifier {
    /**
     * Immutable menu-background modifier description.
     *
     * @property image immutable 16 by 16 profile image retained without a pixel copy.
     */
    data class Element(
        val image: DrawImage,
    ) : ModifierElement {
        /**
         * Stable token shared by every menu-background modifier description.
         */
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained paint node that repeats the menu image across its local bounds.
     *
     * @param image initial immutable profile image.
     */
    class Node(
        private var image: DrawImage,
    ) : ModifierNode(),
        PaintNode {
        private val source = IntRect(0, 0, 16, 16)
        private val tile = IntSize(32, 32)

        /**
         * Emits row-major nearest-sampled tiles before the virtual child is painted.
         *
         * @param scope local modifier paint scope.
         * @throws ArithmeticException when a final overflowing tile edge is not representable.
         */
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

        /**
         * Replaces the retained image after a typed description update.
         *
         * @param element incoming immutable description.
         * @return Paint when pixels changed, otherwise no dirty phase.
         */
        @Suppress("unused")
        fun update(element: Element): DirtyMask {
            val changed = image != element.image
            image = element.image
            return if (changed) DirtyMask.of(DirtyPhase.Paint) else DirtyMask.None
        }

        private fun checkFinalTileEdges(size: IntSize) {
            val finalLeft = Math.multiplyExact((size.width - 1) / tile.width, tile.width)
            val finalTop = Math.multiplyExact((size.height - 1) / tile.height, tile.height)
            Math.addExact(finalLeft, tile.width)
            Math.addExact(finalTop, tile.height)
        }
    }

    /**
     * Stable retained modifier token.
     */
    val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { element ->
                require(element.image.size == IntSize(16, 16)) {
                    "Menu background image must be 16 by 16 pixels."
                }
            },
            createNode = { element -> Node(element.image) },
            updateNode = { _, current, node -> node.update(current) },
        )

    /**
     * Creates one immutable description for the caller's modifier chain.
     *
     * @param image immutable profile image retained without copying pixels.
     * @return one thread-neutral description validated when its element tree is validated.
     */
    fun element(image: DrawImage): ModifierElement = Element(image)
}

/**
 * Creates one internal active menu-background modifier description.
 *
 * This thread-neutral factory performs no retained mutation and defers local validation to complete-tree validation.
 *
 * @param image immutable 16 by 16 profile image.
 * @return one typed modifier description retaining the image.
 */
@JvmSynthetic
internal fun createMinecraftMenuBackgroundModifier(image: DrawImage): ModifierElement = MinecraftMenuBackgroundModifier.element(image)
