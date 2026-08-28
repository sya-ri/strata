package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Counts actual editor invalidation callbacks and CPU glyph work without a timing assertion or production test hook.
 */
internal class MinecraftTextAreaInvalidationTest {
    @Test
    fun boundaryKeysPointerMaxLengthAndClampedWheelAreTrueNoOps() {
        MinecraftTextAreaFixture(cacheEntries = 0).use { fixture ->
            val state = TextAreaState("A", maxLength = 1)
            val dirty = mutableListOf<DirtyMask>()
            val editor = MinecraftTextAreaEditor(fixture.configuration(state)) { dirty.add(it) }
            try {
                editor.attach()
                editor.measure()
                editor.focus(true)
                assertEquals(listOf(paint), dirty)
                dirty.clear()
                val calls = fixture.glyphCalls
                listOf(KeyCode.Right, KeyCode.End, KeyCode.Down, KeyCode.PageDown, KeyCode.Delete).forEach { editor.keyboard(KeyboardEvent.Press(it, 0)) }
                editor.pointer(PointerEvent.Press(IntOffset(7, 4), PointerButton.Primary), IntOffset(7, 4))
                editor.textInput(TextInputEvent.Character('B'.code))
                editor.pointer(PointerEvent.Scroll(IntOffset(4, 4), 0.0, 0.0), IntOffset(4, 4))
                editor.pointer(PointerEvent.Scroll(IntOffset(4, 4), 0.0, 1.0), IntOffset(4, 4))
                assertTrue(dirty.isEmpty())
                assertEquals(calls, fixture.glyphCalls)
                editor.keyboard(KeyboardEvent.Press(KeyCode.Home, 0))
                editor.measure()
                dirty.clear()
                listOf(KeyCode.Left, KeyCode.Home, KeyCode.Up, KeyCode.PageUp, KeyCode.Backspace).forEach { editor.keyboard(KeyboardEvent.Press(it, 0)) }
                assertTrue(dirty.isEmpty())
                editor.focus(false)
                assertEquals(listOf(paint), dirty)
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun acceptedEditsEmitOneInvalidationAndRejectedCommitOnlyInvalidatesWhenClearingComposition() {
        MinecraftTextAreaFixture().use { fixture ->
            val state = TextAreaState("", maxLength = 2)
            val dirty = mutableListOf<DirtyMask>()
            val editor = MinecraftTextAreaEditor(fixture.configuration(state)) { dirty.add(it) }
            try {
                editor.attach()
                editor.measure()
                editor.textInput(TextInputEvent.Character('A'.code))
                assertEquals(listOf(valueChange), dirty)
                assertEquals("A", state.value)
                editor.measure()
                dirty.clear()
                editor.textInput(TextInputEvent.Preedit("B", 1, listOf("B"), 0))
                editor.measure()
                dirty.clear()
                editor.textInput(TextInputEvent.Character(0x1F642))
                assertEquals(listOf(geometry), dirty)
                assertEquals("A", state.value)
                editor.measure()
                dirty.clear()
                editor.keyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
                assertEquals(listOf(valueChange), dirty)
                assertEquals("", state.value)
                editor.measure()
                dirty.clear()
                editor.keyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
                assertTrue(dirty.isEmpty())
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun preeditCaretAndBlockChurnReuseLayoutAndInvalidateOnlyTheirChangedPhases() {
        MinecraftTextAreaFixture(cacheEntries = 0).use { fixture ->
            val state = TextAreaState("A", maxLength = 6)
            val dirty = mutableListOf<DirtyMask>()
            val editor = MinecraftTextAreaEditor(fixture.configuration(state)) { dirty.add(it) }
            try {
                editor.attach()
                editor.measure()
                editor.textInput(TextInputEvent.Preedit("🙂B", 0, listOf("🙂", "B"), 0))
                editor.measure()
                dirty.clear()
                val calls = fixture.glyphCalls
                editor.textInput(TextInputEvent.Preedit("🙂B", 2, listOf("🙂", "B"), 0))
                assertEquals(listOf(geometry), dirty)
                editor.measure()
                assertEquals(calls, fixture.glyphCalls)
                dirty.clear()
                editor.textInput(TextInputEvent.Preedit("🙂B", 2, listOf("🙂", "B"), 1))
                assertEquals(listOf(paint), dirty)
                editor.measure()
                assertEquals(calls, fixture.glyphCalls)
                dirty.clear()
                editor.textInput(TextInputEvent.Preedit("🙂B", 2, listOf("🙂", "B"), 1))
                assertTrue(dirty.isEmpty())
                editor.textInput(TextInputEvent.Preedit("한B", 2, listOf("한", "B"), 1))
                assertEquals(listOf(geometry), dirty)
                editor.measure()
                assertTrue(calls < fixture.glyphCalls)
                dirty.clear()
                editor.focus(true)
                dirty.clear()
                editor.focus(false)
                assertEquals(listOf(geometry), dirty)
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun fractionalScrollOnlyPaintsOnIntegerCellChangesAndFollowSynchronizesThePaintKey() {
        MinecraftTextAreaFixture(cacheEntries = 0).use { fixture ->
            val state = TextAreaState("A\n".repeat(10) + "A")
            val dirty = mutableListOf<DirtyMask>()
            val editor = MinecraftTextAreaEditor(fixture.configuration(state)) { dirty.add(it) }
            try {
                editor.attach()
                editor.measure()
                val calls = fixture.glyphCalls
                state.scrollState.scrollTo(0.5)
                assertTrue(dirty.isEmpty())
                state.scrollState.scrollTo(1.0)
                assertEquals(listOf(paint), dirty)
                dirty.clear()
                editor.pointer(PointerEvent.Scroll(IntOffset(4, 4), 0.0, 0.01), IntOffset(4, 4))
                assertTrue(dirty.isEmpty())
                editor.pointer(PointerEvent.Scroll(IntOffset(4, 4), 0.0, 0.2), IntOffset(4, 4))
                assertEquals(listOf(paint), dirty)
                assertEquals(calls, fixture.glyphCalls)
                editor.keyboard(KeyboardEvent.Press(KeyCode.Home, 0, KeyboardModifiers(control = true)))
                editor.measure()
                editor.keyboard(KeyboardEvent.Press(KeyCode.End, 0, KeyboardModifiers(control = true)))
                editor.measure()
                assertEquals(81.0, state.scrollState.metrics.offset)
                dirty.clear()
                state.scrollState.scrollTo(0.0)
                assertEquals(listOf(paint), dirty)
                editor.measure()
                dirty.clear()
                state.scrollState.scrollTo(81.0)
                editor.measure()
                editor.update(fixture.configuration(state, IntSize(32, 44)))
                editor.measure()
                assertEquals(63.0, state.scrollState.metrics.offset)
                editor.update(fixture.configuration(state))
                editor.measure()
                dirty.clear()
                state.scrollState.scrollTo(81.0)
                assertEquals(listOf(paint), dirty)
            } finally {
                editor.dispose()
            }
        }
    }

    private val paint = DirtyMask.of(DirtyPhase.Paint)
    private val geometry = DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint)
    private val valueChange = DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint, DirtyPhase.Semantics)
}
