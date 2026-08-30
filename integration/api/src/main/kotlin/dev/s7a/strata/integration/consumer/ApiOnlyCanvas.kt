package dev.s7a.strata.integration.consumer

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.canvasSource
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.StateSource

/**
 * Creates an API-only canvas whose caller optionally forwards captured pointer input to its image producer.
 *
 * The source stays externally owned; its image snapshots may arrive from any thread and are committed only by owner-thread frames.
 * Input callbacks execute on that owner thread and must return normally; only a consumed press starts capture.
 * The modifier is independent of image observation and gives the canvas no keyboard focus.
 *
 * @param frames externally owned immutable CPU image observations.
 * @param size positive logical destination size.
 * @param onCancel owner-thread cancellation callback for an interrupted captured gesture.
 * @param onPointerEvent owner-thread callback receiving unclamped local logical coordinates.
 * @return an unevaluated one-shot screen definition, owned by the caller until opened or closed.
 */
public fun createApiOnlyCanvasDefinition(
    frames: StateSource<DrawImage>,
    size: IntSize,
    onCancel: (PointerButton) -> Unit,
    onPointerEvent: (PointerEvent, IntOffset) -> InputResult,
): ScreenDefinition {
    val source = canvasSource(frames)
    return ScreenDefinition("API-only Canvas") {
        Canvas(
            source = source,
            size = size,
            modifier = Modifier.Empty.onCapturedPointerEvent(onCancel, onPointerEvent),
        )
    }
}
