package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent

/**
 * Handles every pointer drag that hits this modifier's laid-out bounds.
 *
 * Drag events retain the held button and finite native displacement in addition to the current absolute position.
 * Use [onHover] for distinct enter and exit transitions produced by both ordinary movement and dragging.
 *
 * @param callback callback receiving the typed event and modifier-local position and deciding propagation.
 * @return this chain with one appended active drag handler.
 * @throws Throwable when [callback] fails during dispatch.
 */
public fun Modifier.onDrag(callback: (PointerEvent.Drag, IntOffset) -> InputResult): Modifier = then(PointerInputModifier.Element(PointerInputModifier.Action.Drag(callback)))

/**
 * Runs [action] for every pointer drag that hits this modifier without consuming the event.
 *
 * @param action synchronous action invoked on the owning tree thread.
 * @return this chain with one appended non-consuming drag handler.
 * @throws Throwable when [action] fails during dispatch.
 */
public fun Modifier.onDrag(action: () -> Unit): Modifier =
    onDrag { _, _ ->
        action()
        InputResult.Ignored
    }
