@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Real FlowRow ownership rejects escaped scopes and repeated child participation exactly as other standard parents.
 */
internal class FlowScopeLifetimeTest {
    @Test
    fun actualEscapedScopesRejectUseAfterTheOwningCallbackReturns() {
        val probe = FlowScopeProbeModifier.Probe()
        UiTree().use { tree ->
            tree.update(description(probe))
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
            assertFailsWith<IllegalStateException> { checkNotNull(probe.measure).childCount }
            assertFailsWith<IllegalStateException> { checkNotNull(probe.layout).childCount }
            assertFailsWith<IllegalStateException> { checkNotNull(probe.layout).measuredChildSize(0) }
        }
    }

    @Test
    fun duplicateMeasureOrPlacementPoisonsTheActualFlowRowTree() {
        for (measure in listOf(true, false)) {
            val probe = FlowScopeProbeModifier.Probe()
            probe.duplicateMeasure = measure
            probe.duplicateLayout = measure.not()
            UiTree().use { tree ->
                tree.update(description(probe))
                assertFailsWith<IllegalStateException> {
                    tree.measure(Constraints.fixed(10, 10))
                    tree.layout()
                }
                assertEquals(TreeState.Poisoned, tree.state)
            }
        }
    }

    private fun description(probe: FlowScopeProbeModifier.Probe) = evaluateComponentTree {
        FlowRow { Spacer(modifier = Modifier.Empty.then(FlowScopeProbeModifier(probe))) }
    }
}
