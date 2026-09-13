package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderOperation
import dev.s7a.strata.runtime.platform.IdentityMap
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Checks identity ownership and detached diagnostic collection contracts on JVM and JavaScript.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PortableRegistryTest {
    @Test
    fun equalKeysRemainIndependentAndSnapshotsSurviveRelease() {
        val first = listOf(1)
        val second = listOf(1)
        val registry = IdentityMap<List<Int>, String>()
        registry[first] = "first"
        registry[second] = "second"
        val values = registry.values
        assertEquals("first", registry.remove(first))
        assertNull(registry[first])
        assertEquals("second", registry[second])
        registry.clear()
        assertEquals(emptyList(), registry.values)
        assertEquals(setOf("first", "second"), values.toSet())
    }

    @Test
    fun diagnosticMapsRemainDetachedAfterCountersReset() {
        val counts = RenderWorkCounts()
        counts.record(UiRenderMetric.FrameAttempt, UiRenderOperation.Frame)
        val totals = counts.totals()
        val operations = counts.operations()
        val frame = operations.getValue(UiRenderOperation.Frame)
        counts.clear()
        assertEquals(1L, totals[UiRenderMetric.FrameAttempt])
        assertEquals(1L, frame[UiRenderMetric.FrameAttempt])
        assertEquals(0L, counts.totals()[UiRenderMetric.FrameAttempt])
        assertEquals(totals.toMap(), totals)
        assertEquals(totals.toMap().hashCode(), totals.hashCode())
    }
}
