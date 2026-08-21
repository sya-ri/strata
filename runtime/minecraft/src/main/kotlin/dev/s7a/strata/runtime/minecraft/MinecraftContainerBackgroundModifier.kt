package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope

/**
 * Internal active modifier that sizes and paints a Minecraft generic-container panel behind content.
 */
private object MinecraftContainerBackgroundModifier {
    /**
     * Immutable generic-container background description.
     *
     * @property image immutable 256 by 256 profile image retained without a pixel copy.
     * @property rows validated chest row count from one through six.
     */
    data class Element(
        val image: DrawImage,
        val rows: Int,
    ) : ModifierElement {
        /**
         * Stable token shared by every container-background modifier description.
         */
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained modifier node that fixes the virtual child size and paints native panel regions first.
     *
     * @param image initial immutable profile image.
     * @param rows initial chest row count.
     */
    class Node(
        private var image: DrawImage,
        private var rows: Int,
    ) : ModifierNode(),
        PaintNode {
        private val width = 176
        private val lowerSourceTop = 126
        private val lowerHeight = 96

        /**
         * Measures the virtual child at the exact native panel size.
         *
         * @param scope one-child modifier measurement scope.
         * @param constraints incoming parent constraints.
         * @return exact native panel size.
         * @throws IllegalArgumentException when incoming constraints do not contain the native size.
         * @throws ArithmeticException when checked natural-size arithmetic overflows.
         */
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(scope.childCount == 1) { "A container-background modifier must have exactly one virtual child." }
            val size = naturalSize(rows)
            require(constraints.isSatisfiedBy(size)) {
                "Minecraft container-background constraints must contain its exact natural size."
            }
            return scope.measureChild(0, Constraints.fixed(size.width, size.height))
        }

        /**
         * Emits the native upper and lower panel regions before virtual-child content.
         *
         * @param scope local modifier paint scope.
         * @throws ArithmeticException when checked region arithmetic overflows.
         */
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

        /**
         * Replaces the retained image and row count after a typed update.
         *
         * @param element incoming immutable description.
         * @return Measure for row changes, Paint for image-only changes, or no dirty phase for equality.
         */
        @Suppress("unused")
        fun update(element: Element): DirtyMask {
            val imageChanged = image != element.image
            val rowsChanged = rows != element.rows
            image = element.image
            rows = element.rows
            return when {
                rowsChanged -> DirtyMask.of(DirtyPhase.Measure)
                imageChanged -> DirtyMask.of(DirtyPhase.Paint)
                else -> DirtyMask.None
            }
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
                require(element.image.size == IntSize(256, 256)) {
                    "Container background image must be 256 by 256 pixels."
                }
                require(element.rows in 1..6) {
                    "Container background rows must be from one through six."
                }
            },
            createNode = { element -> Node(element.image, element.rows) },
            updateNode = { _, current, node -> node.update(current) },
        )

    /**
     * Creates one immutable description for the caller's modifier chain.
     *
     * @param image immutable profile image retained without copying pixels.
     * @param rows requested container row count retained by value.
     * @return one thread-neutral description validated when its element tree is validated.
     */
    fun element(
        image: DrawImage,
        rows: Int,
    ): ModifierElement = Element(image, rows)

    /**
     * Computes the exact generic-container logical size.
     *
     * @param rows validated container row count.
     * @return native 176-pixel width and row-dependent height.
     * @throws ArithmeticException when checked height arithmetic overflows.
     */
    fun naturalSize(rows: Int): IntSize = IntSize(176, Math.addExact(114, Math.multiplyExact(rows, 18)))
}

/**
 * Creates one internal active generic-container background modifier description.
 *
 * This thread-neutral factory performs no retained mutation and defers local validation to complete-tree validation.
 *
 * @param image immutable 256 by 256 profile image.
 * @param rows chest row count from one through six.
 * @return one typed modifier description retaining the image and row policy.
 */
@JvmSynthetic
internal fun createMinecraftContainerBackgroundModifier(
    image: DrawImage,
    rows: Int,
): ModifierElement = MinecraftContainerBackgroundModifier.element(image, rows)
