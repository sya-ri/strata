package dev.s7a.strata.modifier

import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardInputFilter
import dev.s7a.strata.projection.BuiltinProjection

/**
 * Subscribes to typed keyboard notifications with a fixed immediate dispatch result.
 * Only events matching [filter] invoke [action] or cross a remote connection; other events continue propagation.
 * The handler runs on the screen owner thread, on Paper when the screen is remote.
 * [propagation] is decided locally without waiting for that handler; dynamic result callbacks use the other overload.
 */
public fun Modifier.onKeyEvent(
    propagation: InputResult,
    filter: KeyboardInputFilter = KeyboardInputFilter(),
    action: (KeyboardEvent) -> Unit,
): Modifier =
    then(
        FocusedInputModifier.Element(
            FocusedInputModifier.Action.EveryKey { event ->
                if (filter.matches(event)) {
                    action(event)
                    propagation
                } else {
                    InputResult.Ignored
                }
            },
            DeclaredInputProjection.keyboard(BuiltinProjection.KeyboardEvents, propagation, filter, { it }, action),
        ),
    )

/**
 * Subscribes to typed keyboard notifications with a fixed immediate dispatch result.
 * Only events matching [filter] invoke [action] or cross a remote connection; other events continue propagation.
 * The handler runs on the screen owner thread, on Paper when the screen is remote.
 * [propagation] is decided locally without waiting for that handler; dynamic result callbacks use the other overload.
 */
public fun Modifier.onKeyPress(
    propagation: InputResult,
    filter: KeyboardInputFilter = KeyboardInputFilter(),
    action: (KeyboardEvent.Press) -> Unit,
): Modifier =
    then(
        FocusedInputModifier.Element(
            FocusedInputModifier.Action.KeyPress { event ->
                if (filter.matches(event)) {
                    action(event)
                    propagation
                } else {
                    InputResult.Ignored
                }
            },
            DeclaredInputProjection.keyboard(BuiltinProjection.KeyPress, propagation, filter, { requireNotNull(it as? KeyboardEvent.Press) }, action),
        ),
    )

/**
 * Subscribes to typed keyboard notifications with a fixed immediate dispatch result.
 * Only events matching [filter] invoke [action] or cross a remote connection; other events continue propagation.
 * The handler runs on the screen owner thread, on Paper when the screen is remote.
 * [propagation] is decided locally without waiting for that handler; dynamic result callbacks use the other overload.
 */
public fun Modifier.onKeyRelease(
    propagation: InputResult,
    filter: KeyboardInputFilter = KeyboardInputFilter(),
    action: (KeyboardEvent.Release) -> Unit,
): Modifier =
    then(
        FocusedInputModifier.Element(
            FocusedInputModifier.Action.KeyRelease { event ->
                if (filter.matches(event)) {
                    action(event)
                    propagation
                } else {
                    InputResult.Ignored
                }
            },
            DeclaredInputProjection.keyboard(BuiltinProjection.KeyRelease, propagation, filter, { requireNotNull(it as? KeyboardEvent.Release) }, action),
        ),
    )
