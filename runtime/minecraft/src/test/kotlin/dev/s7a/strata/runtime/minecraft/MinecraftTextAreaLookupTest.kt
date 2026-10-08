package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Compares pointer placement with independent committed scalar navigation and exact retained editor pixels.
 * Composition mapping and failure cleanup run through the real owner-thread editor without a game or native IME.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTextAreaLookupTest {
    @Test
    fun ordinaryPressMatchesScalarPlacementPixelsAndSemanticsForPlateausAndSignedMetrics() {
        val size = IntSize(64, 26)
        val value = "AZB🙂C"
        val offsets = intArrayOf(0, 1, 2, 3, 5, 6)
        val advances = mapOf('A'.code to 4f, 'Z'.code to 0f, 'B'.code to -4f, 0x1F642 to 3f, 'C'.code to 0f)
        val positions = intArrayOf(0, 4, 4, 0, 3, 3)
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        MinecraftTextAreaFixture(glyph = { _, scalar -> MinecraftFontGlyph(advances[scalar] ?: 1f, 0f, 0f, 1f, 1f, image) }, cacheEntries = 0).use { fixture ->
            for (x in 0..8) {
                val boundary = positions.indices.minBy { index -> abs(positions[index].toLong() - x.toLong()) }
                val pointerState = TextAreaState(value)
                val scalarState = TextAreaState(value)
                UiTree().use { pointer ->
                    UiTree().use { scalar ->
                        pointer.update(fixture.description(pointerState, size))
                        scalar.update(fixture.description(scalarState, size))
                        fixture.frame(pointer, size)
                        fixture.frame(scalar, size)
                        fixture.key(scalar, KeyCode.Home, size, KeyboardModifiers(control = true))
                        repeat(boundary) { fixture.key(scalar, KeyCode.Right, size) }
                        val calls = fixture.glyphCalls
                        pointer.dispatchPointer(PointerEvent.Press(IntOffset(4 + x, 4), PointerButton.Primary))
                        assertEquals(calls, fixture.glyphCalls)
                        val actual = fixture.frame(pointer, size)
                        val expected = fixture.frame(scalar, size)
                        assertPixels(expected, actual, size)
                        assertEquals(UiText.Literal(value), pointer.semantics().single().semantics.value)
                        fixture.input(pointer, TextInputEvent.Character('M'.code), size)
                        fixture.input(scalar, TextInputEvent.Character('M'.code), size)
                        assertEquals(value.substring(0, offsets[boundary]) + "M" + value.substring(offsets[boundary]), pointerState.value)
                        assertEquals(scalarState.value, pointerState.value)
                        assertPixels(fixture.frame(scalar, size), fixture.frame(pointer, size), size)
                    }
                }
                pointerState.observe {}.close()
                scalarState.observe {}.close()
            }
        }
    }

    @Test
    fun composedPressMapsBeforeInsideAndAfterSupplementaryPreeditWithoutCommittingIt() {
        val size = IntSize(64, 26)
        MinecraftTextAreaFixture().use { fixture ->
            for ((x, insertion) in listOf(0 to 0, 3 to 1, 6 to 1, 9 to 1, 12 to 2, 15 to 3)) {
                val state = TextAreaState("ABC")
                UiTree().use { tree ->
                    tree.update(fixture.description(state, size))
                    fixture.frame(tree, size)
                    fixture.key(tree, KeyCode.Home, size, KeyboardModifiers(control = true))
                    fixture.key(tree, KeyCode.Right, size)
                    fixture.input(tree, TextInputEvent.Preedit("🙂Z", 2, listOf("🙂", "Z"), 0), size)
                    tree.dispatchPointer(PointerEvent.Press(IntOffset(4 + x, 4), PointerButton.Primary))
                    fixture.frame(tree, size)
                    assertEquals("ABC", state.value)
                    fixture.input(tree, TextInputEvent.Character('M'.code), size)
                    assertEquals("ABC".substring(0, insertion) + "M" + "ABC".substring(insertion), state.value)
                }
            }
        }
    }

    @Test
    fun inputFailureAndDetachReleaseTheCurrentLayoutAndBorrowedInputs() {
        MinecraftTextAreaFixture().use { fixture ->
            val state = TextAreaState("AB🙂")
            val failure = IllegalArgumentException("Rejected editor invalidation")
            var reject = false
            val editor = MinecraftTextAreaEditor(fixture.configuration(state)) { if (reject) throw failure }
            editor.attach()
            editor.measure()
            val layout = MinecraftTextAreaEditor::class.java.getDeclaredField("layout").apply { isAccessible = true }
            val previous = layout.get(editor)
            editor.detach()
            assertNull(layout.get(editor))
            state.observe {}.close()
            editor.attach()
            editor.measure()
            assertEquals(false, previous === layout.get(editor))
            reject = true
            try {
                assertSame(failure, assertThrows(IllegalArgumentException::class.java) { editor.pointer(PointerEvent.Press(IntOffset(4, 4), PointerButton.Primary), IntOffset(4, 4)) })
            } finally {
                editor.dispose()
            }
            assertNull(layout.get(editor))
            assertNull(MinecraftTextAreaEditor::class.java.getDeclaredField("current").apply { isAccessible = true }.get(editor))
            state.observe {}.close()
        }
    }

    private fun assertPixels(
        expected: List<DrawCommand>,
        actual: List<DrawCommand>,
        size: IntSize,
    ) {
        for (scale in 1..3) {
            assertArrayEquals(rasterizeHeadless(expected, size, scale).copyArgb(), rasterizeHeadless(actual, size, scale).copyArgb())
        }
    }
}
