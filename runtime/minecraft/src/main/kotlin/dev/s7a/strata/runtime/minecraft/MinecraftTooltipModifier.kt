package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Profile-owned tooltip surface; common runtime code owns timing and root placement.
 */
private object MinecraftTooltipModifier {
    /**
     * Immutable background style for one decorated overlay.
     */
    data class Description(
        val style: MinecraftTooltipStyle,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *> get() = TYPE
    }

    /**
     * Paints the standard decoration in the independently laid-out root's local coordinates.
     */
    class Retained(
        var style: MinecraftTooltipStyle,
    ) : ModifierNode(),
        PaintNode,
        OverlayPaintNode {
        override fun paint(scope: PaintScope) {
            style.paintBackground(scope, IntRect(0, 0, scope.size.width, scope.size.height))
        }

        override fun paintOverlay(scope: PaintScope) {
            style.paintFrame(scope, IntRect(0, 0, scope.size.width, scope.size.height))
        }
    }

    val TYPE =
        ModifierNodeType(
            elementClass = Description::class,
            nodeClass = Retained::class,
            validateLocal = {},
            createNode = { Retained(it.style) },
            updateNode = { _, current, node ->
                node.style = current.style
                DirtyMask.of(DirtyPhase.Paint)
            },
        )
}

/**
 * Applies the profile's standard tooltip surface and inset, or retains bare caller content.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun createMinecraftTooltipContent(
    content: Element,
    style: MinecraftTooltipStyle,
    decorated: Boolean,
): Element =
    evaluateComponentTree {
        val surface = if (decorated) Modifier.then(MinecraftTooltipModifier.Description(style)).padding(3) else Modifier
        Stack(modifier = surface) {
            element(content)
        }
    }
