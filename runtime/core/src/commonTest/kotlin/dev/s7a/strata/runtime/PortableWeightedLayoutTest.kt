@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies integer weight slots and floating-point ratios on JVM and JavaScript.
 */
internal class PortableWeightedLayoutTest {
    @Test
    fun fractionalWeightsAndRoundingPreserveTheAvailableExtent() {
        assertSlots(listOf(0.5f, 1.5f), 12, listOf(3, 9))
        assertSlots(listOf(1f, 48f), 49, listOf(1, 48))
        assertSlots(listOf(1f, 1f, 1f), 10, listOf(3, 3, 4))
        assertSlots(listOf(0.25f, 0.75f), 0, listOf(0, 0))
        assertSlots(listOf(0.5f), Int.MAX_VALUE - 1, listOf(Int.MAX_VALUE - 1))
    }

    @Test
    fun extremeWeightsRemainFiniteAndAllowSubpixelSharesToVanish() {
        assertSlots(listOf(Float.MAX_VALUE, Float.MIN_VALUE), 100, listOf(100, 0))
        assertSlots(listOf(Float.MIN_VALUE, Float.MAX_VALUE), 100, listOf(0, 100))
        assertSlots(listOf(Float.MIN_VALUE, Float.MIN_VALUE), 100, listOf(50, 50))
        assertSlots(listOf(Float.MAX_VALUE, Float.MAX_VALUE), Int.MAX_VALUE - 1, listOf(1_073_741_823, 1_073_741_823))
    }

    private fun assertSlots(
        weights: List<Float>,
        extent: Int,
        expected: List<Int>,
    ) {
        for (horizontal in listOf(true, false)) {
            val probe = TestProbe()
            val tree = UiTree()
            try {
                tree.update(
                    evaluateComponentTree {
                        if (horizontal) {
                            Row {
                                weights.forEachIndexed { index, weight ->
                                    element(probe.element(TestProbe.ProbeId(index.toString()), modifier = Modifier.Empty.weight(weight)))
                                }
                            }
                        } else {
                            Column {
                                weights.forEachIndexed { index, weight ->
                                    element(probe.element(TestProbe.ProbeId(index.toString()), modifier = Modifier.Empty.weight(weight)))
                                }
                            }
                        }
                    },
                )
                tree.measure(if (horizontal) Constraints.fixed(extent, 1) else Constraints.fixed(1, extent))
                tree.layout()
                assertEquals(expected, probe.measureConstraints.map { if (horizontal) it.minWidth else it.minHeight })
                assertEquals(expected, probe.measureConstraints.map { if (horizontal) it.maxWidth else it.maxHeight })
            } finally {
                tree.close()
            }
        }
    }
}
