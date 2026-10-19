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
import dev.s7a.strata.modifier.onActivate
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
        val empty = Modifier
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
        assertEquals(empty, Modifier)
    }

    @Test
    fun invalidSizeArgumentsFailAtExtensionConstruction() {
        assertThrows(IllegalArgumentException::class.java) { Modifier.size(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.size(1, -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.width(-1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.height(-1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.sizeIn(minWidth = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.sizeIn(minHeight = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.sizeIn(maxWidth = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.sizeIn(maxHeight = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.sizeIn(minWidth = 2, maxWidth = 1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.sizeIn(minHeight = 2, maxHeight = 1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.widthIn(min = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.widthIn(max = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.widthIn(min = 2, max = 1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.heightIn(min = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.heightIn(max = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.heightIn(min = 2, max = 1) }
    }

    @Test
    fun invalidPaddingArgumentsFailAtExtensionConstruction() {
        assertThrows(IllegalArgumentException::class.java) { Modifier.padding(-1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.padding(horizontal = -1, vertical = 0) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.padding(horizontal = 0, vertical = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.padding(left = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.padding(top = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.padding(right = -1) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.padding(bottom = -1) }
        assertThrows(ArithmeticException::class.java) { Modifier.padding(Int.MAX_VALUE) }
        assertThrows(ArithmeticException::class.java) {
            Modifier.padding(horizontal = Int.MAX_VALUE, vertical = 0)
        }
        assertThrows(ArithmeticException::class.java) {
            Modifier.padding(left = Int.MAX_VALUE, right = 1)
        }
        assertThrows(ArithmeticException::class.java) {
            Modifier.padding(top = Int.MAX_VALUE, bottom = 1)
        }
    }

    @Test
    fun builtInTokensReportOnlyTheirDeclaredUpdatePhases() {
        assertUpdateMask(
            first = Modifier.size(4, 4),
            second = Modifier.size(5, 4),
            expected = DirtyMask.of(DirtyPhase.Measure),
        )
        assertUpdateMask(
            first = Modifier.padding(1),
            second = Modifier.padding(2),
            expected = DirtyMask.of(DirtyPhase.Measure),
        )
        assertUpdateMask(
            first = Modifier.background(ArgbColor(0xFF000000.toInt())),
            second = Modifier.background(ArgbColor(0xFFFFFFFF.toInt())),
            expected = DirtyMask.of(DirtyPhase.Paint),
        )
        assertUpdateMask(
            first = Modifier.semantics(Semantics(label = UiText.Literal("first"))),
            second = Modifier.semantics(Semantics(label = UiText.Literal("second"))),
            expected = DirtyMask.of(DirtyPhase.Semantics),
        )
        assertUpdateMask(
            first = Modifier.onPress { _, _ -> InputResult.Ignored },
            second = Modifier.onPress { _, _ -> InputResult.Consumed },
            expected = DirtyMask.None,
        )
    }

    @Test
    fun everySizeFactorySharesOneStableToken() {
        val modifiers =
            listOf(
                Modifier.size(1, 2),
                Modifier.width(1),
                Modifier.height(2),
                Modifier.sizeIn(minWidth = 1, maxWidth = 3, minHeight = 2, maxHeight = 4),
                Modifier.widthIn(min = 1, max = 3),
                Modifier.heightIn(min = 2, max = 4),
                Modifier.fillMaxSize(),
                Modifier.fillMaxWidth(),
                Modifier.fillMaxHeight(),
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
                Modifier.onPointerEvent { _, _ -> InputResult.Ignored },
                Modifier.onPress { _, _ -> InputResult.Ignored },
                Modifier.onPress {},
                Modifier.onRelease { _, _ -> InputResult.Ignored },
                Modifier.onRelease {},
                Modifier.onMove { _, _ -> InputResult.Ignored },
                Modifier.onMove {},
                Modifier.onDrag { _, _ -> InputResult.Ignored },
                Modifier.onDrag {},
                Modifier.onScroll { _, _ -> InputResult.Ignored },
                Modifier.onScroll {},
                Modifier.onHover {},
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
    fun activationComposesStablePointerAndFocusedInputNodesOnlyWhenEnabled() {
        val empty = Modifier
        val first = empty.onActivate {}
        val second = empty.onActivate {}
        val disabled = empty.onActivate(enabled = false) {}

        assertEquals(2, first.elements().size)
        assertNotSame(empty, first)
        assertSame(empty, disabled)
        first.elements().indices.forEach { index ->
            val previous = first.elements()[index]
            val current = second.elements()[index]
            assertSame(previous.type, current.type)
            val node = previous.type.createErased(previous)
            assertEquals(DirtyMask.None, previous.type.updateErased(previous, current, node))
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
