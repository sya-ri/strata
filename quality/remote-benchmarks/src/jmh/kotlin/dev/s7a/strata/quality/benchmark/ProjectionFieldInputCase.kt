package dev.s7a.strata.quality.benchmark

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

/**
 * Frozen public input encoders; pointer cases deliberately retain the unchanged concatenation path.
 */
internal enum class ProjectionFieldInputCase(
    private val prepareInput: ProjectionFieldInputCase.() -> Prepared,
) {
    KeyboardPress({ keyboard(KeyboardEvent.Press(KeyCode.Enter, 28, KeyboardModifiers(true, true, true, true, true, true))) }),
    KeyboardRelease({ keyboard(KeyboardEvent.Release(KeyCode.Unknown, -1)) }),
    CharacterAscii({ text(TextInputEvent.Character(65)) }),
    CharacterSupplementary({ text(TextInputEvent.Character(0x1F642)) }),
    PreeditEmpty({ text(TextInputEvent.Preedit("", 0, emptyList(), -1)) }),
    PreeditShort({ text(TextInputEvent.Preedit("a", 1, listOf("a"), 0)) }),
    PreeditMultipart({ text(TextInputEvent.Preedit("ab", 1, listOf("a", "b"), 1)) }),
    FilterEmpty({ filter(KeyboardInputFilter()) }),
    FilterSingle({ filter(KeyboardInputFilter(setOf(KeyCode.Enter))) }),
    FilterMany({ filter(KeyboardInputFilter(setOf(KeyCode.Enter, KeyCode.Space, KeyCode.Unknown), KeyboardModifiers(control = true))) }),
    Primary({ button(PointerButton.Primary) }),
    Secondary({ button(PointerButton.Secondary) }),
    Middle({ button(PointerButton.Middle) }),
    Auxiliary({ button(PointerButton.Auxiliary(8)) }),
    PointerPress({ pointer(PointerEvent.Press(IntOffset(4, 5), PointerButton.Secondary)) }),
    PointerRelease({ pointer(PointerEvent.Release(IntOffset(4, 5), PointerButton.Auxiliary(8))) }),
    PointerMove({ pointer(PointerEvent.Move(IntOffset(4, 5))) }),
    PointerDrag({ pointer(PointerEvent.Drag(IntOffset(4, 5), PointerButton.Secondary, -0.25, 10.5)) }),
    PointerScroll({ pointer(PointerEvent.Scroll(IntOffset(4, 5), 0.5, -3.75)) }),
    ;

    /**
     * Constructs immutable events and decoder controls before the timed invocation.
     */
    fun prepare(): Prepared = prepareInput(this)

    /**
     * Owns one immutable input and its public encoder/decoder, without accumulating previous results.
     */
    class Prepared(
        val encode: () -> ProjectionValue,
        val decode: (ProjectionValue) -> Any,
        val expected: Any,
    )

    private fun keyboard(event: KeyboardEvent): Prepared = Prepared({ ProjectionInputCodec.keyboard(event) }, { ProjectionInputCodec.keyboard(it) }, event)

    private fun text(event: TextInputEvent): Prepared = Prepared({ ProjectionInputCodec.text(event) }, { ProjectionInputCodec.text(it) }, event)

    private fun filter(value: KeyboardInputFilter): Prepared = Prepared({ ProjectionInputCodec.filter(value) }, { ProjectionInputCodec.filter(it) }, value)

    private fun button(value: PointerButton): Prepared = Prepared({ ProjectionInputCodec.button(value) }, { ProjectionInputCodec.button(it) }, value)

    private fun pointer(event: PointerEvent): Prepared {
        val local = IntOffset(-34, 72)
        return Prepared({ ProjectionInputCodec.pointer(event, local) }, { ProjectionInputCodec.pointer(it) }, event to local)
    }
}
