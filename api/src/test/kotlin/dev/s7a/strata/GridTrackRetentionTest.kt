@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.GridElement
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Inspects actual primitive arrays without adding a diagnostic capability to production nodes.
 */
internal class GridTrackRetentionTest {
    @Test
    fun largeSmallAndEmptyChurnRetainsOnlyExactCurrentTrackLengths() {
        val node = GridElement.Node(64, 0, 0, Alignment.TopStart)
        repeat(20) {
            for (count in listOf(4_096, 1, 128, 0)) {
                val previous = tracks(node)
                node.measure(scope(count), Constraints())
                val current = tracks(node)
                assertEquals(listOf(minOf(64, count), count / 64 + if (count % 64 == 0) 0 else 1), current.map { it?.size })
                current.zip(previous).forEach { (value, old) -> assertNotSame(old, value) }
            }
        }
        node.dispose()
        assertEquals(listOf(null, null), tracks(node))
    }

    @Test
    fun replacementRemainsUnpublishedThroughChildCallbacksAndFailure() {
        val node = GridElement.Node(2, 0, 0, Alignment.TopStart)
        node.measure(scope(2), Constraints())
        val failure = IllegalStateException("pending measurement")
        val pending =
            scope(3) { index ->
                assertEquals(listOf(null, null), tracks(node))
                if (index == 2) throw failure
            }
        assertSame(failure, assertThrows<IllegalStateException> { node.measure(pending, Constraints()) })
        assertEquals(listOf(null, null), tracks(node))
        node.measure(scope(1), Constraints())
        assertThrows<ArithmeticException> {
            node.measure(scope(2, size = IntSize(Int.MAX_VALUE, 1)), Constraints())
        }
        assertEquals(listOf(null, null), tracks(node))
    }

    @Test
    fun ownersAreIsolatedAndDisposedReachableNodesContainNoTrackReferences() {
        val first = GridElement.Node(2, 0, 0, Alignment.TopStart)
        val second = GridElement.Node(2, 0, 0, Alignment.TopStart)
        first.measure(scope(4), Constraints())
        second.measure(scope(4), Constraints())
        tracks(first).zip(tracks(second)).forEach { (a, b) -> assertNotSame(a, b) }
        val secondTracks = tracks(second)
        first.detach()
        first.dispose()
        assertEquals(listOf(null, null), tracks(first))
        tracks(second).zip(secondTracks).forEach { (a, b) -> assertSame(a, b) }
        val referenceFields = first.javaClass.declaredFields.filter { it.type.isPrimitive.not() }
        assertEquals(setOf(Alignment::class.java, IntArray::class.java), referenceFields.map { it.type }.toSet())
        referenceFields.filter { it.type == IntArray::class.java }.forEach { field ->
            field.isAccessible = true
            assertNull(field.get(first))
        }
        second.dispose()
    }

    private fun tracks(node: GridElement.Node): List<IntArray?> =
        listOf("columnWidths", "rowHeights").map { name ->
            node.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(node) as IntArray?
        }

    private fun scope(
        count: Int,
        size: IntSize = IntSize(1, 1),
        before: (Int) -> Unit = {},
    ): MeasureScope =
        object : MeasureScope {
            override val childCount: Int = count

            override fun measureChild(
                index: Int,
                constraints: Constraints,
            ): IntSize {
                before(index)
                return constraints.constrain(size)
            }

            override fun <D : Any> childParentData(
                index: Int,
                key: ParentDataKey<D>,
            ): D? = null
        }
}
