package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.round

/**
 * Caller-owned owner-thread numeric state for one Slider.
 *
 * Values are finite, clamped to [range], and quantized to [steps] evenly spaced interior stops when steps are requested.
 * Distinct normalized writes synchronously invalidate attached retained observers.
 *
 * @param initialValue initial value normalized into [range].
 * @property range finite increasing inclusive range.
 * @property steps number of evenly spaced selectable values excluding both endpoints.
 */
public class SliderState(
    initialValue: Double,
    public val range: ClosedFloatingPointRange<Double> = 0.0..1.0,
    public val steps: Int = 0,
) {
    private val observable: ObservableComponentState<Double>

    init {
        require(range.start.isFinite() && range.endInclusive.isFinite() && range.start < range.endInclusive) {
            "Slider range must have finite increasing endpoints."
        }
        require(0 <= steps) { "Slider steps must be non-negative." }
        observable = ObservableComponentState(normalize(initialValue), ::validateNormalized)
    }

    /**
     * Current normalized value.
     */
    public var value: Double
        get() = observable.get()
        set(value) {
            observable.set(normalize(value))
        }

    /**
     * Current zero-to-one fraction within [range].
     */
    public val fraction: Double
        get() = (value - range.start) / (range.endInclusive - range.start)

    /**
     * Installs one privileged retained observer.
     */
    @InternalStrataRuntimeApi
    public fun observe(callback: (Double) -> Unit): ComponentStateSubscription = observable.observe(callback)

    private fun normalize(value: Double): Double {
        require(value.isFinite()) { "Slider value must be finite." }
        val clamped = value.coerceIn(range.start, range.endInclusive)
        if (steps == 0) return clamped
        val intervals = steps + 1
        val fraction = (clamped - range.start) / (range.endInclusive - range.start)
        return range.start + round(fraction * intervals.toDouble()) / intervals.toDouble() * (range.endInclusive - range.start)
    }

    private fun validateNormalized(value: Double) {
        require(value.isFinite() && value in range) { "Slider value must remain inside its finite range." }
    }
}
