package dev.s7a.strata.projection

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardInputFilter
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent

/**
 * Detached standard input properties shared by declaration projections and installed client adapters.
 * Decoders reject malformed variants, ranges, and trailing fields before handlers run.
 * The enclosing transport owns aggregate byte, collection, and nesting limits.
 */
@Suppress("TooManyFunctions") // One paired schema boundary for the existing platform-neutral input families.
public object ProjectionInputCodec {
    /**
     * Encodes the physical key, scan code, and complete captured modifier state.
     */
    public fun keyboard(event: KeyboardEvent): ProjectionValue =
        fields(
            number(
                when (event) {
                    is KeyboardEvent.Press -> KeyKind.Press
                    is KeyboardEvent.Release -> KeyKind.Release
                }.ordinal,
            ),
            number(event.key.value),
            number(event.scanCode),
            modifiers(event.modifiers),
        )

    /**
     * Decodes a keyboard event, preserving unknown keys and platform scan codes.
     */
    public fun keyboard(value: ProjectionValue): KeyboardEvent {
        val fields = ProjectionFields(value)
        val kind = KeyKind.entries[fields.int(KeyKind.entries.indices)]
        val key = KeyCode(fields.int(-1..Int.MAX_VALUE))
        val scanCode = fields.int()
        val modifiers = modifiers(fields.value())
        fields.finish()
        return when (kind) {
            KeyKind.Press -> KeyboardEvent.Press(key, scanCode, modifiers)
            KeyKind.Release -> KeyboardEvent.Release(key, scanCode, modifiers)
        }
    }

    /**
     * Encodes both tree coordinates and modifier-local coordinates, including out-of-bounds captured input.
     */
    public fun pointer(
        event: PointerEvent,
        localPosition: IntOffset,
    ): ProjectionValue {
        val common = listOf(number(event.position.x), number(event.position.y), number(localPosition.x), number(localPosition.y))
        val (kind, details) =
            when (event) {
                is PointerEvent.Press -> PointerKind.Press to listOf(button(event.button))
                is PointerEvent.Release -> PointerKind.Release to listOf(button(event.button))
                is PointerEvent.Move -> PointerKind.Move to emptyList()
                is PointerEvent.Drag -> PointerKind.Drag to listOf(button(event.button), ProjectionValue.Real(event.deltaX), ProjectionValue.Real(event.deltaY))
                is PointerEvent.Scroll -> PointerKind.Scroll to listOf(ProjectionValue.Real(event.deltaX), ProjectionValue.Real(event.deltaY))
            }
        return ProjectionValue.Sequence(listOf(number(kind.ordinal)) + common + details)
    }

    /**
     * Decodes one pointer event together with its modifier-local position.
     */
    public fun pointer(value: ProjectionValue): Pair<PointerEvent, IntOffset> {
        val fields = ProjectionFields(value)
        val kind = PointerKind.entries[fields.int(PointerKind.entries.indices)]
        val position = IntOffset(fields.int(), fields.int())
        val localPosition = IntOffset(fields.int(), fields.int())
        val event =
            when (kind) {
                PointerKind.Press -> PointerEvent.Press(position, button(fields.value()))
                PointerKind.Release -> PointerEvent.Release(position, button(fields.value()))
                PointerKind.Move -> PointerEvent.Move(position)
                PointerKind.Drag -> PointerEvent.Drag(position, button(fields.value()), fields.real(), fields.real())
                PointerKind.Scroll -> PointerEvent.Scroll(position, fields.real(), fields.real())
            }
        fields.finish()
        return event to localPosition
    }

    /**
     * Encodes committed Unicode scalars or complete immutable IME composition notifications.
     */
    public fun text(event: TextInputEvent): ProjectionValue =
        when (event) {
            is TextInputEvent.Character -> fields(number(TextKind.Character.ordinal), number(event.codePoint))
            is TextInputEvent.Preedit -> fields(number(TextKind.Preedit.ordinal), ProjectionValue.Text(event.fullText), number(event.caretPosition), ProjectionValue.Sequence(event.blocks.map(ProjectionValue::Text)), number(event.focusedBlock))
        }

