@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Real standard linear parents retain neither a usable escaped scope nor duplicate child participation. */
internal class LinearScopeLifetimeTest {
    @Test
    fun everyEscapedMeasureAndLayoutScopeRejectsUseAfterCallback() {
        for (horizontal in listOf(true, false)) {
            val probe = LinearScopeProbeModifier.Probe()
            UiTree().use { tree ->
                tree.update(description(horizontal, probe))
                tree.measure(Constraints.fixed(10, 10))
                tree.layout()
                assertFailsWith<IllegalStateException> { checkNotNull(probe.measure).childCount }
                assertFailsWith<IllegalStateException> { checkNotNull(probe.layout).childCount }
                assertFailsWith<IllegalStateException> { checkNotNull(probe.layout).measuredChildSize(0) }
            }
        }
    }

    @Test
    fun measuredOnceAndPlacedOnceViolationsPoisonAndReleaseTheTree() {
        for (horizontal in listOf(true, false)) {
            for (measure in listOf(true, false)) {
                val probe = LinearScopeProbeModifier.Probe()
                probe.duplicateMeasure = measure
                probe.duplicateLayout = measure.not()
                UiTree().use { tree ->
                    tree.update(description(horizontal, probe))
                    assertFailsWith<IllegalStateException> {
                        tree.measure(Constraints.fixed(10, 10))
                        tree.layout()
                    }
                    assertEquals(TreeState.Poisoned, tree.state)
                }
            }
        }
    }

    private fun description(horizontal: Boolean, probe: LinearScopeProbeModifier.Probe) =
        evaluateComponentTree {
            if (horizontal) Row { Spacer(modifier = Modifier.Empty.then(LinearScopeProbeModifier(probe))) }
            else Column { Spacer(modifier = Modifier.Empty.then(LinearScopeProbeModifier(probe))) }
        }
}
