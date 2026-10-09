package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.ModifierNode

/** Captures actual callback scopes solely to verify their original lifetime and once-only contracts. */
internal class FlowScopeProbeModifier(
    val probe: Probe,
) : ModifierElement {
    override val type: ModifierNodeType<*, *> get() = TYPE

    /** Test-owned escaped scopes; these are never part of a production owner or timed fixture. */
    class Probe {
        var measure: MeasureScope? = null
        var layout: LayoutScope? = null
        var duplicateMeasure: Boolean = false
        var duplicateLayout: Boolean = false
    }

    /** Delegates the complete ordinary modifier behavior before an optional illegal second operation. */
    class CaptureNode(private val probe: Probe) : ModifierNode() {
        override fun measure(scope: MeasureScope, constraints: Constraints): IntSize {
            probe.measure = scope
            val result = super.measure(scope, constraints)
            if (probe.duplicateMeasure) scope.measureChild(0, constraints)
            return result
        }

        override fun layout(scope: LayoutScope) {
            probe.layout = scope
            super.layout(scope)
            if (probe.duplicateLayout) scope.placeChild(0, IntOffset.Zero)
        }
    }

    private companion object {
        val TYPE = ModifierNodeType(
            elementClass = FlowScopeProbeModifier::class,
            nodeClass = CaptureNode::class,
            validateLocal = { _ -> },
            createNode = { element -> CaptureNode(element.probe) },
            updateNode = { _, _, _ -> DirtyMask.None },
        )
    }
}
