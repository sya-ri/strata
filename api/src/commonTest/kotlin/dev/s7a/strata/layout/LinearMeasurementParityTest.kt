package dev.s7a.strata.layout

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Original complete algorithms independently check values, callback cutoffs and exceptional prefixes on JVM/JS.
 */
internal class LinearMeasurementParityTest {
    @Test
    fun weightedAllocationMatchesOriginalBoundsSizesAndOrdering() {
        for (orientation in orientations()) {
            for (count in listOf(0, 1, 16, 128, 4_096)) {
                val plans = listOf(
                    List<WeightParentData.Data?>(count) { null },
                    List(count) { index -> if (index == 0 || index == count - 1) WeightParentData.Data(if (index == 0) 1f else 3f, true) else null },
                    List(count) { index -> if (index % 2 == 0) WeightParentData.Data((index % 3 + 1).toFloat(), true) else null },
                    List(count) { index -> WeightParentData.Data((index % 3 + 1).toFloat(), false) },
                    List(count) { index -> WeightParentData.Data(if (index % 2 == 0) Float.MIN_VALUE else Float.MAX_VALUE, true) },
                )
                for (weights in plans) {
                    for (constraints in bounds(orientation)) {
                        compare(orientation, weights, constraints)
                    }
                }
            }
        }
    }

    @Test
    fun parentDataIsCapturedOnceBeforeAnyChildCallbackIncludingMutation() {
        for (orientation in orientations()) {
            for (count in listOf(1, 16, 128)) {
                for (weightedIndex in listOf<Int?>(null, 0, count - 1)) {
                    val weights = List(count) { index -> if (index == weightedIndex) WeightParentData.Data(1f, true) else null }
                    val pair = scopes(weights)
                    mutateWeightsOnMeasure(pair)
                    compare(orientation, pair, Constraints.fixed(320, 180))
                    val trace = pair.first().trace
                    assertEquals((0 until count).map(LinearMeasurementTrace::WeightRead), trace.take(count))
                    assertEquals(count, trace.filterIsInstance<LinearMeasurementTrace.WeightRead>().size)
                }
            }
        }
    }

    @Test
    fun fullLayoutPreflightSurvivesPlacementSideEffectsAndAllPolicies() {
        for (orientation in orientations()) {
            for (arrangement in Arrangement.entries) {
                val pair = scopes(List(16) { null }, overrideAlignment = true)
                pair.forEach { scope ->
                    scope.onPlace = { for (index in scope.measuredSizes.indices) scope.measuredSizes[index] = IntSize.Zero }
                }
                compare(orientation, pair, Constraints.fixed(320, 180), arrangement = arrangement)
                val reads = pair.first().trace.filterIsInstance<LinearMeasurementTrace.SizeRead>()
                assertEquals(16, reads.size)
                assertTrue(reads.all { it.size != IntSize.Zero })
            }
        }
    }

    @Test
    fun firstMiddleLastFailuresKeepOriginalSuccessfulAndExceptionalPrefixes() {
        for (orientation in orientations()) {
            val weights = List<WeightParentData.Data?>(16) { index -> if (index % 2 == 0) WeightParentData.Data(1f, true) else null }
            val successful = scopes(weights)
            compare(orientation, successful, Constraints.fixed(320, 180))
            val trace = successful.first().trace
            val groups = listOf(
                trace.filterIsInstance<LinearMeasurementTrace.WeightRead>(),
                trace.filterIsInstance<LinearMeasurementTrace.Measure>(),
                trace.filterIsInstance<LinearMeasurementTrace.SizeRead>(),
                trace.filterIsInstance<LinearMeasurementTrace.AlignmentRead>(),
                trace.filterIsInstance<LinearMeasurementTrace.Place>(),
            )
            for (events in groups) {
                for (event in listOf(events.first(), events[events.size / 2], events.last())) {
                    val cause = IllegalStateException("Injected linear callback failure")
                    val pair = scopes(weights, failure = LinearMeasurementScope.Failure(event, cause))
                    val failure = compare(orientation, pair, Constraints.fixed(320, 180))
                    assertSame(cause, failure)
                    assertEquals(trace.take(trace.indexOf(event) + 1), pair.first().trace)
                }
            }
        }
    }

