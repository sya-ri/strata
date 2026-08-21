package dev.s7a.strata.modifier

import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.TextInputEvent

/**
 * Adds a focused callback for every key press and release.
 *
 * The modifier makes its logical component focusable, retains [callback] until replacement or disposal, and invokes it synchronously on the tree thread.
 *
 * @param callback focused key callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onKeyEvent(callback: (KeyboardEvent) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.EveryKey(callback)))

/**
 * Adds a focused key-press callback.
 *
 * @param callback focused typed callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onKeyPress(callback: (KeyboardEvent.Press) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.KeyPress(callback)))

/**
 * Adds a focused key-release callback.
 *
 * @param callback focused typed callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onKeyRelease(callback: (KeyboardEvent.Release) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.KeyRelease(callback)))

/**
 * Adds a focused callback for both committed characters and input-method preedit updates.
 *
 * @param callback focused typed callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onTextInput(callback: (TextInputEvent) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.EveryText(callback)))

/**
 * Adds a focused committed-character callback.
 *
 * @param callback focused typed callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onCharacterInput(callback: (TextInputEvent.Character) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.Character(callback)))

/**
 * Adds a focused input-method preedit callback.
 *
 * @param callback focused typed callback deciding propagation.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onPreedit(callback: (TextInputEvent.Preedit) -> InputResult): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.Preedit(callback)))

/**
 * Makes the logical component a keyboard and text-input focus target.
 *
 * Pointer focus is acquired only after a consuming primary press within the component's laid-out hit path.
 *
 * @return a modifier with active focus-target behavior appended nearest the component.
 */
public fun Modifier.focusable(): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.Focusable))

/**
 * Makes the logical component the initial focus target when layout has no retained focus owner.
 *
 * More than one placed initial target is a deterministic layout error.
 *
 * @return a modifier with active initial-focus behavior appended nearest the component.
 */
public fun Modifier.initialFocus(): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.InitialFocus))

/**
 * Adds a callback for distinct retained focus transitions and makes the logical component focusable.
 *
 * Detach delivers [FocusEvent.Lost] before retained ownership is suspended; permanent disposal releases [callback].
 *
 * @param callback synchronous distinct transition observer.
 * @return a modifier with the active callback appended nearest the component.
 */
public fun Modifier.onFocusChanged(callback: (FocusEvent) -> Unit): Modifier = then(FocusedInputModifier.Element(FocusedInputModifier.Action.FocusChange(callback)))
