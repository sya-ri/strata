package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Complete retained geometry, ordered commands, semantics and input match the frozen original.
 */
internal class LinearRetainedGeometryTest {
    @Test
    fun allMainAndCrossPoliciesRemainEquivalentWithFixedAndWeightedChildren() {
        for (horizontal in listOf(true, false)) {
            for (arrangement in Arrangement.entries) {
                for (vertical in VerticalAlignment.entries) {
                    for (alignment in HorizontalAlignment.entries) {
                        for (fill in listOf(false, true)) {
                            LinearRetainedTestFixture(true, horizontal).use { original ->
                                LinearRetainedTestFixture(false, horizontal).use { candidate ->
                                    val ids = (0 until 16).toList()
                                    val weights = List(16) { index -> if (index % 2 == 0) (index % 3 + 1).toFloat() else null }
                                    for (fixture in listOf(original, candidate)) fixture.update(ids, weights, fill, spacing = 1, arrangement = arrangement, vertical = vertical, alignment = alignment)
                                    for (bounds in listOf(Constraints.fixed(61, 43), Constraints.fixed(0, 0), if (horizontal) Constraints(maxHeight = 43) else Constraints(maxWidth = 61))) {
                                        assertEquals(original.frame(bounds), candidate.frame(bounds))
                                        assertEquals(original.probe.measureConstraints, candidate.probe.measureConstraints)
                                        assertEquals(original.probe.inputObservations, candidate.probe.inputObservations)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
