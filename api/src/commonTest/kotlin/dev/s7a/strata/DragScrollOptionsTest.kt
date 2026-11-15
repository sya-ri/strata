package dev.s7a.strata

import dev.s7a.strata.component.DragScrollOptions
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.projection.ProjectionDragScrollOptions
import dev.s7a.strata.projection.ProjectionValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Verifies option admission and the shared local/remote navigation-state policy on JVM and JavaScript.
 */
internal class DragScrollOptionsTest {
    @Test
    fun statesRetainTheirImmutablePolicyAndInitialOffset() {
        val options = DragScrollOptions(enabled = false, inertiaEnabled = false, dragThreshold = 8.0, deceleration = 12.0)
        val scroll = ScrollState(dragScroll = options, initialOffset = 18.5)
        val list = VirtualListState<Int>(scrollState = scroll)
        assertSame(options, list.scrollState.dragScroll)
        assertEquals(18.5, scroll.metrics.offset)
        assertEquals(DragScrollOptions.Default, ScrollState().dragScroll)
    }

    @Test
    fun thresholdsAndDecayRatesMustBeFiniteAndPositive() {
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { DragScrollOptions(dragThreshold = invalid) }
            assertFailsWith<IllegalArgumentException> { DragScrollOptions(deceleration = invalid) }
        }
    }

    @Test
    fun wirePolicyRoundTripsAndRejectsTruncatedExtraAndInvalidFields() {
        val options = DragScrollOptions(enabled = false, inertiaEnabled = false, dragThreshold = 8.0, deceleration = 12.0)
        val encoded = ProjectionDragScrollOptions.encode(options) as ProjectionValue.Sequence
        assertEquals(options, ProjectionDragScrollOptions.decode(encoded))
        assertFailsWith<IllegalArgumentException> { ProjectionDragScrollOptions.decode(ProjectionValue.Sequence(encoded.values.dropLast(1))) }
        assertFailsWith<IllegalArgumentException> { ProjectionDragScrollOptions.decode(ProjectionValue.Sequence(encoded.values + ProjectionValue.Flag(true))) }
        val invalid = encoded.values.toMutableList()
        invalid[2] = ProjectionValue.Real(0.0)
        assertFailsWith<IllegalArgumentException> { ProjectionDragScrollOptions.decode(ProjectionValue.Sequence(invalid)) }
    }
}
