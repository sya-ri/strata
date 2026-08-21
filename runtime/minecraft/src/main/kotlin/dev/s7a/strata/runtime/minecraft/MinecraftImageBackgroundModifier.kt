package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope

/**
 * Internal active modifier that paints arbitrary immutable image pixels behind content.
 */
private object MinecraftImageBackgroundModifier {
    /**
     * Immutable background image description.
     *
     * @property image immutable source pixels.
     * @property scale typed destination mapping.
     */
    data class Element(
        val image: DrawImage,
        val scale: MinecraftImageScale,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained paint node for one image background.
     *
     * @param image initial immutable source pixels.
     * @param scale initial typed mapping.
     */
    class Node(
        private var image: DrawImage,
        private var scale: MinecraftImageScale,
    ) : ModifierNode(),
        PaintNode {
        override fun paint(scope: PaintScope) {
            if (scope.size.width == 0 || scope.size.height == 0) return
            when (scale) {
                MinecraftImageScale.Stretch -> {
                    scope.blitImage(
                        image,
                        IntRect(0, 0, image.size.width, image.size.height),
                        IntRect(0, 0, scope.size.width, scope.size.height),
                    )
                }

                MinecraftImageScale.Tile -> {
                    paintTiles(scope)
                }
            }
        }

        /**
         * Replaces the retained background values after reconciliation.
         *
         * @param element incoming immutable description.
         * @return Paint when pixels or mapping changed, otherwise no dirty phase.
         */
        fun update(element: Element): DirtyMask {
            val changed = image != element.image || scale != element.scale
            image = element.image
            scale = element.scale
            return if (changed) DirtyMask.of(DirtyPhase.Paint) else DirtyMask.None
        }

        private fun paintTiles(scope: PaintScope) {
            var top = 0
            while (top < scope.size.height) {
                var left = 0
                while (left < scope.size.width) {
                    val width = minOf(image.size.width, scope.size.width - left)
                    val height = minOf(image.size.height, scope.size.height - top)
                    scope.blitImage(
                        image,
                        IntRect(0, 0, width, height),
                        IntRect(
                            left,
                            top,
                            Math.addExact(left, width),
                            Math.addExact(top, height),
                        ),
                    )
                    left = Math.addExact(left, image.size.width)
                }
                top = Math.addExact(top, image.size.height)
            }
        }
    }

    /**
     * Stable token shared by every arbitrary image background.
     */
    val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { element ->
                require(0 < element.image.size.width && 0 < element.image.size.height) {
                    "Minecraft image-background source dimensions must be positive."
                }
            },
            createNode = { element -> Node(element.image, element.scale) },
            updateNode = { _, current, node -> node.update(current) },
        )

    /**
     * Creates one immutable modifier description.
     *
     * @param image immutable source pixels.
     * @param scale typed destination mapping.
     * @return one active image-background description.
     */
    fun element(
        image: DrawImage,
        scale: MinecraftImageScale,
    ): ModifierElement = Element(image, scale)
}

/**
 * Creates one internal arbitrary image-background description.
 *
 * @param image immutable source pixels.
 * @param scale typed destination mapping.
 * @return one active paint modifier.
 */
@JvmSynthetic
internal fun createMinecraftImageBackgroundModifier(
    image: DrawImage,
    scale: MinecraftImageScale,
): ModifierElement = MinecraftImageBackgroundModifier.element(image, scale)
