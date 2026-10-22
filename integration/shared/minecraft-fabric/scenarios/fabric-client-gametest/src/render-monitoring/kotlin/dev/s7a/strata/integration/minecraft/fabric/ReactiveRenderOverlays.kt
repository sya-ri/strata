package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.RootOverlayPaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.RootOverlayPaintScope

/**
 * Immutable foreground fixture covering changing text and progress with clipped translucent and opaque paint.
 * Its commands are retained while the lower components change; native composition must still replay them.
 */
internal object ReactiveRenderOverlays : ModifierElement {
    /**
     * Actual callbacks of this synthetic fixture; production modifiers contain no observer or counter.
     */
    var localCalls: Long = 0L
        private set
    var rootCalls: Long = 0L
        private set

    override val type: ModifierNodeType<*, *> =
        ModifierNodeType(
            elementClass = ReactiveRenderOverlays::class,
            nodeClass = OverlayNode::class,
            validateLocal = { _ -> },
            createNode = { _ -> OverlayNode() },
            updateNode = { _, _, _ -> DirtyMask.None },
        )

    /**
     * Emits local paint after descendants, then root-coordinate paint after every ordinary tree command.
     */
    private class OverlayNode :
        ModifierNode(),
        OverlayPaintNode,
        RootOverlayPaintNode {
        override fun paintOverlay(scope: PaintScope) {
            localCalls += 1L
            scope.withClip(IntRect(16, 8, 112, 36)) {
                scope.fillRectangle(IntRect(0, 0, 128, 48), ArgbColor(0x80FFFFFF.toInt()))
            }
        }

        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            rootCalls += 1L
            scope.withClip(IntRect(40, 20, 48, 32)) {
                scope.fillRectangle(IntRect(0, 0, 160, 48), ArgbColor(0xFFFFCC00.toInt()))
            }
        }
    }
}
