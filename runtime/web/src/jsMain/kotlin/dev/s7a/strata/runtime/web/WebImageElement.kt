package dev.s7a.strata.runtime.web

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.FloatRect
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
 * Immutable image region with an optional hat layer for normalized skins.
 */
internal class WebImageElement(
    val image: DrawImage,
    val region: IntRect,
    val size: IntSize,
    val hat: Boolean,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional, TYPE, modifier = modifier) {
    init {
        require(0 < region.width && 0 < region.height && 0 <= region.left && 0 <= region.top && region.right <= image.size.width && region.bottom <= image.size.height)
        require(0 < size.width && 0 < size.height)
    }

    /**
     * Retains only the current source and requested geometry.
     */
    private class Node(
        private var element: WebImageElement,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.isSatisfiedBy(element.size)) { "Web image constraints must contain its requested size." }
            return element.size
        }

        override fun paint(scope: PaintScope) {
            val region = element.region
            val target = FloatRect(0f, 0f, scope.size.width.toFloat(), scope.size.height.toFloat())
            scope.sampledImage(element.image, FloatRect(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat()), target, alphaCutoff = 0f)
            if (element.hat) scope.sampledImage(element.image, FloatRect(40f, 8f, 48f, 16f), target, alphaCutoff = 0f)
        }

        /**
         * Applies the current source without retaining historical pixels.
         */
        fun update(current: WebImageElement): DirtyMask {
            val previous = element
            element = current
            return when {
                previous.size != current.size -> DirtyMask.of(DirtyPhase.Measure)
                previous.image !== current.image || previous.region != current.region || previous.hat != current.hat -> DirtyMask.of(DirtyPhase.Paint)
                else -> DirtyMask.None
            }
        }
    }

    private companion object {
        val TYPE = ElementType(WebImageElement::class, Node::class, { _ -> }, ::Node, { _, current, node -> node.update(current) })
    }
}
