package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.Insets
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
 * Active immutable image background with explicit mapping and optional nine-slice borders.
 */
internal data class WebImageBackground(
    val image: DrawImage,
    val scale: ImageScale = ImageScale.Stretch,
    val border: Insets? = null,
    val centerMode: NineSliceCenterMode = NineSliceCenterMode.Tiled,
) : ModifierElement {
    init {
        require(0 < image.size.width && 0 < image.size.height)
        if (border != null) require(border.left.toLong() + border.right < image.size.width && border.top.toLong() + border.bottom < image.size.height) { "Nine-slice borders require a nonempty center." }
    }

    override val type: ModifierNodeType<*, *> get() = TYPE

    /**
     * Paints without changing child geometry; retains only the current immutable description.
     */
    private class Node(
        private var element: WebImageBackground,
    ) : ModifierNode(),
        PaintNode {
        @Suppress("CyclomaticComplexMethod") // Native three-segment and nine-segment mappings share exact edge clamps.
        override fun paint(scope: PaintScope) {
            val image = element.image
            val border = element.border
            if (scope.size.width == 0 || scope.size.height == 0) return
            val source = IntRect(0, 0, image.size.width, image.size.height)
            val destination = IntRect(0, 0, scope.size.width, scope.size.height)
            if (border == null) {
                segment(scope, source, destination, element.scale == ImageScale.Tile)
            } else if (scope.size == image.size) {
                segment(scope, source, destination, false)
            } else {
                val left = minOf(border.left, scope.size.width / 2)
                val right = minOf(border.right, scope.size.width / 2)
                val top = minOf(border.top, scope.size.height / 2)
                val bottom = minOf(border.bottom, scope.size.height / 2)
                val sx = if (scope.size.width == image.size.width) listOf(0, image.size.width) else listOf(0, left, image.size.width - right, image.size.width)
                val sy = if (scope.size.height == image.size.height) listOf(0, image.size.height) else listOf(0, top, image.size.height - bottom, image.size.height)
                val dx = if (sx.size == 2) sx else listOf(0, left, scope.size.width - right, scope.size.width)
                val dy = if (sy.size == 2) sy else listOf(0, top, scope.size.height - bottom, scope.size.height)
                for (y in 0 until sy.lastIndex) {
                    for (x in 0 until sx.lastIndex) {
                        val tile = (sx.size == 4 && x == 1) || (sy.size == 4 && y == 1)
                        segment(scope, IntRect(sx[x], sy[y], sx[x + 1], sy[y + 1]), IntRect(dx[x], dy[y], dx[x + 1], dy[y + 1]), tile && element.centerMode == NineSliceCenterMode.Tiled)
                    }
                }
            }
        }

        /**
         * Applies new pixels or mapping, preserving modifier identity.
         */
        fun update(current: WebImageBackground): DirtyMask {
            if (element == current) return DirtyMask.None
            element = current
            return DirtyMask.of(DirtyPhase.Paint)
        }

        private fun segment(
            scope: PaintScope,
            source: IntRect,
            target: IntRect,
            tiled: Boolean,
        ) {
            if (source.width == 0 || source.height == 0) return
            if (target.width == 0 || target.height == 0) return
            if (tiled.not()) {
                scope.blitImage(element.image, source, target)
                return
            }
            val tileWidth = if (source.width == 1) target.width else source.width
            val tileHeight = if (source.height == 1) target.height else source.height
            var y = target.top
            while (y < target.bottom) {
                val height = minOf(tileHeight, target.bottom - y)
                var x = target.left
                while (x < target.right) {
                    val width = minOf(tileWidth, target.right - x)
                    scope.blitImage(element.image, IntRect(source.left, source.top, source.left + minOf(width, source.width), source.top + minOf(height, source.height)), IntRect(x, y, x + width, y + height))
                    x += width
                }
                y += height
            }
        }
    }

    private companion object {
        val TYPE = ModifierNodeType(WebImageBackground::class, Node::class, { _ -> }, ::Node, { _, current, node -> node.update(current) })
    }
}
