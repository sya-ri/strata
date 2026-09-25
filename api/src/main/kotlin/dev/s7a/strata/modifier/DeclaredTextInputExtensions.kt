package dev.s7a.strata.modifier

import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.ui.UiSession

/**
 * Subscribes to typed text notifications while deciding [propagation] immediately on the client.
 * [action] runs on the screen owner thread, on Paper for a remote screen, and returns no dispatch decision.
 * IME state remains local; only the requested event variant is sent to the server.
 */
public fun Modifier.onTextInput(
    propagation: InputResult,
    action: UiSession.(TextInputEvent) -> Unit,
): Modifier =
    then(
        FocusedInputModifier.Element(
            FocusedInputModifier.Action.EveryText { event ->
                action(event)
                propagation
            },
            DeclaredInputProjection.text(BuiltinProjection.TextInput, propagation, { it }, action),
        ),
    )

/**
 * Subscribes to typed text notifications while deciding [propagation] immediately on the client.
 * [action] runs on the screen owner thread, on Paper for a remote screen, and returns no dispatch decision.
 * IME state remains local; only the requested event variant is sent to the server.
 */
public fun Modifier.onCharacterInput(
    propagation: InputResult,
    action: UiSession.(TextInputEvent.Character) -> Unit,
): Modifier =
    then(
        FocusedInputModifier.Element(
            FocusedInputModifier.Action.Character { event ->
                action(event)
                propagation
            },
            DeclaredInputProjection.text(BuiltinProjection.CharacterInput, propagation, { requireNotNull(it as? TextInputEvent.Character) }, action),
        ),
    )

/**
 * Subscribes to typed text notifications while deciding [propagation] immediately on the client.
 * [action] runs on the screen owner thread, on Paper for a remote screen, and returns no dispatch decision.
 * IME state remains local; only the requested event variant is sent to the server.
 */
public fun Modifier.onPreedit(
    propagation: InputResult,
    action: UiSession.(TextInputEvent.Preedit) -> Unit,
): Modifier =
    then(
        FocusedInputModifier.Element(
            FocusedInputModifier.Action.Preedit { event ->
                action(event)
                propagation
            },
            DeclaredInputProjection.text(BuiltinProjection.PreeditInput, propagation, { requireNotNull(it as? TextInputEvent.Preedit) }, action),
        ),
    )
