package dev.s7a.strata.runtime

import kotlin.math.abs
import kotlin.math.exp

/**
 * Integrates a bounded fling using exponential decay and explicit monotonic frame timestamps.
 * Equal timestamps do no work; reversing the clock stops motion rather than applying a jump.
 */
internal class ScrollMomentum(
    initialVelocity: Double,
    initialTime: Long,
) {
    private var velocity = initialVelocity
    private var time = initialTime

    /**
     * Whether enough velocity remains for another visible scroll step.
     */
    val active: Boolean
        get() = MINIMUM_VELOCITY <= abs(velocity)

    /**
     * Returns the analytically integrated logical-pixel displacement for one host frame.
     */
    fun advance(nextTime: Long): Double {
        if (nextTime < time) {
            velocity = 0.0
            return 0.0
        }
        val seconds = secondsBetween(time, nextTime)
        time = nextTime
        val decay = exp(-DECELERATION * seconds)
        val distance = velocity * (1.0 - decay) / DECELERATION
        velocity *= decay
        return distance
    }

    private companion object {
        /**
         * Exponential rate of velocity loss per second.
         */
        const val DECELERATION = 9.0

        /**
         * Logical pixels per second below which motion stops.
         */
        const val MINIMUM_VELOCITY = 15.0
    }
}
