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
 * @param sourceRegion nonempty source rectangle retained by value.
 * @param destinationSize exact logical component extent.
 * @param modifier active behavior applied to the component.
 * @param key optional stable sibling identity.
 */
private class MinecraftImageElement private constructor(
    private val image: DrawImage,
    private val sourceRegion: IntRect,
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
     * @param sourceRegion initial source rectangle.
     * @param destinationSize initial exact logical extent.
     */
    private class Node(
        private var image: DrawImage,
        private var sourceRegion: IntRect,
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
                sourceRegion,
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
            val imageChanged = image != element.image || sourceRegion != element.sourceRegion
            destinationSize = element.destinationSize
            image = element.image
            sourceRegion = element.sourceRegion
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
                    require(0 < element.sourceRegion.width && 0 < element.sourceRegion.height) {
                        "Minecraft Image source region must be nonempty."
                    }
                    require(
                        0 <= element.sourceRegion.left &&
                            0 <= element.sourceRegion.top &&
                            element.sourceRegion.right <= element.image.size.width &&
                            element.sourceRegion.bottom <= element.image.size.height,
                    ) {
                        "Minecraft Image source region must be contained by the source image."
                    }
                    require(0 < element.destinationSize.width && 0 < element.destinationSize.height) {
                        "Minecraft Image destination dimensions must be positive."
                    }
                },
                createNode = { element -> Node(element.image, element.sourceRegion, element.destinationSize) },
                updateNode = { _, current, node -> node.update(current) },
            )

        /**
         * Creates one private image description.
         *
         * @param image immutable source pixels.
         * @param sourceRegion nonempty contained source rectangle.
         * @param destinationSize exact logical component size.
         * @param modifier active component behavior.
         * @param key optional stable sibling identity.
         * @return one retained image description.
         */
        @JvmSynthetic
        internal fun create(
            image: DrawImage,
            sourceRegion: IntRect,
            destinationSize: IntSize,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftImageElement(image, sourceRegion, destinationSize, modifier, key)
    }
}

/**
 * Creates one internal image description through its private retained implementation.
 *
 * @param image immutable source pixels.
 * @param sourceRegion nonempty contained source rectangle.
 * @param destinationSize exact logical component size.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 * @return one retained image description.
 */
@JvmSynthetic
internal fun createMinecraftImageElement(
    image: DrawImage,
    sourceRegion: IntRect,
    destinationSize: IntSize,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftImageElement.create(image, sourceRegion, destinationSize, modifier, key)
