package dev.s7a.strata.component

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Describes a canvas displaying one immutable CPU image without a native runtime dependency.
 *
 * The [DrawImage] already owns detached straight ARGB storage; this factory never retains a caller-owned pixel array.
 * The returned source is immutable and thread-safe to share, and each attachment owns an independent binding.
 * No subscription or retained resource is acquired until the canvas attaches.
 *
 * @param image positive immutable source image, retained without copying its already immutable pixels.
 * @return an externally owned source suitable for both native and headless presentation.
 * @throws IllegalArgumentException when the image has an empty extent.
 */
public fun canvasSource(image: DrawImage): CanvasSource {
    require(0 < image.size.width && 0 < image.size.height) { "Canvas source dimensions must be positive." }
    return CpuCanvasSource { StateSubscription(StateSnapshot(StateRevision(0), image)) {} }
}

/**
 * Describes a canvas observing revisioned immutable CPU frames through the existing state-source protocol.
 *
 * Each attachment subscribes independently and closes only its subscription when replaced, suspended, or removed.
 * Any-thread callbacks only replace the newest pending snapshot and never invalidate or mutate the retained tree.
 * A timed or untimed owner-thread frame commits its cutoff before layout and painting; newer notifications wait until the next frame.
 * The binding retains only a committed image and newest pending image between frames, plus one transaction-local cutoff snapshot while a frame is being committed.
 * Every published image must have positive extent and own immutable straight ARGB storage.
 *
 * @param frames externally owned source, which remains open after every canvas closes.
 * @return an immutable source description without an active observation.
 */
public fun canvasSource(frames: StateSource<DrawImage>): CanvasSource = CpuCanvasSource(frames)
