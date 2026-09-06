package dev.s7a.strata

import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the public scale-to-fit values, factory validation, and retained update bridge.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ScaleToFitModifierContractTest {
    @Test
    fun childTransformRequiresFinitePositiveScaleAndProvidesStableIdentity() {
        assertEquals(ChildTransform(scale = 1.0, offset = DoubleOffset.Zero), ChildTransform.Identity)
        assertSame(ChildTransform.Identity, ChildTransform.Identity)
        assertEquals(ChildTransform(scale = 2.5, offset = DoubleOffset(3.0, 4.0)), ChildTransform(2.5, DoubleOffset(3.0, 4.0)))

        assertThrows(IllegalArgumentException::class.java) { ChildTransform(0.0) }
        assertThrows(IllegalArgumentException::class.java) { ChildTransform(-1.0) }
        assertThrows(IllegalArgumentException::class.java) { ChildTransform(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ChildTransform(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) {
            ChildTransform(scale = 1.0, offset = DoubleOffset(Double.NEGATIVE_INFINITY, 0.0))
        }
    }

    @Test
    fun factoryRejectsEmptyContentAxesAndAppendsOneStableDescription() {
        assertThrows(IllegalArgumentException::class.java) {
            Modifier.Empty.scaleToFit(IntSize(0, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Modifier.Empty.scaleToFit(IntSize(1, 0))
        }

        val first = Modifier.Empty.scaleToFit(IntSize(100, 50))
        val second = Modifier.Empty.scaleToFit(IntSize(100, 50), Alignment.BottomEnd, allowUpscaling = true)
        assertEquals(1, first.elements().size)
        assertEquals(1, second.elements().size)
        assertSame(first.elements().single().type, second.elements().single().type)
    }

    @Test
    fun updateBridgeSeparatesMeasureLayoutAndEqualChanges() {
        assertUpdateMask(
            first = Modifier.Empty.scaleToFit(IntSize(100, 50)),
            second = Modifier.Empty.scaleToFit(IntSize(120, 50)),
            expected = DirtyMask.of(DirtyPhase.Measure),
        )
        assertUpdateMask(
            first = Modifier.Empty.scaleToFit(IntSize(100, 50)),
            second = Modifier.Empty.scaleToFit(IntSize(100, 50), contentAlignment = Alignment.TopStart),
            expected = DirtyMask.of(DirtyPhase.Layout),
        )
        assertUpdateMask(
            first = Modifier.Empty.scaleToFit(IntSize(100, 50)),
            second = Modifier.Empty.scaleToFit(IntSize(100, 50), allowUpscaling = true),
            expected = DirtyMask.of(DirtyPhase.Layout),
        )
        assertUpdateMask(
            first = Modifier.Empty.scaleToFit(IntSize(100, 50)),
            second = Modifier.Empty.scaleToFit(IntSize(100, 50)),
            expected = DirtyMask.None,
        )
    }

    private fun assertUpdateMask(
        first: Modifier,
        second: Modifier,
        expected: DirtyMask,
    ) {
        val firstElement = first.elements().single()
        val secondElement = second.elements().single()
        assertSame(firstElement.type, secondElement.type)
        val node = firstElement.type.createErased(firstElement)
        assertEquals(expected, firstElement.type.updateErased(firstElement, secondElement, node))
        assertEquals(DirtyMask.None, firstElement.type.updateErased(secondElement, secondElement, node))
    }
}
