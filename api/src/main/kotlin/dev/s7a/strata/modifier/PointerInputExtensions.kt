package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerHoverEvent

/**
 * Handles every typed pointer event that hits this modifier's laid-out bounds.
 *
 * The callback runs on the owning tree thread in deepest/latest-painted-first dispatch order.
 * Returning [InputResult.Consumed] stops ordinary event propagation; returning [InputResult.Ignored] allows earlier candidates to run.
 *
 * @param callback callback receiving the tree event and modifier-local position.
 * @return this chain with one appended active pointer-input node.
 * @throws Throwable when [callback] fails during dispatch; the owning tree preserves the exact failure as primary while poisoning and cleaning retained ownership.
 */
public fun Modifier.onPointerEvent(callback: (PointerEvent, IntOffset) -> InputResult): Modifier = then(PointerInputModifier.Element(PointerInputModifier.Action.Every(callback)))

/**
 * Handles pointer events and captures the button whose press this handler consumes.
 *
 * Only one handler may own capture in a tree; a press cannot replace another active capture.
 * Moves and matching-button drags or releases then reach only this entry, including outside its bounds or ancestor clips, and stop propagation regardless of the returned result.
 * Coordinates remain relative to the latest committed layout without clamping, while other buttons, scrolling, and hover use ordinary hit testing.
 * A matching release clears capture before [callback] and never calls [onCancel].
 * Removal, replacement, unplacement, input reset, detachment, close, and failure clear capture before calling [onCancel] once, before this entry is disposed.
 * Updating callbacks in the same retained modifier position preserves capture and does not invalidate frame phases.
 * Both callbacks run on the owning tree thread; descriptions retain them until replacement or disposal.
 *
 * @param onCancel callback receiving the starting button of an interrupted gesture.
 * @param callback callback receiving the immutable tree event and current modifier-local position.
 * @return this chain with one appended active capture-capable pointer node.
 * @throws Throwable when either callback fails; the owning tree preserves the primary failure and still attempts remaining cleanup.
 */
public fun Modifier.onCapturedPointerEvent(
    onCancel: (PointerButton) -> Unit,
    callback: (PointerEvent, IntOffset) -> InputResult,
): Modifier = then(CapturedPointerInputModifier.Element(onCancel, callback))

/**
 * Handles every pointer press that hits this modifier's laid-out bounds.
 *
 * @param callback callback receiving the typed event and modifier-local position and deciding propagation.
 * @return this chain with one appended active press handler.
 * @throws Throwable when [callback] fails during dispatch.
 */
public fun Modifier.onPress(callback: (PointerEvent.Press, IntOffset) -> InputResult): Modifier = then(PointerInputModifier.Element(PointerInputModifier.Action.Press(callback)))

/**
 * Runs [action] for every primary pointer press that hits this modifier and consumes the event.
 * Other button presses remain ignored so lower or earlier handlers can process them.
 *
 * @param action synchronous action invoked on the owning tree thread.
 * @return this chain with one appended consuming press handler.
 * @throws Throwable when [action] fails during dispatch.
 */
public fun Modifier.onPress(action: () -> Unit): Modifier =
    onPress { event, _ ->
        if (event.button === PointerButton.Primary) {
            action()
            InputResult.Consumed
        } else {
            InputResult.Ignored
        }
    }

/**
 * Handles every pointer release that hits this modifier's laid-out bounds.
 *
 * @param callback callback receiving the typed event and modifier-local position and deciding propagation.
 * @return this chain with one appended active release handler.
 * @throws Throwable when [callback] fails during dispatch.
 */
public fun Modifier.onRelease(callback: (PointerEvent.Release, IntOffset) -> InputResult): Modifier = then(PointerInputModifier.Element(PointerInputModifier.Action.Release(callback)))

/**
 * Runs [action] for every pointer release that hits this modifier and consumes the event.
 *
 * @param action synchronous action invoked on the owning tree thread.
 * @return this chain with one appended consuming release handler.
 * @throws Throwable when [action] fails during dispatch.
 */
public fun Modifier.onRelease(action: () -> Unit): Modifier =
    onRelease { _, _ ->
        action()
        InputResult.Consumed
    }

/**
 * Handles every pointer move that hits this modifier's laid-out bounds.
 *
 * Use [onHover] for distinct enter and exit transitions, including exit during session detachment.
 *
 * @param callback callback receiving the typed event and modifier-local position and deciding propagation.
 * @return this chain with one appended active move handler.
 * @throws Throwable when [callback] fails during dispatch.
 */
public fun Modifier.onMove(callback: (PointerEvent.Move, IntOffset) -> InputResult): Modifier = then(PointerInputModifier.Element(PointerInputModifier.Action.Move(callback)))

/**
 * Runs [action] for every pointer move that hits this modifier without consuming the event.
 *
 * @param action synchronous action invoked on the owning tree thread.
 * @return this chain with one appended non-consuming move handler.
 * @throws Throwable when [action] fails during dispatch.
 */
public fun Modifier.onMove(action: () -> Unit): Modifier =
    onMove { _, _ ->
        action()
        InputResult.Ignored
    }

/**
 * Handles every pointer scroll event that hits this modifier's laid-out bounds.
 *
 * @param callback callback receiving the typed event and modifier-local position and deciding propagation.
 * @return this chain with one appended active scroll handler.
 * @throws Throwable when [callback] fails during dispatch.
 */
public fun Modifier.onScroll(callback: (PointerEvent.Scroll, IntOffset) -> InputResult): Modifier = then(PointerInputModifier.Element(PointerInputModifier.Action.Scroll(callback)))

/**
 * Runs [action] for every pointer scroll event that hits this modifier and consumes the event.
 *
 * @param action synchronous action invoked on the owning tree thread.
 * @return this chain with one appended consuming scroll handler.
 * @throws Throwable when [action] fails during dispatch.
 */
public fun Modifier.onScroll(action: () -> Unit): Modifier =
    onScroll { _, _ ->
        action()
        InputResult.Consumed
    }

/**
 * Observes distinct pointer enter and exit transitions for this modifier's laid-out bounds.
 *
 * Hover observation never consumes the corresponding move event.
 * It is event-driven: layout movement under a stationary pointer does not create a transition until another move arrives.
 * An owning session detachment emits [PointerHoverEvent.Exit] for an entered node before retaining the tree for possible reattachment.
 *
 * @param callback callback receiving distinct typed transitions on the owning tree thread.
 * @return this chain with one appended active hover observer.
 * @throws Throwable when [callback] fails while a move or detach transition is delivered.
 */
public fun Modifier.onHover(callback: (PointerHoverEvent) -> Unit): Modifier = then(PointerInputModifier.Element(PointerInputModifier.Action.Hover(callback)))
