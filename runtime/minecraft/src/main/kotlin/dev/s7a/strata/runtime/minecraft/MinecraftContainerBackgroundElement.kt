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
 * Internal immutable description for the 26.2 generic chest-container background.
 *
 * The element retains one immutable 256 by 256 texture and a validated row count.
 * Its two checked blits reproduce `ContainerScreen.extractBackground` without inspecting a platform component type.
 *
 * @param image immutable generic-container texture.
 * @param rows chest row count from one through six.
 * @param modifier active behavior applied to the component.
 * @param key optional stable identity among direct siblings.
 */
private class MinecraftContainerBackgroundElement private constructor(
    @get:JvmSynthetic
    internal val image: DrawImage,
    @get:JvmSynthetic
    internal val rows: Int,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Retained fixed-size node that emits the native upper and lower generic-container blits.
     */
    private class Node(
        var image: DrawImage,
        var rows: Int,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode {
        private val width = 176
        private val lowerSourceTop = 126
        private val lowerHeight = 96

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            val size = naturalSize(rows)
            require(constraints.isSatisfiedBy(size)) {
                "Minecraft ContainerBackground constraints must contain its exact natural size."
            }
            return size
        }

        override fun paint(scope: PaintScope) {
            val upperHeight = Math.addExact(Math.multiplyExact(rows, 18), 17)
            scope.blitImage(
                image,
                IntRect(0, 0, width, upperHeight),
                IntRect(0, 0, width, upperHeight),
            )
            scope.blitImage(
                image,
                IntRect(0, lowerSourceTop, width, Math.addExact(lowerSourceTop, lowerHeight)),
                IntRect(0, upperHeight, width, Math.addExact(upperHeight, lowerHeight)),
            )
        }
    }

    /**
     * Owns the private element type token and constructor-only factory.
     */
    companion object {
        private val textureSize = IntSize(256, 256)
        private val supportedRows = 1..6
        private val TYPE: ElementType<MinecraftContainerBackgroundElement, Node> =
            ElementType(
                elementClass = MinecraftContainerBackgroundElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.image.size == textureSize) {
                        "Minecraft ContainerBackground image must be 256 by 256 pixels."
                    }
                    require(element.rows in supportedRows) {
                        "Minecraft ContainerBackground rows must be from one through six."
                    }
                },
                createNode = { element -> Node(element.image, element.rows) },
                updateNode = { previous, current, node ->
                    val imageChanged = previous.image != current.image
                    val rowsChanged = previous.rows != current.rows
                    node.image = current.image
                    node.rows = current.rows
                    when {
                        rowsChanged -> DirtyMask.of(DirtyPhase.Measure)
                        imageChanged -> DirtyMask.of(DirtyPhase.Paint)
                        else -> DirtyMask.None
                    }
                },
            )

        /**
         * Creates one private generic-container background description.
         *
         * @param image immutable 256 by 256 profile image.
         * @param rows chest row count from one through six.
         * @param modifier active component behavior.
         * @param key optional stable sibling identity.
         * @return one fixed-size background element.
         */
        @JvmSynthetic
        internal fun create(
            image: DrawImage,
            rows: Int,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftContainerBackgroundElement(image, rows, modifier, key)

        private fun naturalSize(rows: Int): IntSize = IntSize(176, Math.addExact(114, Math.multiplyExact(rows, 18)))
    }
}

/**
 * Creates one internal generic-container background through the private retained implementation.
 *
 * @param image immutable 256 by 256 profile image.
 * @param rows chest row count from one through six.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 * @return one fixed-size background element.
 */
@JvmSynthetic
internal fun createMinecraftContainerBackgroundElement(
    image: DrawImage,
    rows: Int,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftContainerBackgroundElement.create(image, rows, modifier, key)
