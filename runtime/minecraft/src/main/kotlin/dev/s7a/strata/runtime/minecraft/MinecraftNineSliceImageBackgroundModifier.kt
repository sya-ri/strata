package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope

/**
 * Internal active modifier that paints one immutable image as a Minecraft-compatible nine-slice background.
 */
private object MinecraftNineSliceImageBackgroundModifier {
    /**
     * Immutable nine-slice description.
     *
     * @property image immutable source pixels.
     * @property border source border widths retained by the mapping.
     * @property centerMode typed mapping for every expandable edge and center segment.
     */
    data class Element(
        val image: DrawImage,
        val border: Insets,
        val centerMode: NineSliceCenterMode,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained paint node that emits Minecraft's dimension-specialized or full nine-slice order.
     *
     * @param image initial immutable source pixels.
     * @param border initial source borders.
     * @param centerMode initial inner-segment mapping.
     */
    class Node(
        private var image: DrawImage,
        private var border: Insets,
        private var centerMode: NineSliceCenterMode,
    ) : ModifierNode(),
        PaintNode {
        override fun paint(scope: PaintScope) {
            paintMinecraftNineSlice(scope, image, border, centerMode)
        }

        /**
         * Replaces retained image mapping values after reconciliation.
         *
         * @param element incoming immutable description.
         * @return Paint when pixels or mapping changed, otherwise no dirty phase.
         */
        fun update(element: Element): DirtyMask {
            val changed = image != element.image || border != element.border || centerMode != element.centerMode
            image = element.image
            border = element.border
            centerMode = element.centerMode
            return if (changed) DirtyMask.of(DirtyPhase.Paint) else DirtyMask.None
        }
    }

    /**
     * Stable modifier-node token shared by every nine-slice image background.
     */
    val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { element ->
                require(Math.addExact(element.border.left, element.border.right) < element.image.size.width) {
                    "Minecraft nine-slice horizontal borders must leave a nonempty source center."
                }
                require(Math.addExact(element.border.top, element.border.bottom) < element.image.size.height) {
                    "Minecraft nine-slice vertical borders must leave a nonempty source center."
                }
            },
            createNode = { element -> Node(element.image, element.border, element.centerMode) },
            updateNode = { _, current, node -> node.update(current) },
        )

    /**
     * Creates one immutable nine-slice modifier description.
     *
     * @param image immutable source pixels.
     * @param border source border widths.
     * @param centerMode typed inner-segment mapping.
     * @return one active paint modifier.
     */
    fun element(
        image: DrawImage,
        border: Insets,
        centerMode: NineSliceCenterMode,
    ): ModifierElement = Element(image, border, centerMode)
}

/**
 * Creates one internal nine-slice image-background description.
 *
 * @param image immutable source pixels.
 * @param border source border widths.
 * @param centerMode typed inner-segment mapping.
 * @return one active paint modifier.
 */
@JvmSynthetic
internal fun createMinecraftNineSliceImageBackgroundModifier(
    image: DrawImage,
    border: Insets,
    centerMode: NineSliceCenterMode,
): ModifierElement = MinecraftNineSliceImageBackgroundModifier.element(image, border, centerMode)