    /**
     * Validates Unicode scalars and composition caret/block bounds through the existing input constructors.
     */
    public fun text(value: ProjectionValue): TextInputEvent {
        val fields = ProjectionFields(value)
        val event =
            when (TextKind.entries[fields.int(TextKind.entries.indices)]) {
                TextKind.Character -> TextInputEvent.Character(fields.int())
                TextKind.Preedit -> TextInputEvent.Preedit(fields.text(), fields.int(), fields.values().map { requireNotNull(it as? ProjectionValue.Text).value }, fields.int())
            }
        fields.finish()
        return event
    }

    /**
     * Encodes exact physical-key membership and optional held-modifier matching.
     */
    public fun filter(filter: KeyboardInputFilter): ProjectionValue =
        fields(
            ProjectionValue.Sequence(filter.keys.sortedBy(KeyCode::value).map { number(it.value) }),
            filter.modifiers?.let(::modifiers) ?: ProjectionValue.Absent,
        )

    /**
     * Decodes a detached keyboard subscription before installing client listeners.
     */
    public fun filter(value: ProjectionValue): KeyboardInputFilter {
        val fields = ProjectionFields(value)
        val keys =
            fields.values().map {
                KeyCode(
                    requireNotNull(it as? ProjectionValue.Integer).value.let { value ->
                        require(value in -1L..Int.MAX_VALUE.toLong())
                        value.toInt()
                    },
                )
            }
        require(keys.distinct().size == keys.size) { "Duplicate subscribed key." }
        val modifiers = fields.value().let { if (it === ProjectionValue.Absent) null else modifiers(it) }
        fields.finish()
        return KeyboardInputFilter(keys.toSet(), modifiers)
    }

    /**
     * Encodes one logical button without conflating auxiliary and named buttons.
     */
    public fun button(button: PointerButton): ProjectionValue =
        when (button) {
            PointerButton.Primary -> fields(number(ButtonKind.Primary.ordinal))
            PointerButton.Secondary -> fields(number(ButtonKind.Secondary.ordinal))
            PointerButton.Middle -> fields(number(ButtonKind.Middle.ordinal))
            is PointerButton.Auxiliary -> fields(number(ButtonKind.Auxiliary.ordinal), number(button.index))
        }

    /**
     * Decodes named or nonnegative auxiliary button identities.
     */
    public fun button(value: ProjectionValue): PointerButton {
        val fields = ProjectionFields(value)
        val button =
            when (ButtonKind.entries[fields.int(ButtonKind.entries.indices)]) {
                ButtonKind.Primary -> PointerButton.Primary
                ButtonKind.Secondary -> PointerButton.Secondary
                ButtonKind.Middle -> PointerButton.Middle
                ButtonKind.Auxiliary -> PointerButton.Auxiliary(fields.int(0..Int.MAX_VALUE))
            }
        fields.finish()
        return button
    }

    private fun modifiers(value: KeyboardModifiers): ProjectionValue =
        fields(
            ProjectionValue.Flag(value.shift),
            ProjectionValue.Flag(value.control),
            ProjectionValue.Flag(value.alt),
            ProjectionValue.Flag(value.superKey),
            ProjectionValue.Flag(value.capsLock),
            ProjectionValue.Flag(value.numLock),
        )

    private fun modifiers(value: ProjectionValue): KeyboardModifiers {
        val fields = ProjectionFields(value)
        val result = KeyboardModifiers(fields.flag(), fields.flag(), fields.flag(), fields.flag(), fields.flag(), fields.flag())
        fields.finish()
        return result
    }

    private fun number(value: Int): ProjectionValue = ProjectionValue.Integer(value.toLong())

    private fun fields(vararg values: ProjectionValue): ProjectionValue = ProjectionValue.Sequence(values.toList())

    private enum class KeyKind { Press, Release }

    private enum class PointerKind { Press, Release, Move, Drag, Scroll }

    private enum class TextKind { Character, Preedit }

    private enum class ButtonKind { Primary, Secondary, Middle, Auxiliary }
}
