package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Current-state reuse is compared across equal-count weight replacement, keyed movement and independent owners. */
internal class LinearRetainedTopologyTest {
    @Test
    fun appendRemoveShrinkReorderAndWeightChangesKeepOriginalRetainedIdentity() {
        for (horizontal in listOf(true, false)) {
            for (keyed in listOf(false, true)) {
                LinearRetainedTestFixture(true, horizontal).use { original ->
                    LinearRetainedTestFixture(false, horizontal).use { candidate ->
                        for (count in listOf(0, 1, 16, 128, 4_096, 128, 16, 1, 0)) {
                            val ids = (0 until count).toList()
                            for (order in listOf(ids, ids.reversed())) {
                                for (weighted in listOf(false, true, false)) {
                                    for (fixture in listOf(original, candidate)) {
                                        fixture.update(order, List(count) { index -> if (weighted && index % 2 == 0) 1f else null }, keyed = keyed)
                                    }
                                    assertEquals(original.frame(Constraints.fixed(320, 180)), candidate.frame(Constraints.fixed(320, 180)))
                                    assertEquals(original.probe.events, candidate.probe.events)
                                    assertEquals(original.probe.created.size, candidate.probe.created.size)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun equalCountReorderKeepsTheActualKeyedChildNodes() {
        for (horizontal in listOf(true, false)) {
            for (original in listOf(false, true)) {
                LinearRetainedTestFixture(original, horizontal).use { fixture ->
                    fixture.update((0 until 16).toList())
                    fixture.frame(Constraints.fixed(320, 180))
                    val nodes = List(16) { fixture.probe.nodeForTag(TestProbe.ProbeId(it.toString())) }
                    fixture.update((0 until 16).reversed().toList(), List(16) { 1f })
                    fixture.frame(Constraints.fixed(321, 181))
                    for (index in nodes.indices) assertSame(nodes[index], fixture.probe.nodeForTag(TestProbe.ProbeId(index.toString())))
                }
            }
        }
    }

    @Test
    fun oneThousandShrinkWeightToggleResizeCyclesKeepParityAndCurrentOwnership() {
        for (horizontal in listOf(true, false)) {
            LinearRetainedTestFixture(true, horizontal).use { original ->
                LinearRetainedTestFixture(false, horizontal).use { candidate ->
                    repeat(1_000) { cycle ->
                        val steps = listOf(Triple(128, false, 0), Triple(128, true, 1), Triple(16, false, 0), Triple(0, false, 1))
                        for ((count, weighted, resized) in steps) {
                            val ids = (0 until count).toList().let { if (cycle % 2 == 0) it else it.reversed() }
                            val weights = List(count) { index -> if (weighted && index % 2 == 0) 1f else null }
                            for (fixture in listOf(original, candidate)) fixture.update(ids, weights, extent = resized + 1, spacing = resized)
                            val bounds = Constraints.fixed(320 + resized, 180 + resized)
                            assertEquals(original.frame(bounds), candidate.frame(bounds))
                            assertEquals(original.probe.events, candidate.probe.events)
                            for (fixture in listOf(original, candidate)) {
                                fixture.probe.events.clear()
                                fixture.probe.created.clear()
                                fixture.probe.measureConstraints.clear()
                                fixture.probe.inputObservations.clear()
                                fixture.probe.inputEvents.clear()
                            }
                        }
                    }
                }
            }
        }
    }
}
