package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardInputFilter
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.modifier.onCharacterInput
import dev.s7a.strata.modifier.onDrag
import dev.s7a.strata.modifier.onKeyEvent
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.onKeyRelease
import dev.s7a.strata.modifier.onMove
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.modifier.onPreedit
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.onRelease
import dev.s7a.strata.modifier.onScroll
import dev.s7a.strata.modifier.onTextInput
import dev.s7a.strata.ui.UiSession

/**
 * Public subscription factories, with callbacks and filters prepared independently of construction and endpoint timing.
 */
internal enum class ProjectionFieldModifierCase {
    KeyEvery,
    KeyPress,
    KeyRelease,
    PointerEvery,
    PointerPress,
    PointerRelease,
    PointerMove,
    PointerDrag,
    PointerScroll,
    TextEvery,
    Character,
    Preedit,
    Capture,
    ;

    /**
     * Creates the real modifier and its captured immutable subscription policy.
     */
    fun create(): Modifier =
        when (this) {
            KeyEvery -> Modifier.Empty.onKeyEvent(InputResult.Consumed, filter, keyboard)
            KeyPress -> Modifier.Empty.onKeyPress(InputResult.Consumed, filter, keyboard)
            KeyRelease -> Modifier.Empty.onKeyRelease(InputResult.Consumed, filter, keyboard)
            PointerEvery -> Modifier.Empty.onPointerEvent(InputResult.Ignored, pointer)
            PointerPress -> Modifier.Empty.onPress(InputResult.Consumed, PointerButton.Secondary, pointer)
            PointerRelease -> Modifier.Empty.onRelease(InputResult.Consumed, PointerButton.Secondary, pointer)
            PointerMove -> Modifier.Empty.onMove(InputResult.Ignored, pointer)
            PointerDrag -> Modifier.Empty.onDrag(InputResult.Consumed, PointerButton.Secondary, pointer)
            PointerScroll -> Modifier.Empty.onScroll(InputResult.Ignored, pointer)
            TextEvery -> Modifier.Empty.onTextInput(InputResult.Ignored, text)
            Character -> Modifier.Empty.onCharacterInput(InputResult.Consumed, text)
            Preedit -> Modifier.Empty.onPreedit(InputResult.Ignored, text)
            Capture -> Modifier.Empty.onCapturedPointerEvent(PointerButton.Secondary, cancel, pointer)
        }

    private companion object {
        private val filter = KeyboardInputFilter(setOf(KeyCode.Enter), KeyboardModifiers(control = true))
        private val keyboard: UiSession.(KeyboardEvent) -> Unit = {}
        private val pointer: UiSession.(PointerEvent, IntOffset) -> Unit = { _, _ -> }
        private val text: UiSession.(TextInputEvent) -> Unit = {}
        private val cancel: UiSession.(PointerButton) -> Unit = {}
    }
}
