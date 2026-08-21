package dev.s7a.strata

import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxHeight
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.fillMaxWidth
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.heightIn
import dev.s7a.strata.modifier.onDrag
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onMove
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.onRelease
import dev.s7a.strata.modifier.onScroll
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.sizeIn
import dev.s7a.strata.modifier.width
import dev.s7a.strata.modifier.widthIn
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the public built-in modifier factories and their typed update bridges.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class BuiltinModifierContractTest {
    @Test
    fun insetsValidateEdgesTotalsCopiesFactoriesAndEquality() {
        assertThrows(IllegalArgumentException::class.java) { Insets(left = -1) }
        assertThrows(IllegalArgumentException::class.java) { Insets(top = -1) }
        assertThrows(IllegalArgumentException::class.java) { Insets(right = -1) }
        assertThrows(IllegalArgumentException::class.java) { Insets(bottom = -1) }
        assertThrows(ArithmeticException::class.java) { Insets(left = Int.MAX_VALUE, right = 1) }
        assertThrows(ArithmeticException::class.java) { Insets(top = Int.MAX_VALUE, bottom = 1) }

        val source = Insets(left = 1, top = 2, right = 3, bottom = 4)
        assertThrows(IllegalArgumentException::class.java) { source.copy(top = -1) }
        assertThrows(IllegalArgumentException::class.java) { Insets.all(-1) }
        assertThrows(IllegalArgumentException::class.java) { Insets.symmetric(horizontal = -1, vertical = 0) }
        assertThrows(IllegalArgumentException::class.java) { Insets.symmetric(horizontal = 0, vertical = -1) }
        assertThrows(ArithmeticException::class.java) { source.copy(left = Int.MAX_VALUE, right = 1) }
        assertThrows(ArithmeticException::class.java) { Insets.all(Int.MAX_VALUE) }
        assertThrows(ArithmeticException::class.java) { Insets.symmetric(Int.MAX_VALUE, 0) }
        assertEquals(Insets.Zero, Insets())
        assertEquals(Insets(2, 2, 2, 2), Insets.all(2))
        assertEquals(Insets(3, 4, 3, 4), Insets.symmetric(3, 4))
        assertEquals(source, Insets(left = 1, top = 2, right = 3, bottom = 4))
    }

    @Test
    fun eachFactoryAppendsExactlyOneDescriptionAndPreservesEarlierChains() {
        val empty = Modifier.Empty
        val padding = empty.padding(Insets.all(1))
        val size = padding.size(4, 5)
        val width = size.width(6)
        val height = width.height(7)
        val sizeIn = height.sizeIn(minWidth = 1, maxWidth = 8, minHeight = 2, maxHeight = 9)
        val widthIn = sizeIn.widthIn(min = 2, max = 10)
        val heightIn = widthIn.heightIn(min = 3, max = 11)
        val fillSize = heightIn.fillMaxSize()
        val fillWidth = fillSize.fillMaxWidth()
        val fillHeight = fillWidth.fillMaxHeight()
        val background = fillHeight.background(ArgbColor(0xFF112233.toInt()))
        val semantics = background.semantics(Semantics(label = UiText.Literal("label")))
        val pointer = semantics.onPointerEvent { _, _ -> InputResult.Ignored }

        assertEquals(0, empty.elements().size)
        assertEquals(1, padding.elements().size)
        assertEquals(2, size.elements().size)
        assertEquals(3, width.elements().size)
        assertEquals(4, height.elements().size)
        assertEquals(5, sizeIn.elements().size)
        assertEquals(6, widthIn.elements().size)
        assertEquals(7, heightIn.elements().size)
        assertEquals(8, fillSize.elements().size)
        assertEquals(9, fillWidth.elements().size)
        assertEquals(10, fillHeight.elements().size)
        assertEquals(11, background.elements().size)
        assertEquals(12, semantics.elements().size)
        assertEquals(13, pointer.elements().size)
        assertNotSame(empty, pointer)
        assertSame(padding.elements()[0].type, size.elements()[0].type)
        assertEquals(empty, Modifier.Empty)
    }

    @Test
    fun invalidSizeArgumentsFailAtExtensionConstruction() {
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.size(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.size(1, -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.width(-1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.height(-1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.sizeIn(minWidth = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.sizeIn(minHeight = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.sizeIn(maxWidth = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.sizeIn(maxHeight = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.sizeIn(minWidth = 2, maxWidth = 1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.sizeIn(minHeight = 2, maxHeight = 1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.widthIn(min = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.widthIn(max = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.widthIn(min = 2, max = 1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.heightIn(min = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.heightIn(max = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.heightIn(min = 2, max = 1) }
    }

    @Test
    fun invalidPaddingArgumentsFailAtExtensionConstruction() {
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.padding(-1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.padding(horizontal = -1, vertical = 0) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.padding(horizontal = 0, vertical = -1) }
        assertThrows(ArithmeticException::class.java) { Modifier.Empty.padding(Int.MAX_VALUE) }
        assertThrows(ArithmeticException::class.java) {
            Modifier.Empty.padding(horizontal = Int.MAX_VALUE, vertical = 0)
        }
    }

    @Test
    fun builtInTokensReportOnlyTheirDeclaredUpdatePhases() {
        assertUpdateMask(
            first = Modifier.Empty.size(4, 4),
            second = Modifier.Empty.size(5, 4),
            expected = DirtyMask.of(DirtyPhase.Measure),
        )
        assertUpdateMask(
            first = Modifier.Empty.padding(1),
            second = Modifier.Empty.padding(2),
            expected = DirtyMask.of(DirtyPhase.Measure),
        )
        assertUpdateMask(
            first = Modifier.Empty.background(ArgbColor(0xFF000000.toInt())),
            second = Modifier.Empty.background(ArgbColor(0xFFFFFFFF.toInt())),
            expected = DirtyMask.of(DirtyPhase.Paint),
        )
        assertUpdateMask(
            first = Modifier.Empty.semantics(Semantics(label = UiText.Literal("first"))),
            second = Modifier.Empty.semantics(Semantics(label = UiText.Literal("second"))),
            expected = DirtyMask.of(DirtyPhase.Semantics),
        )
        assertUpdateMask(
            first = Modifier.Empty.onPress { _, _ -> InputResult.Ignored },
            second = Modifier.Empty.onPress { _, _ -> InputResult.Consumed },
            expected = DirtyMask.None,
        )
    }

    @Test
    fun everySizeFactorySharesOneStableToken() {
        val modifiers =
            listOf(
                Modifier.Empty.size(1, 2),
                Modifier.Empty.width(1),
                Modifier.Empty.height(2),
                Modifier.Empty.sizeIn(minWidth = 1, maxWidth = 3, minHeight = 2, maxHeight = 4),
                Modifier.Empty.widthIn(min = 1, max = 3),
                Modifier.Empty.heightIn(min = 2, max = 4),
                Modifier.Empty.fillMaxSize(),
                Modifier.Empty.fillMaxWidth(),
                Modifier.Empty.fillMaxHeight(),
            )
        val token =
            modifiers
                .first()
                .elements()
                .single()
                .type

        modifiers.drop(1).forEach { modifier ->
            assertSame(token, modifier.elements().single().type)
        }
    }

    @Test
    fun everyPointerActionFactorySharesOneStableToken() {
        val modifiers =
            listOf(
                Modifier.Empty.onPointerEvent { _, _ -> InputResult.Ignored },
                Modifier.Empty.onPress { _, _ -> InputResult.Ignored },
                Modifier.Empty.onPress {},
                Modifier.Empty.onRelease { _, _ -> InputResult.Ignored },
                Modifier.Empty.onRelease {},
                Modifier.Empty.onMove { _, _ -> InputResult.Ignored },
                Modifier.Empty.onMove {},
                Modifier.Empty.onDrag { _, _ -> InputResult.Ignored },
                Modifier.Empty.onDrag {},
                Modifier.Empty.onScroll { _, _ -> InputResult.Ignored },
                Modifier.Empty.onScroll {},
                Modifier.Empty.onHover {},
            )
        val token =
            modifiers
                .first()
                .elements()
                .single()
                .type

        modifiers.drop(1).forEach { modifier ->
            assertSame(token, modifier.elements().single().type)
        }
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