    @Test
    fun checkedExtentAndSpacingFailuresKeepOriginalOrder() {
        for (orientation in orientations()) {
            val sizes = List(3) { IntSize(Int.MAX_VALUE, Int.MAX_VALUE) }
            compare(orientation, List(3) { null }, Constraints(), spacing = Int.MAX_VALUE, sizes = sizes)
            compare(orientation, List(3) { WeightParentData.Data(Float.MIN_VALUE, true) }, Constraints(), spacing = Int.MAX_VALUE, sizes = sizes)
        }
    }

    @Test
    fun commonBoundsAreInvocationLocalAndDistinctSlotsRemainExact() {
        for (orientation in orientations()) {
            val node = LinearElement.Node(orientation, 0, Arrangement.Start)
            val first = scopes(List(16) { null }).first()
            node.measure(first, Constraints.fixed(320, 180))
            assertTrue(first.bounds.all { it === first.bounds.first() })
            val second = scopes(List(16) { null }).first()
            node.measure(second, Constraints.fixed(321, 181))
            assertTrue(second.bounds.all { it === second.bounds.first() })
            assertTrue(first.bounds.first() !== second.bounds.first())
            val weighted = scopes(List(16) { WeightParentData.Data(1f, true) }).first()
            node.measure(weighted, intrinsic(orientation))
            assertTrue(weighted.bounds.all { it === weighted.bounds.first() })
        }
    }

    private fun mutateWeightsOnMeasure(pair: List<LinearMeasurementScope>) {
        pair.forEach { scope ->
            scope.onMeasure = {
                for (index in scope.weights.indices) scope.weights[index] = WeightParentData.Data(3f, false)
            }
        }
    }

    private fun orientations(): List<LinearOrientation> =
        VerticalAlignment.entries.map { LinearOrientation.Row(it) } +
            HorizontalAlignment.entries.map { LinearOrientation.Column(it) }

    private fun intrinsic(orientation: LinearOrientation): Constraints =
        if (orientation.axis == LinearAxis.Horizontal) Constraints(maxHeight = 180) else Constraints(maxWidth = 180)

    private fun bounds(orientation: LinearOrientation): List<Constraints> =
        listOf(Constraints.fixed(320, 180), Constraints.fixed(0, 0), Constraints(1, 321, 2, 181), intrinsic(orientation), Constraints())

    private fun scopes(
        weights: List<WeightParentData.Data?>,
        sizes: List<IntSize> = List(weights.size) { index -> IntSize(index % 3 + 1, index % 5 + 1) },
        failure: LinearMeasurementScope.Failure? = null,
        overrideAlignment: Boolean = false,
    ): List<LinearMeasurementScope> = List(2) { LinearMeasurementScope(weights.toMutableList(), sizes, failure, overrideAlignment) }

    private fun compare(
        orientation: LinearOrientation,
        weights: List<WeightParentData.Data?>,
        constraints: Constraints,
        spacing: Int = 1,
        sizes: List<IntSize> = List(weights.size) { index -> IntSize(index % 3 + 1, index % 5 + 1) },
    ): Throwable? = compare(orientation, scopes(weights, sizes), constraints, spacing)

    private fun compare(
        orientation: LinearOrientation,
        pair: List<LinearMeasurementScope>,
        constraints: Constraints,
        spacing: Int = 1,
        arrangement: Arrangement = Arrangement.Start,
    ): Throwable? {
        val reference = LinearMeasurementReference.Node(orientation, spacing, arrangement)
        val candidate = LinearElement.Node(orientation, spacing, arrangement)
        val expected = execute(reference, reference, pair[0], constraints)
        val actual = execute(candidate, candidate, pair[1], constraints)
        assertEquals(expected.getOrNull(), actual.getOrNull())
        assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
        assertEquals(expected.exceptionOrNull()?.let { it::class }, actual.exceptionOrNull()?.let { it::class })
        assertEquals(pair[0].trace, pair[1].trace)
        assertEquals(pair[0].measuredSizes, pair[1].measuredSizes)
        return actual.exceptionOrNull()
    }

    private fun execute(measure: MeasureNode, layout: LayoutNode, scope: LinearMeasurementScope, constraints: Constraints): Result<IntSize> =
        runCatching {
            measure.measure(scope, constraints).also {
                scope.size = it
                layout.layout(scope)
            }
        }
}
