package dev.s7a.strata.modifier

import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.input.PointerButton

/**
 * Adds captured pointer panning and pointer-anchored scroll zooming.
 *
 * A matching press is consumed and acquires the tree's generic pointer capture, so matching drags and release continue outside this modifier's bounds and ancestor clips.
 * Dragging moves content with the pointer by adjusting the content center against the resolved scale.
 * Vertical scrolling applies `zoomStep ^ -deltaY` around the event's modifier-local position and consumes the scroll event.
 * Other buttons, pointer moves, and horizontal-only scrolling remain available to ordinary dispatch.
 * Capture cancellation forgets the unfinished gesture without changing the last committed transform.
 * The caller owns [state], and all interaction must run on its owner thread.
 *
 * @param state caller-owned transform updated synchronously by input.
 * @param panButton button that begins a captured pan gesture.
 * @param zoomStep finite factor greater than one for each logical vertical scroll unit.
 * @return this chain with one appended active pan-and-zoom input node.
 * @throws IllegalArgumentException when [zoomStep] is not finite or is not greater than one, or accepted input cannot produce a finite transform.
 * @throws IllegalStateException when input runs on a thread other than the state owner.
 * @throws Throwable when accepted input publishes state and an observer callback fails.
 */
public fun Modifier.panZoom(
    state: PanZoomState,
    panButton: PointerButton = PointerButton.Primary,
    zoomStep: Double = 1.12,
): Modifier = then(PanZoomModifier.Element(state, panButton, zoomStep))
