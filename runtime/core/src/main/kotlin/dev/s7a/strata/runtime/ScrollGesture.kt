package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.HorizontalDragNode
import kotlin.math.abs

/**
 * Owns one deferred tap or vertical drag, releasing its original hit target when scrolling starts.
 * Distances are converted from tree coordinates into viewport-local logical pixels.
 */
internal class ScrollGesture(
    val identity: Any,
    press: PointerEvent.Press,
    hit: RetainedEntry,
    time: Long?,
    tapEnabled: Boolean,
) {
    private var phase: Phase = Phase.Pending(press, hit, time, tapEnabled)

    /**
     * Chooses vertical scrolling or horizontal capture after the local motion threshold, or keeps waiting.
     */
    fun drag(
        position: IntOffset,
        scale: Double,
        time: Long?,
    ): Drag? {
        return when (val current = phase) {
            is Phase.Pending -> {
                val dx = (position.x.toDouble() - current.press.position.x) / scale
                val dy = (position.y.toDouble() - current.press.position.y) / scale
                if (DRAG_THRESHOLD <= abs(dx) || DRAG_THRESHOLD <= abs(dy)) current.tapEnabled = false
                if (current.hit.node is HorizontalDragNode && DRAG_THRESHOLD <= abs(dx) && abs(dy) < abs(dx)) {
                    phase = Phase.Finished
                    return Drag.Horizontal(current.press, current.hit)
                }
                if (abs(dy) < DRAG_THRESHOLD) return null
                val velocity = Velocity(current.time)
                velocity.add(-dy, time)
                phase = Phase.Dragging(position, velocity)
                Drag.Scroll(-dy)
            }

            is Phase.Dragging -> {
                val delta = (current.position.y.toDouble() - position.y) / scale
                current.position = position
                current.velocity.add(delta, time)
                Drag.Scroll(delta)
            }

            Phase.Finished -> {
                null
            }
        }
    }

    /**
     * Finishes recognition without retaining the original press or item after release.
     */
    fun release(
        position: IntOffset,
        scale: Double,
        time: Long?,
        frameSeconds: Double,
    ): Release {
        val current = phase
        phase = Phase.Finished
        return when (current) {
            is Phase.Pending -> {
                val dx = abs(position.x.toDouble() - current.press.position.x) / scale
                val dy = abs(position.y.toDouble() - current.press.position.y) / scale
                if (current.tapEnabled && dx < DRAG_THRESHOLD && dy < DRAG_THRESHOLD) {
                    Release.Tap(current.press, current.hit)
                } else {
                    Release.Cancelled
                }
            }

            is Phase.Dragging -> {
                Release.Momentum(current.velocity.finish(time, frameSeconds))
            }

            Phase.Finished -> {
                Release.Cancelled
            }
        }
    }

    /**
     * Detects cleanup of an item whose press has not yet been delivered.
     */
    fun references(entry: RetainedEntry): Boolean = (phase as? Phase.Pending)?.hit === entry

    /**
     * The winning drag recognizer for one pointer sequence.
     */
    sealed interface Drag {
        /**
         * A viewport-local vertical offset displacement.
         */
        data class Scroll(
            val delta: Double,
        ) : Drag

        /**
         * An explicit horizontal control whose original press can now be delivered.
         */
        data class Horizontal(
            val press: PointerEvent.Press,
            val hit: RetainedEntry,
        ) : Drag
    }

    /**
     * The only work that may follow a matching release.
     */
    sealed interface Release {
        /**
         * A stationary tap whose original target must still be the current hit.
         */
        data class Tap(
            val press: PointerEvent.Press,
            val hit: RetainedEntry,
        ) : Release

        /**
         * A released drag, carrying its bounded logical-pixel velocity per second.
         */
        data class Momentum(
            val velocity: Double,
        ) : Release

        /**
         * A moved or cancelled pointer sequence with no activation.
         */
        data object Cancelled : Release
    }

    private sealed interface Phase {
        class Pending(
            val press: PointerEvent.Press,
            val hit: RetainedEntry,
            val time: Long?,
            var tapEnabled: Boolean,
        ) : Phase

        class Dragging(
            var position: IntOffset,
            val velocity: Velocity,
        ) : Phase

        data object Finished : Phase
    }

    private class Velocity(
        initialTime: Long?,
    ) {
        private var sampledAt = initialTime
        private var movedAt = initialTime
        private var distance = 0.0
        private var velocity = 0.0

        fun add(
            delta: Double,
            time: Long?,
        ) {
            if (delta == 0.0) return
            distance += delta
            movedAt = time
            val previous = sampledAt
            if (time != null && previous != null && previous < time) {
                velocity = distance / secondsBetween(previous, time)
                distance = 0.0
                sampledAt = time
            } else if (previous == null) {
                sampledAt = time
            }
        }

        fun finish(
            time: Long?,
            frameSeconds: Double,
        ): Double {
            val moved = movedAt ?: return 0.0
            val released = time ?: return 0.0
            if (released < moved) return 0.0
            if (MAXIMUM_RELEASE_PAUSE < secondsBetween(moved, released)) return 0.0
            val speed = if (distance != 0.0 && 0.0 < frameSeconds) distance / frameSeconds else velocity
            return speed.coerceIn(-MAXIMUM_VELOCITY, MAXIMUM_VELOCITY)
        }
    }

    private companion object {
        /**
         * Logical pixels of vertical motion required to choose scrolling over a tap.
         */
        const val DRAG_THRESHOLD = 4.0

        /**
         * Maximum pointer speed retained for kinetic scrolling, in logical pixels per second.
         */
        const val MAXIMUM_VELOCITY = 6_000.0

        /**
         * Seconds without pointer motion after which releasing cannot start a fling.
         */
        const val MAXIMUM_RELEASE_PAUSE = 0.1
    }
}

/**
 * Computes a frame-clock interval without narrowing opaque signed nanosecond timestamps.
 */
internal fun secondsBetween(
    start: Long,
    end: Long,
): Double = (end.toDouble() - start.toDouble()) / 1_000_000_000.0
