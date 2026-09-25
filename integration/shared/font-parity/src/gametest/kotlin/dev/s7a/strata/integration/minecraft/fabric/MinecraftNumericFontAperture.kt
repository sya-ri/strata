package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode

/**
 * Test-only finite viewport around an unbounded naturally measured Text child.
 * It preserves the public component's measurement contract instead of forcing huge native widths into a small constraint.
 * Placement and clipping use the shared row origin; the positive-infinite row uses zero x so Int.MAX_VALUE remains representable.
 * Nodes own only immutable geometry and remain confined to their host's tree thread.
 */
internal object MinecraftNumericFontAperture {
    /**
     * Creates an active layout modifier for one immutable numeric row.
     */
    fun modifier(row: MinecraftNumericFontFixture.Row): Modifier = Modifier.Empty.then(Description(IntOffset(row.left, row.top)))

    private data class Description(
        val origin: IntOffset,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *> get() = TYPE
    }

    private class ApertureNode(
        var origin: IntOffset,
    ) : ModifierNode(),
        ClipChildrenNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            check(scope.childCount == 1)
            scope.measureChild(0, Constraints())
            return constraints.constrain(MinecraftFontParityFixture.viewport)
        }

        override fun layout(scope: LayoutScope) {
            check(scope.childCount == 1)
            scope.placeChild(0, origin)
        }
    }

    private val TYPE =
        ModifierNodeType(
            elementClass = Description::class,
            nodeClass = ApertureNode::class,
            validateLocal = { description -> require(0 <= description.origin.x && 0 <= description.origin.y) },
            createNode = { description -> ApertureNode(description.origin) },
            updateNode = { previous, current, node ->
                node.origin = current.origin
                if (previous == current) DirtyMask.None else DirtyMask.of(DirtyPhase.Layout)
            },
        )
}
