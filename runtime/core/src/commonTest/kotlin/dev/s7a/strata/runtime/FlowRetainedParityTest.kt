package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Original whole-node geometry, direct ownership and policy reuse across every required retained topology. */
internal class FlowRetainedParityTest {
    @Test
    fun allArrangementsAlignmentsAndParentOverridesPreserveFullOrderedOutputAndInput() {
        for (arrangement in Arrangement.entries) {
            for (alignment in VerticalAlignment.entries) {
                for (overridden in listOf(false, true)) {
                    FlowRetainedTestFixture(true).use { original ->
                        FlowRetainedTestFixture(false).use { candidate ->
                            val ids = (0 until 16).toList()
                            for (fixture in listOf(original, candidate)) {
                                fixture.update(ids, List(16) { it % 3 + 1 }, arrangement = arrangement, alignment = alignment,
                                    overrides = List(16) { if (overridden) VerticalAlignment.entries[it % 3] else null })
                            }
                            for (bounds in listOf(Constraints.fixed(9, 180), Constraints(maxWidth = 7, maxHeight = 180), Constraints(), Constraints.fixed(0, 0))) {
                                assertEquals(original.frame(bounds), candidate.frame(bounds))
                                assertEquals(original.probe.inputObservations, candidate.probe.inputObservations)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun appendShrinkReorderWrapAndPolicyChangesKeepOriginalKeyedAndPositionalIdentity() {
        for (keyed in listOf(false, true)) {
            FlowRetainedTestFixture(true).use { original ->
                FlowRetainedTestFixture(false).use { candidate ->
                    for (count in listOf(0, 1, 16, 128, 4_096, 128, 16, 1, 0)) {
                        val ids = (0 until count).toList()
                        for (order in listOf(ids, ids.reversed())) {
                            for (arrangement in Arrangement.entries) {
                                for (fixture in listOf(original, candidate)) fixture.update(order, List(count) { it % 2 + 1 }, arrangement = arrangement, keyed = keyed)
                                assertEquals(original.frame(Constraints(maxWidth = 7, maxHeight = 180)), candidate.frame(Constraints(maxWidth = 7, maxHeight = 180)))
                                assertEquals(original.probe.events, candidate.probe.events)
                                assertEquals(original.probe.created.size, candidate.probe.created.size)
                                for (fixture in listOf(original, candidate)) {
                                    fixture.probe.events.clear()
                                    fixture.probe.created.clear()
                                    fixture.probe.measureConstraints.clear()
                                    fixture.probe.inputObservations.clear()
                                    fixture.probe.inputEvents.clear()
                                    fixture.focus.clear()
                                    fixture.keys.clear()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun keyedChildrenRemainTheSameLogicalNodesAcrossChangedRowMembership() {
        for (original in listOf(false, true)) {
            FlowRetainedTestFixture(original).use { fixture ->
                val ids = (0 until 16).toList()
                fixture.update(ids)
                fixture.frame(Constraints(maxWidth = 7, maxHeight = 180))
                val nodes = List(16) { fixture.probe.nodeForTag(TestProbe.ProbeId(it.toString())) }
                fixture.update(ids.reversed(), List(16) { it % 2 + 1 }, horizontalSpacing = 2, verticalSpacing = 2)
                fixture.frame(Constraints(maxWidth = 9, maxHeight = 181))
                for (index in nodes.indices) assertSame(nodes[index], fixture.probe.nodeForTag(TestProbe.ProbeId(index.toString())))
            }
        }
    }

    @Test
    fun oneThousandGrowShrinkReorderWrapCyclesKeepIndependentOwnersAndCompleteOutput() {
        FlowRetainedTestFixture(true).use { original ->
            FlowRetainedTestFixture(false).use { candidate ->
                repeat(1_000) { cycle ->
                    for (count in listOf(128, 16, 0, 1)) {
                        val ids = (0 until count).toList().let { if (cycle % 2 == 0) it else it.reversed() }
                        for (fixture in listOf(original, candidate)) fixture.update(ids, List(count) { it % 2 + 1 }, horizontalSpacing = cycle % 2, verticalSpacing = cycle % 2)
                        val bounds = Constraints(maxWidth = 7 + cycle % 2, maxHeight = 180 + cycle % 2)
                        assertEquals(original.frame(bounds), candidate.frame(bounds))
                        assertEquals(original.probe.events, candidate.probe.events)
                        for (fixture in listOf(original, candidate)) {
                            fixture.probe.events.clear()
                            fixture.probe.created.clear()
                            fixture.probe.measureConstraints.clear()
                            fixture.probe.inputObservations.clear()
                            fixture.probe.inputEvents.clear()
                            fixture.focus.clear()
                            fixture.keys.clear()
                        }
                    }
                }
            }
        }
    }
}

