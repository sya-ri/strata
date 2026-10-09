@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent actual target-node dirty policy checks without drawing conclusions from elapsed time. */
internal class FlowRetainedPolicyTest {
    @Test
    fun arrangementAndDefaultAlignmentRemainLayoutOnlyParentOverridesRemainMeasureDirtyAndIdleIsClean() {
        for (original in listOf(false, true)) {
            FlowRetainedTestFixture(original).use { fixture ->
                val ids = (0 until 16).toList()
                val bounds = Constraints(maxWidth = 7, maxHeight = 180)
                fixture.update(ids)
                fixture.frame(bounds)
                fixture.tree.startRenderMonitoring(65_536).use { monitor ->
                    val target = monitor.findNodes(fixture.target).single()
                    fixture.frame(bounds)
                    var counts = monitor.snapshot().nodes.single { it.id == target }.counts
                    assertEquals(0L, counts.getValue(UiRenderMetric.Measure))
                    assertEquals(0L, counts.getValue(UiRenderMetric.Layout))
                    monitor.checkpoint()
                    fixture.update(ids, arrangement = Arrangement.End)
                    fixture.frame(bounds)
                    counts = monitor.snapshot().nodes.single { it.id == target }.counts
                    assertEquals(0L, counts.getValue(UiRenderMetric.Measure))
                    assertEquals(1L, counts.getValue(UiRenderMetric.Layout))
                    monitor.checkpoint()
                    fixture.update(ids, arrangement = Arrangement.End, alignment = VerticalAlignment.Bottom)
                    fixture.frame(bounds)
                    counts = monitor.snapshot().nodes.single { it.id == target }.counts
                    assertEquals(0L, counts.getValue(UiRenderMetric.Measure))
                    assertEquals(1L, counts.getValue(UiRenderMetric.Layout))
                    monitor.checkpoint()
                    fixture.update(ids, arrangement = Arrangement.End, alignment = VerticalAlignment.Bottom,
                        overrides = List(16) { if (it == 0) VerticalAlignment.Center else null })
                    fixture.frame(bounds)
                    counts = monitor.snapshot().nodes.single { it.id == target }.counts
                    assertEquals(1L, counts.getValue(UiRenderMetric.Measure))
                    assertEquals(1L, counts.getValue(UiRenderMetric.Layout))
                    assertTrue(monitor.snapshot().overflowed.not())
                }
            }
        }
    }
}

