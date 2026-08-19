package dev.s7a.strata.integration.external

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText

/**
 * Third-party modifier node with real pipeline and lifecycle behavior.
 *
 * It uses the inherited one-child pass-through measure and layout behavior.
 */
public class ExternalModifierNode internal constructor(
    private val probe: ExternalProbe,
) : ModifierNode(),
    PaintNode,
    PointerInputNode,
    SemanticsNode,
    LifecycleNode {
    /**
     * Current paint color installed by the typed update bridge.
     */
    internal var color: ArgbColor = ArgbColor(0xFFFF00FF.toInt())

    /**
     * Current unresolved semantics label installed by the typed update bridge.
     */
    internal var label: UiText = UiText.Literal("modifier")

    /**
     * Number of paint callbacks observed by the external probe node.
     */
    internal var paintCalls: Int = 0

    /**
     * Number of pointer callbacks observed by the external probe node.
     */
    internal var pointerCalls: Int = 0

    override fun measure(
        scope: MeasureScope,
        constraints: Constraints,
    ): IntSize {
        probe.modifierVirtualChildCounts += scope.childCount
        return super.measure(scope, constraints)
    }

    override fun paint(scope: PaintScope) {
        paintCalls += 1
        scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color)
    }

    override fun onPointerEvent(
        event: PointerEvent,
        localPosition: IntOffset,
    ): InputResult {
        pointerCalls += 1
        return InputResult.Consumed
    }

    override fun semantics(scope: SemanticsScope) {
        scope.emit(Semantics(label = label))
    }

    override fun attach() {
        probe.lifecycle += ExternalLifecycleEvent.Attach(ExternalNodeId.Modifier)
    }

    override fun detach() {
        probe.lifecycle += ExternalLifecycleEvent.Detach(ExternalNodeId.Modifier)
    }

    override fun dispose() {
        probe.lifecycle += ExternalLifecycleEvent.Dispose(ExternalNodeId.Modifier)
    }

    /**
     * Exercises node-local invalidation from the external test package.
     *
     * @param phase the phase to invalidate.
     */
    public fun invalidateForTest(phase: DirtyPhase) {
        invalidate(DirtyMask.of(phase))
    }
}
