package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope

/**
 * Theme-owned decoration independent of tooltip content padding and input.
 */
internal data class WebTooltipSurface(
    val theme: WebTheme,
) : ModifierElement {
    override val type: ModifierNodeType<*, *> get() = TYPE

    /**
     * Paints a filled surface and inset frame without changing measured dimensions.
     */
    private class Retained(
        var theme: WebTheme,
    ) : ModifierNode(),
        PaintNode {
        override fun paint(scope: PaintScope) {
            val width = scope.size.width
            val height = scope.size.height
            if (width == 0 || height == 0) return
            val background = if (theme == WebTheme.Minecraft) ArgbColor(0xF0100010.toInt()) else ArgbColor(0xF0202020.toInt())
            val border = if (theme == WebTheme.Minecraft) ArgbColor(0xFF5000FF.toInt()) else ArgbColor(0xFF808080.toInt())
            scope.fillRectangle(IntRect(0, 0, width, height), background)
            scope.fillRectangle(IntRect(0, 0, width, 1), border)
            scope.fillRectangle(IntRect(0, height - 1, width, height), border)
            scope.fillRectangle(IntRect(0, 0, 1, height), border)
            scope.fillRectangle(IntRect(width - 1, 0, width, height), border)
        }
    }

    private companion object {
        private val TYPE =
            ModifierNodeType(
                elementClass = WebTooltipSurface::class,
                nodeClass = Retained::class,
                validateLocal = {},
                createNode = { Retained(it.theme) },
                updateNode = { previous, current, node ->
                    node.theme = current.theme
                    if (previous == current) DirtyMask.None else DirtyMask.of(DirtyPhase.Paint)
                },
            )
    }
}
