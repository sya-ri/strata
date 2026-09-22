package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.projection.BuiltinProjection

/**
 * Subscribes to typed pointer notifications within the modifier's hit region, including local coordinates.
 * [propagation] is applied immediately; [action] runs on the screen owner thread, on the hosting server or proxy for remote screens.
 * Only the subscribed event variant and optional button are delivered or transmitted.
 */
public fun Modifier.onPointerEvent(
    propagation: InputResult,
    action: (PointerEvent, IntOffset) -> Unit,
): Modifier =
    then(
        PointerInputModifier.Element(
            PointerInputModifier.Action.Every { event, position ->
                action(event, position)
                propagation
            },
            DeclaredInputProjection.pointer(BuiltinProjection.PointerEvents, propagation, null, { it }, action),
        ),
    )

/**
 * Subscribes to typed pointer notifications within the modifier's hit region, including local coordinates.
 * [propagation] is applied immediately; [action] runs on the screen owner thread, on the hosting server or proxy for remote screens.
 * Only the subscribed event variant and optional button are delivered or transmitted.
 */
public fun Modifier.onPress(
    propagation: InputResult,
    button: PointerButton? = null,
    action: (PointerEvent.Press, IntOffset) -> Unit,
): Modifier =
    then(
        PointerInputModifier.Element(
            PointerInputModifier.Action.Press { event, position ->
                if (DeclaredInputProjection.matches(event, button)) {
                    action(event, position)
                    propagation
                } else {
                    InputResult.Ignored
                }
            },
            DeclaredInputProjection.pointer(BuiltinProjection.PointerPress, propagation, button, { requireNotNull(it as? PointerEvent.Press) }, action),
        ),
    )

/**
 * Subscribes to typed pointer notifications within the modifier's hit region, including local coordinates.
 * [propagation] is applied immediately; [action] runs on the screen owner thread, on the hosting server or proxy for remote screens.
 * Only the subscribed event variant and optional button are delivered or transmitted.
 */
public fun Modifier.onRelease(
    propagation: InputResult,
    button: PointerButton? = null,
    action: (PointerEvent.Release, IntOffset) -> Unit,
): Modifier =
    then(
        PointerInputModifier.Element(
            PointerInputModifier.Action.Release { event, position ->
                if (DeclaredInputProjection.matches(event, button)) {
                    action(event, position)
                    propagation
                } else {
                    InputResult.Ignored
                }
            },
            DeclaredInputProjection.pointer(BuiltinProjection.PointerRelease, propagation, button, { requireNotNull(it as? PointerEvent.Release) }, action),
        ),
    )

/**
 * Subscribes to typed pointer notifications within the modifier's hit region, including local coordinates.
 * [propagation] is applied immediately; [action] runs on the screen owner thread, on the hosting server or proxy for remote screens.
 * Only the subscribed event variant and optional button are delivered or transmitted.
 */
public fun Modifier.onMove(
    propagation: InputResult,
    action: (PointerEvent.Move, IntOffset) -> Unit,
): Modifier =
    then(
        PointerInputModifier.Element(
            PointerInputModifier.Action.Move { event, position ->
                action(event, position)
                propagation
            },
            DeclaredInputProjection.pointer(BuiltinProjection.PointerMove, propagation, null, { requireNotNull(it as? PointerEvent.Move) }, action),
        ),
    )

/**
 * Subscribes to typed pointer notifications within the modifier's hit region, including local coordinates.
 * [propagation] is applied immediately; [action] runs on the screen owner thread, on the hosting server or proxy for remote screens.
 * Only the subscribed event variant and optional button are delivered or transmitted.
 */
public fun Modifier.onDrag(
    propagation: InputResult,
    button: PointerButton? = null,
    action: (PointerEvent.Drag, IntOffset) -> Unit,
): Modifier =
    then(
        PointerInputModifier.Element(
            PointerInputModifier.Action.Drag { event, position ->
                if (DeclaredInputProjection.matches(event, button)) {
                    action(event, position)
                    propagation
                } else {
                    InputResult.Ignored
                }
            },
            DeclaredInputProjection.pointer(BuiltinProjection.PointerDrag, propagation, button, { requireNotNull(it as? PointerEvent.Drag) }, action),
        ),
    )

/**
 * Subscribes to typed pointer notifications within the modifier's hit region, including local coordinates.
 * [propagation] is applied immediately; [action] runs on the screen owner thread, on the hosting server or proxy for remote screens.
 * Only the subscribed event variant and optional button are delivered or transmitted.
 */
public fun Modifier.onScroll(
    propagation: InputResult,
    action: (PointerEvent.Scroll, IntOffset) -> Unit,
): Modifier =
    then(
        PointerInputModifier.Element(
            PointerInputModifier.Action.Scroll { event, position ->
                action(event, position)
                propagation
            },
            DeclaredInputProjection.pointer(BuiltinProjection.PointerScroll, propagation, null, { requireNotNull(it as? PointerEvent.Scroll) }, action),
        ),
    )

/**
 * Captures [button] locally when its press reaches this modifier and sends typed gesture notifications to its owner.
 * Matching presses, movement, scrolling, and matching-button drag/release events are consumed immediately.
 * Other buttons continue ordinary dispatch. Captured coordinates may lie outside the modifier bounds.
 * Changing the button leaves an active gesture attached to its original subscription until release or cancellation.
 * A remote subscription change retires its endpoints, so that old gesture cannot invoke replacement handlers.
 * [onCancel] runs on the owner thread for interrupted gestures; it is not called for an ordinary release.
 */
public fun Modifier.onCapturedPointerEvent(
    button: PointerButton,
    onCancel: (PointerButton) -> Unit,
    action: (PointerEvent, IntOffset) -> Unit,
): Modifier =
    then(
        CapturedPointerInputModifier.Element(
            onCancel,
            { event, position ->
                if (DeclaredInputProjection.matches(event, button)) {
                    action(event, position)
                    InputResult.Consumed
                } else {
                    InputResult.Ignored
                }
            },
            DeclaredInputProjection.capture(button, onCancel, action),
            button,
        ),
    )
