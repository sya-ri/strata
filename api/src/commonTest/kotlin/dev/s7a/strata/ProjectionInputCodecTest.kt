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

    @Test
    fun keyboardFieldsHaveLiteralOrderAndIndependentNestedModifierSnapshots() {
        val all = KeyboardModifiers(true, true, true, true, true, true)
        val flags = sequence(ProjectionValue.Flag(true), ProjectionValue.Flag(true), ProjectionValue.Flag(true), ProjectionValue.Flag(true), ProjectionValue.Flag(true), ProjectionValue.Flag(true))
        val old = ProjectionInputCodec.keyboard(KeyboardEvent.Press(KeyCode.Enter, 28, all))
        assertEquals(sequence(number(0), number(KeyCode.Enter.value), number(28), flags), old)
        val fresh = ProjectionInputCodec.keyboard(KeyboardEvent.Release(KeyCode.Unknown, -1))
        assertEquals(sequence(number(1), number(-1), number(-1), sequence(ProjectionValue.Flag(false), ProjectionValue.Flag(false), ProjectionValue.Flag(false), ProjectionValue.Flag(false), ProjectionValue.Flag(false), ProjectionValue.Flag(false))), fresh)
        assertEquals(sequence(number(0), number(KeyCode.Enter.value), number(28), flags), old)
    }

    @Test
    fun textButtonsAndFiltersPreserveLiteralSchemasIncludingSmallControls() {
        listOf(65, 0x1F642).forEach { codePoint ->
            assertEquals(sequence(number(0), number(codePoint)), ProjectionInputCodec.text(TextInputEvent.Character(codePoint)))
        }
        listOf(
            TextInputEvent.Preedit("", 0, emptyList(), -1),
            TextInputEvent.Preedit("a", 1, listOf("a"), 0),
            TextInputEvent.Preedit("ab", 1, listOf("a", "b"), 1),
        ).forEach { event ->
            assertEquals(sequence(number(1), ProjectionValue.Text(event.fullText), number(event.caretPosition), ProjectionValue.Sequence(event.blocks.map(ProjectionValue::Text)), number(event.focusedBlock)), ProjectionInputCodec.text(event))
        }
        listOf(
            PointerButton.Primary to sequence(number(0)),
            PointerButton.Secondary to sequence(number(1)),
            PointerButton.Middle to sequence(number(2)),
            PointerButton.Auxiliary(8) to sequence(number(3), number(8)),
        ).forEach { (button, expected) -> assertEquals(expected, ProjectionInputCodec.button(button)) }
        listOf(emptySet(), setOf(KeyCode.Enter), setOf(KeyCode.Space, KeyCode.Enter, KeyCode.Unknown)).forEach { keys ->
            assertEquals(sequence(ProjectionValue.Sequence(keys.sortedBy(KeyCode::value).map { number(it.value) }), ProjectionValue.Absent), ProjectionInputCodec.filter(KeyboardInputFilter(keys)))
        }
    }

    @Test
    fun pointerControlsPreserveLiteralTreeAndOutOfBoundsLocalCoordinates() {
        val position = IntOffset(4, 5)
        val local = IntOffset(-34, 72)
        val prefix = listOf(number(4), number(5), number(-34), number(72))
        val cases =
            listOf(
                PointerEvent.Press(position, PointerButton.Primary) to listOf(number(0), sequence(number(0))),
                PointerEvent.Release(position, PointerButton.Secondary) to listOf(number(1), sequence(number(1))),
                PointerEvent.Move(position) to listOf(number(2)),
                PointerEvent.Drag(position, PointerButton.Auxiliary(8), -0.25, 10.5) to listOf(number(3), sequence(number(3), number(8)), ProjectionValue.Real(-0.25), ProjectionValue.Real(10.5)),
                PointerEvent.Scroll(position, 0.5, -3.75) to listOf(number(4), ProjectionValue.Real(0.5), ProjectionValue.Real(-3.75)),
            )
        cases.forEach { (event, expected) ->
            assertEquals(ProjectionValue.Sequence(listOf(expected.first()) + prefix + expected.drop(1)), ProjectionInputCodec.pointer(event, local))
        }
    }

    @Test
    fun everyDecoderRejectsTrailingFieldsAndWrongNestedFieldTypes() {
        val samples: List<Pair<ProjectionValue, (ProjectionValue) -> Any>> =
            listOf(
                ProjectionInputCodec.keyboard(KeyboardEvent.Press(KeyCode.Enter, 28)) to { value: ProjectionValue -> ProjectionInputCodec.keyboard(value) },
                ProjectionInputCodec.pointer(PointerEvent.Move(IntOffset(4, 5)), IntOffset(-34, 72)) to { value: ProjectionValue -> ProjectionInputCodec.pointer(value) },
                ProjectionInputCodec.text(TextInputEvent.Preedit("", 0, emptyList(), -1)) to { value: ProjectionValue -> ProjectionInputCodec.text(value) },
                ProjectionInputCodec.filter(KeyboardInputFilter()) to { value: ProjectionValue -> ProjectionInputCodec.filter(value) },
                ProjectionInputCodec.button(PointerButton.Primary) to { value: ProjectionValue -> ProjectionInputCodec.button(value) },
            )
        samples.forEach { (encoded, decode) ->
            val fields = (encoded as ProjectionValue.Sequence).values
            assertFailsWith<IllegalArgumentException> { decode(ProjectionValue.Sequence(fields + ProjectionValue.Absent)) }
            assertFailsWith<IllegalArgumentException> { decode(ProjectionValue.Sequence(fields.dropLast(1))) }
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectionInputCodec.keyboard(sequence(number(0), number(KeyCode.Enter.value), number(28), sequence(ProjectionValue.Flag(true))))
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectionInputCodec.filter(sequence(sequence(number(KeyCode.Enter.value), number(KeyCode.Enter.value)), ProjectionValue.Absent))
        }
    }

    private fun number(value: Int): ProjectionValue = ProjectionValue.Integer(value.toLong())

    private fun sequence(vararg values: ProjectionValue): ProjectionValue.Sequence = ProjectionValue.Sequence(values.toList())
}
