package dev.s7a.strata.modifier

import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.ui.UiSession

/**
 * Adds a focused callback for every key press and release.
 *
 * The modifier makes its logical component focusable, retains [callback] until replacement or disposal, and invokes it synchronously on the tree thread.
 *
 * @param callback focused key callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onKeyEvent(callback: UiSession.(KeyboardEvent) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.EveryKey(callback)))

/**
 * Adds a focused key-press callback.
 *
 * @param callback focused typed callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onKeyPress(callback: UiSession.(KeyboardEvent.Press) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.KeyPress(callback)))

/**
 * Adds a focused key-release callback.
 *
 * @param callback focused typed callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onKeyRelease(callback: UiSession.(KeyboardEvent.Release) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.KeyRelease(callback)))

/**
 * Adds a focused callback for both committed characters and input-method preedit updates.
 *
 * @param callback focused typed callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onTextInput(callback: UiSession.(TextInputEvent) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.EveryText(callback)))

/**
 * Adds a focused committed-character callback.
 *
 * @param callback focused typed callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onCharacterInput(callback: UiSession.(TextInputEvent.Character) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.Character(callback)))

/**
 * Adds a focused input-method preedit callback.
 *
 * @param callback focused typed callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onPreedit(callback: UiSession.(TextInputEvent.Preedit) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.Preedit(callback)))

/**
 * Makes the logical component a keyboard and text-input focus target.
 *
 * A primary press focuses the deepest and latest-painted accepting target within the laid-out hit path independently of ordinary pointer-event consumption.
 *
 * @return a modifier with active focus-target behavior appended nearest the component.
 */
public fun Modifier.focusable(): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.Focusable, BuiltinProjection.Focusable.properties()))

/**
 * Makes the logical component the initial focus target when layout has no retained focus owner.
 *
 * More than one placed initial target is a deterministic layout error.
 *
 * @return a modifier with active initial-focus behavior appended nearest the component.
 */
public fun Modifier.initialFocus(): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.InitialFocus, BuiltinProjection.InitialFocus.properties()))

/**
 * Adds a callback for distinct retained focus transitions and makes the logical component focusable.
 *
 * Detach delivers [FocusEvent.Lost] before retained ownership is suspended; permanent disposal releases [callback].
 *
 * @param callback synchronous distinct transition observer.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onFocusChanged(callback: UiSession.(FocusEvent) -> Unit): Modifier =
    then(
        FocusedInputModifier.Element(
            FocusedInputModifier.Action.FocusChange(callback),
            DeclarationProjection(BuiltinProjection.FocusChanged.type, callback) { handler, scope ->
                ProjectionValue.Integer(
                    scope.action(
                        ProjectionAction(BuiltinProjection.FocusChanged.type, { value ->
                            if (requireNotNull(value as? ProjectionValue.Flag).value) FocusEvent.Gained else FocusEvent.Lost
                        }, handler),
                    ),
                )
            },
        ),
    )
