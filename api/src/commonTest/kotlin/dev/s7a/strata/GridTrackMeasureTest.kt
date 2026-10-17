@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.GridAlignmentParentData
import dev.s7a.strata.layout.GridElement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Verifies Grid's completed-measure tracks and one-read placement on JVM and JavaScript.
 */
internal class GridTrackMeasureTest {
    @Test
    fun nonuniformPartialAndEmptyGridsUseOneSizeReadAndPlacementPerChild() {
        for (count in listOf(0, 1, 128, 4_096)) {
            for (columns in listOf(1, 3, 64)) {
                val scope = Scope(List(count) { IntSize(it % 7 + 1, it % 5 + 1) })
                val node = node(columns)
                node.measure(scope, Constraints())
                node.layout(scope)
                assertEquals(List(count) { 1 }, scope.measureReads.toList())
                assertEquals(List(count) { 1 }, scope.sizeReads.toList())
                assertEquals(reference(scope.sizes, columns, Alignment.TopStart), scope.placements)
                node.dispose()
            }
        }
    }

    @Test
    fun defaultAndLiveParentAlignmentChangesReuseSuccessfulMeasurement() {
        val scope = Scope(listOf(IntSize(2, 1), IntSize(4, 3), IntSize(3, 2)))
        val previous = element(2, Alignment.TopStart)
        val current = element(2, Alignment.BottomEnd)
        val node = node(2)
        assertEquals(IntSize(8, 7), node.measure(scope, Constraints()))
        node.update(previous, current)
        node.layout(scope)
        assertEquals(reference(scope.sizes, 2, Alignment.BottomEnd), scope.placements)
        scope.placements.clear()
        scope.alignment = Alignment.Center
        node.layout(scope)
        assertEquals(reference(scope.sizes, 2, Alignment.Center), scope.placements)
        assertEquals(listOf(1, 1, 1), scope.measureReads.toList())
        assertEquals(listOf(2, 2, 2), scope.sizeReads.toList())
    }

    @Test
    fun equalCountSizeSwapAndNewConstraintsReplaceTrackMaxima() {
        val node = node(2)
        val first = Scope(listOf(IntSize(8, 2), IntSize(2, 8), IntSize(1, 1)))
        node.measure(first, Constraints())
        node.layout(first)
        val swapped = Scope(listOf(IntSize(2, 8), IntSize(8, 2), IntSize(1, 1)))
        val constraints = Constraints(maxWidth = 4, maxHeight = 3)
        assertEquals(IntSize(4, 3), node.measure(swapped, constraints))
        node.layout(swapped)
        assertEquals(reference(swapped.sizes, 2, Alignment.TopStart), swapped.placements)
        assertEquals(List(3) { constraints }, swapped.childConstraints)
    }

    @Test
    fun failedChildMeasurementAndCheckedNaturalExtentPublishNoTracks() {
        val node = node(2)
        node.measure(Scope(listOf(IntSize(1, 1))), Constraints())
        val failure = IllegalStateException("child measure")
        val failing = Scope(listOf(IntSize(2, 2), IntSize(3, 3)), failure)
        assertSame(failure, assertFailsWith<IllegalStateException> { node.measure(failing, Constraints()) })
        assertFailsWith<IllegalStateException> { node.layout(Scope(emptyList())) }
        node.measure(Scope(listOf(IntSize(1, 1))), Constraints())
        assertFailsWith<ArithmeticException> {
            node.measure(Scope(listOf(IntSize(Int.MAX_VALUE, 1), IntSize(1, 1))), Constraints())
        }
        assertFailsWith<IllegalStateException> { node.layout(Scope(emptyList())) }
    }

    @Test
    fun terminalDetachAndDisposeRequireAnotherCompletedMeasure() {
        val node = node(1)
        val scope = Scope(listOf(IntSize(1, 1)))
        node.attach()
        node.measure(scope, Constraints())
        node.detach()
        assertFailsWith<IllegalStateException> { node.layout(scope) }
        node.measure(scope, Constraints())
        node.dispose()
        node.dispose()
        assertFailsWith<IllegalStateException> { node.layout(scope) }
    }

    private fun node(columns: Int): GridElement.Node = GridElement.Node(columns, 1, 2, Alignment.TopStart)

    private fun element(
        columns: Int,
        alignment: Alignment,
    ): GridElement = GridElement(columns, 1, 2, alignment, null, emptyList(), Modifier)

    // Deliberately scans each track independently instead of repeating Grid's row-major aggregation.
    private fun reference(
        sizes: List<IntSize>,
        columns: Int,
        alignment: Alignment,
    ): List<IntOffset> {
        val widths =
            List(minOf(columns, sizes.size)) { column ->
                sizes.filterIndexed { index, _ -> index % columns == column }.maxOf { it.width }
            }
        val heights = sizes.chunked(columns).map { row -> row.maxOf { it.height } }
        val columnStarts = widths.runningFold(0) { offset, width -> offset + width + 1 }
        val rowStarts = heights.runningFold(0) { offset, height -> offset + height + 2 }
        return sizes.mapIndexed { index, size ->
            val column = index % columns
            val row = index / columns
            val slackX = widths[column] - size.width
            val slackY = heights[row] - size.height
            val x =
                when (alignment.horizontalAlignment) {
                    HorizontalAlignment.Start -> 0
                    HorizontalAlignment.Center -> slackX / 2
                    HorizontalAlignment.End -> slackX
                }
            val y =
                when (alignment.verticalAlignment) {
                    VerticalAlignment.Top -> 0
                    VerticalAlignment.Center -> slackY / 2
                    VerticalAlignment.Bottom -> slackY
                }
            IntOffset(columnStarts[column] + x, rowStarts[row] + y)
        }
    }

    /**
     * Callback-local test scope recording every direct operation without retaining it in the node.
     */
    private class Scope(
        initialSizes: List<IntSize>,
        private val measureFailure: Throwable? = null,
    ) : MeasureScope,
        LayoutScope {
        val sizes = initialSizes.toMutableList()
        var alignment: Alignment? = null
        val measureReads = IntArray(sizes.size)
        val sizeReads = IntArray(sizes.size)
        val placements = ArrayList<IntOffset>()
        val childConstraints = ArrayList<Constraints>()
        override val childCount: Int get() = sizes.size
        override val size: IntSize get() = IntSize.Zero

        override fun measureChild(
            index: Int,
            constraints: Constraints,
        ): IntSize {
            if (index == sizes.lastIndex) measureFailure?.let { throw it }
            measureReads[index] += 1
            childConstraints += constraints
            return constraints.constrain(sizes[index]).also { sizes[index] = it }
        }

        override fun measuredChildSize(index: Int): IntSize {
            sizeReads[index] += 1
            return sizes[index]
        }

        override fun placeChild(
            index: Int,
            offset: IntOffset,
        ) {
            assertEquals(placements.size, index)
            placements += offset
        }

        override fun <D : Any> childParentData(
            index: Int,
            key: ParentDataKey<D>,
        ): D? = alignment?.let { key.castErased(GridAlignmentParentData.Data(it)) }
    }
}
