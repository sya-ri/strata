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
 * Internal immutable description for one nearest-sampled image component.
 *
 * @param image immutable source pixels retained without a copy.
 * @param destinationSize exact logical component extent.
 * @param modifier active behavior applied to the component.
 * @param key optional stable sibling identity.
 */
private class MinecraftImageElement private constructor(
    private val image: DrawImage,
    private val destinationSize: IntSize,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Retained image node that measures exactly and emits one complete-source blit.
     *
     * @param image initial immutable source pixels.
     * @param destinationSize initial exact logical extent.
     */
    private class Node(
        private var image: DrawImage,
        private var destinationSize: IntSize,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.isSatisfiedBy(destinationSize)) {
                "Minecraft Image constraints must contain its requested size."
            }
            return destinationSize
        }

        override fun paint(scope: PaintScope) {
            if (destinationSize.width == 0 || destinationSize.height == 0) return
            scope.blitImage(
                image,
                IntRect(0, 0, image.size.width, image.size.height),
                IntRect(0, 0, destinationSize.width, destinationSize.height),
            )
        }

        /**
         * Replaces retained image and size values after reconciliation.
         *
         * @param element incoming immutable description.
         * @return Measure for size changes, Paint for pixel-only changes, or no dirty phase for equality.
         */
        fun update(element: MinecraftImageElement): DirtyMask {
            val sizeChanged = destinationSize != element.destinationSize
            val imageChanged = image != element.image
            destinationSize = element.destinationSize
            image = element.image
            return when {
                sizeChanged -> DirtyMask.of(DirtyPhase.Measure)
                imageChanged -> DirtyMask.of(DirtyPhase.Paint)
                else -> DirtyMask.None
            }
        }
    }

    /**
     * Owns the private element token and construction bridge.
     */
    companion object {
        private val TYPE: ElementType<MinecraftImageElement, Node> =
            ElementType(
                elementClass = MinecraftImageElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(0 < element.image.size.width && 0 < element.image.size.height) {
                        "Minecraft Image source dimensions must be positive."
                    }
                },
                createNode = { element -> Node(element.image, element.destinationSize) },
                updateNode = { _, current, node -> node.update(current) },
            )

        /**
         * Creates one private image description.
         *
         * @param image immutable source pixels.
         * @param destinationSize exact logical component size.
         * @param modifier active component behavior.
         * @param key optional stable sibling identity.
         * @return one retained image description.
         */
        @JvmSynthetic
        internal fun create(
            image: DrawImage,
            destinationSize: IntSize,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftImageElement(image, destinationSize, modifier, key)
    }
}

/**
 * Creates one internal image description through its private retained implementation.
 *
 * @param image immutable source pixels.
 * @param destinationSize exact logical component size.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 * @return one retained image description.
 */
@JvmSynthetic
internal fun createMinecraftImageElement(
    image: DrawImage,
    destinationSize: IntSize,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftImageElement.create(image, destinationSize, modifier, key)
