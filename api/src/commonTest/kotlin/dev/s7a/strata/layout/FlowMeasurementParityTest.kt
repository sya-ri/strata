package dev.s7a.strata.layout

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Original complete FlowRow measurement/layout check finite partitions, cutoffs, arithmetic and policies on JVM/JS. */
internal class FlowMeasurementParityTest {
    @Test
    fun allFinitePartitionsAndNaturalExtentsMatchTheFrozenOriginal() {
        for (count in listOf(0, 1, 16, 128, 4_096)) {
            for (maximum in listOf(0, 1, 7, 9, Int.MAX_VALUE)) {
                for (spacing in listOf(0, 1, 2)) {
                    for (pattern in Pattern.entries) {
                        val sizes = List(count) { index -> IntSize(pattern.width(index), index % 3) }
                        compare(sizes, Constraints(maxWidth = maximum, maxHeight = 180), spacing, 1)
                    }
                }
            }
        }
    }

    @Test
    fun minimumClampingAndExtremeSpacingKeepOriginalCheckedFailures() {
        for (spacing in listOf(0, 1, Int.MAX_VALUE)) {
            for (verticalSpacing in listOf(0, 1, Int.MAX_VALUE)) {
                for (sizes in listOf(List(3) { IntSize.Zero }, List(3) { IntSize(1, Int.MAX_VALUE) }, List(3) { IntSize(Int.MAX_VALUE, 1) })) {
                    compare(sizes, Constraints(), spacing, verticalSpacing)
                    compare(sizes, Constraints(1, 9, 2, Int.MAX_VALUE), spacing, verticalSpacing)
                }
            }
        }
    }

    @Test
    fun lateChildFailureWinsBeforeAnyPartitionArithmetic() {
        val sizes = List(3) { IntSize(Int.MAX_VALUE, Int.MAX_VALUE) }
        val cause = IllegalStateException("Last child failure precedes extent overflow")
        val bounds = Constraints()
        val expectedEvent = FlowMeasurementTrace.Measure(2, bounds)
        val pair = List(2) { FlowMeasurementScope(sizes, FlowMeasurementScope.Failure(expectedEvent, cause)) }
        val actual = compare(pair, bounds, 1, Int.MAX_VALUE)
        assertSame(cause, actual)
        assertEquals<List<FlowMeasurementTrace>>((0 until 3).map { FlowMeasurementTrace.Measure(it, bounds) }, pair.first().trace)
    }

    @Test
    fun allArrangementsAndDefaultOrParentAlignmentKeepCompleteLayoutPreflight() {
        for (arrangement in Arrangement.entries) {
            for (alignment in VerticalAlignment.entries) {
                for (overridden in listOf(false, true)) {
                    val sizes = List(16) { index -> IntSize(index % 3, index % 4) }
                    val pair = List(2) { FlowMeasurementScope(sizes, overrideAlignment = overridden) }
                    pair.forEach { scope -> scope.onPlace = { for (index in scope.measuredSizes.indices) scope.measuredSizes[index] = IntSize.Zero } }
                    compare(pair, Constraints.fixed(9, 180), 1, 2, arrangement, alignment)
                    val trace = pair.first().trace
                    val firstPlacement = trace.indexOfFirst { it is FlowMeasurementTrace.Place }
                    assertEquals(16, trace.take(firstPlacement).filterIsInstance<FlowMeasurementTrace.SizeRead>().size)
                }
            }
        }
    }

    @Test
    fun firstMiddleLastFailuresPreserveTheExactOriginalTraceAndIdentity() {
        val sizes = List(16) { index -> IntSize(index % 3 + 1, index % 4 + 1) }
        val successful = List(2) { FlowMeasurementScope(sizes) }
        compare(successful, Constraints.fixed(9, 180), 1, 1)
        val trace = successful.first().trace
        val groups = listOf(
            trace.filterIsInstance<FlowMeasurementTrace.Measure>(),
            trace.filterIsInstance<FlowMeasurementTrace.SizeRead>(),
            trace.filterIsInstance<FlowMeasurementTrace.AlignmentRead>(),
            trace.filterIsInstance<FlowMeasurementTrace.Place>(),
        )
        for (events in groups) {
            for (event in listOf(events.first(), events[events.size / 2], events.last())) {
                val cause = IllegalArgumentException("Original scope failure")
                val pair = List(2) { FlowMeasurementScope(sizes, FlowMeasurementScope.Failure(event, cause)) }
                assertSame(cause, compare(pair, Constraints.fixed(9, 180), 1, 1))
                assertEquals(trace.take(trace.indexOf(event) + 1), pair.first().trace)
            }
        }
    }

    @Test
    fun layoutAndMeasurementRetainNoPreviousChildSizesOrRowMembership() {
        val candidate = FlowRowElement.Node(1, 1, Arrangement.Start, VerticalAlignment.Top)
        val reference = FlowRowMeasurementReference.Node(1, 1, Arrangement.Start, VerticalAlignment.Top)
        for (count in listOf(128, 1, 0, 16, 128)) {
            val sizes = List(count) { index -> IntSize(index % 2 + 1, index % 3) }
            val pair = List(2) { FlowMeasurementScope(sizes) }
            val bounds = Constraints(maxWidth = 7, maxHeight = 180)
            assertEquals(execute(reference, reference, pair[0], bounds), execute(candidate, candidate, pair[1], bounds))
            assertEquals(pair[0].trace, pair[1].trace)
            assertTrue(pair[1].trace.filterIsInstance<FlowMeasurementTrace.Measure>().map { it.index } == (0 until count).toList())
        }
    }

    private enum class Pattern {
        Zero,
        One,
        Two,
        Mixed,
        ;

        fun width(index: Int): Int =
            when (this) {
                Zero -> 0
                One -> 1
                Two -> 2
                Mixed -> index % 3
            }
    }

    private fun compare(sizes: List<IntSize>, bounds: Constraints, spacing: Int, verticalSpacing: Int): Throwable? =
        compare(List(2) { FlowMeasurementScope(sizes) }, bounds, spacing, verticalSpacing)

    private fun compare(
        pair: List<FlowMeasurementScope>,
        bounds: Constraints,
        spacing: Int,
        verticalSpacing: Int,
        arrangement: Arrangement = Arrangement.Start,
        alignment: VerticalAlignment = VerticalAlignment.Top,
    ): Throwable? {
        val reference = FlowRowMeasurementReference.Node(spacing, verticalSpacing, arrangement, alignment)
        val candidate = FlowRowElement.Node(spacing, verticalSpacing, arrangement, alignment)
        val expected = execute(reference, reference, pair[0], bounds)
        val actual = execute(candidate, candidate, pair[1], bounds)
        assertEquals(expected.getOrNull(), actual.getOrNull())
        assertEquals(expected.exceptionOrNull()?.let { it::class }, actual.exceptionOrNull()?.let { it::class })
        assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
        assertEquals(pair[0].trace, pair[1].trace)
        assertEquals(pair[0].measuredSizes, pair[1].measuredSizes)
        return actual.exceptionOrNull()
    }

    private fun execute(measure: MeasureNode, layout: LayoutNode, scope: FlowMeasurementScope, bounds: Constraints): Result<IntSize> =
        runCatching {
            measure.measure(scope, bounds).also {
                scope.size = it
                layout.layout(scope)
            }
        }
}
