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
 * Internal immutable description for one layered Minecraft player head.
 *
 * @param skin immutable complete 64 by 64 skin snapshot.
 * @param size positive logical square extent.
 * @param showHat whether the outer layer follows the base face.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 */
private class MinecraftPlayerHeadElement private constructor(
    @get:JvmSynthetic internal val skin: DrawImage,
    @get:JvmSynthetic internal val size: Int,
    @get:JvmSynthetic internal val showHat: Boolean,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Retained node that measures one square and emits face then optional hat blits.
     *
     * @param skin initial immutable skin pixels.
     * @param size initial logical square extent.
     * @param showHat initial outer-layer policy.
     */
    private class Node(
        private var skin: DrawImage,
        private var size: Int,
        private var showHat: Boolean,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            val measured = IntSize(size, size)
            require(constraints.isSatisfiedBy(measured)) { "Minecraft PlayerHead constraints must contain its requested size." }
            return measured
        }

        override fun paint(scope: PaintScope) {
            val destination = IntRect(0, 0, size, size)
            scope.blitImage(skin, faceSource, destination)
            if (showHat) {
                scope.blitImage(skin, hatSource, destination)
            }
        }

        /**
         * Reconciles retained skin, size, and layer policy.
         *
         * @param element incoming immutable description.
         * @return Measure for size changes, Paint for skin or hat changes, or no dirty phase for equality.
         */
        fun update(element: MinecraftPlayerHeadElement): DirtyMask {
            val sizeChanged = size != element.size
            val paintChanged = skin != element.skin || showHat != element.showHat
            skin = element.skin
            size = element.size
            showHat = element.showHat
            return when {
                sizeChanged -> DirtyMask.of(DirtyPhase.Measure)
                paintChanged -> DirtyMask.of(DirtyPhase.Paint)
                else -> DirtyMask.None
            }
        }

        /**
         * Owns the two source regions consumed exclusively by retained painting.
         */
        companion object {
            private val faceSource = IntRect(8, 8, 16, 16)
            private val hatSource = IntRect(40, 8, 48, 16)
        }
    }

    /**
     * Owns the private element token and construction bridge.
     */
    companion object {
        private val skinSize = IntSize(64, 64)
        private val TYPE: ElementType<MinecraftPlayerHeadElement, Node> =
            ElementType(
                elementClass = MinecraftPlayerHeadElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.skin.size == skinSize) { "Minecraft PlayerHead requires an exact 64 by 64 skin." }
                    require(0 < element.size) { "Minecraft PlayerHead size must be positive." }
                },
                createNode = { element -> Node(element.skin, element.size, element.showHat) },
                updateNode = { _, current, node -> node.update(current) },
            )

        /**
         * Creates one private player-head description.
         *
         * @param skin immutable complete skin pixels.
         * @param size positive logical square extent.
         * @param showHat whether the outer layer is painted.
         * @param modifier active component behavior.
         * @param key optional stable sibling identity.
         * @return one retained player-head description.
         */
        @JvmSynthetic
        internal fun create(
            skin: DrawImage,
            size: Int,
            showHat: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftPlayerHeadElement(skin, size, showHat, modifier, key)
    }
}

/**
 * Creates one internal player-head description through its private retained implementation.
 *
 * @param skin immutable complete skin pixels.
 * @param size positive logical square extent.
 * @param showHat whether the outer layer is painted.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 * @return one retained player-head description.
 */
@JvmSynthetic
internal fun createMinecraftPlayerHeadElement(
    skin: DrawImage,
    size: Int,
    showHat: Boolean,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftPlayerHeadElement.create(skin, size, showHat, modifier, key)
