package dev.s7a.strata

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardInputFilter
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Portable event schemas and subscription rules have identical JVM and JavaScript behavior.
 */
class ProjectionInputCodecTest {
    @Test
    fun preservesEveryExistingInputVariantAndCapturedCoordinates() {
        val modifiers = KeyboardModifiers(true, true, true, true, true, true)
        listOf(KeyboardEvent.Press(KeyCode('S'.code), 31, modifiers), KeyboardEvent.Release(KeyCode.Unknown, -1)).forEach {
            assertEquals(it, ProjectionInputCodec.keyboard(ProjectionInputCodec.keyboard(it)))
        }
        val position = IntOffset(Int.MIN_VALUE, Int.MAX_VALUE)
        val local = IntOffset(-34, 72)
        listOf(
            PointerEvent.Press(position, PointerButton.Secondary),
            PointerEvent.Release(position, PointerButton.Auxiliary(8)),
            PointerEvent.Move(position),
            PointerEvent.Drag(position, PointerButton.Middle, -0.25, 10.5),
            PointerEvent.Scroll(position, 0.5, -3.75),
        ).forEach { assertEquals(it to local, ProjectionInputCodec.pointer(ProjectionInputCodec.pointer(it, local))) }
        listOf(TextInputEvent.Character(0x1F642), TextInputEvent.Preedit("日本語", 2, listOf("日本", "語"), 1)).forEach {
            assertEquals(it, ProjectionInputCodec.text(ProjectionInputCodec.text(it)))
        }
    }

    @Test
    fun shortcutsSnapshotTheirKeySetAndIgnoreLockKeys() {
        val keys = mutableSetOf(KeyCode('S'.code))
        val filter = KeyboardInputFilter(keys, KeyboardModifiers(control = true))
        keys.clear()
        val event = KeyboardEvent.Press(KeyCode('S'.code), 31, KeyboardModifiers(control = true, capsLock = true, numLock = true))
        assertTrue(filter.matches(event))
        assertFalse(filter.matches(event.copy(modifiers = KeyboardModifiers(control = true, shift = true))))
        assertFalse(filter.matches(event.copy(key = KeyCode.Enter)))
        assertEquals(filter, ProjectionInputCodec.filter(ProjectionInputCodec.filter(filter)))
    }

    @Test
    fun rejectsMalformedVariantsRangesAndTrailingData() {
        val event = ProjectionInputCodec.text(TextInputEvent.Character(65)) as ProjectionValue.Sequence
        assertFailsWith<IllegalArgumentException> { ProjectionInputCodec.text(ProjectionValue.Sequence(event.values + ProjectionValue.Absent)) }
        assertFailsWith<IllegalArgumentException> { ProjectionInputCodec.text(ProjectionValue.Sequence(listOf(event.values.first(), ProjectionValue.Integer(0xD800)))) }
        val keyboard = ProjectionInputCodec.keyboard(KeyboardEvent.Press(KeyCode.Enter, 0)) as ProjectionValue.Sequence
        assertFailsWith<IllegalArgumentException> { ProjectionInputCodec.keyboard(ProjectionValue.Sequence(listOf(ProjectionValue.Integer(99)) + keyboard.values.drop(1))) }
        assertFailsWith<IllegalArgumentException> { ProjectionInputCodec.pointer(ProjectionValue.Sequence(emptyList())) }
        val button = ProjectionInputCodec.button(PointerButton.Auxiliary(0)) as ProjectionValue.Sequence
        assertFailsWith<IllegalArgumentException> { ProjectionInputCodec.button(ProjectionValue.Sequence(listOf(button.values.first(), ProjectionValue.Integer(-1)))) }
    }
}
