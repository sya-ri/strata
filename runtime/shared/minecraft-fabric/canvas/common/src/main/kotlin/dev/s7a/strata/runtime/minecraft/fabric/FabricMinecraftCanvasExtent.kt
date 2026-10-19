@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

private const val MAXIMUM_AXIS = 32_768

/**
 * Validates native source or target axes in 1 through 32,768 for exact signed-integer sampling.
 * The device may impose a lower limit; CPU sources and common logical geometry use their own bounds.
 *
 * @throws IllegalArgumentException when either axis is outside that range.
 */
@JvmSynthetic
internal fun requireFabricMinecraftCanvasExtent(size: IntSize) {
    require(size.width in 1..MAXIMUM_AXIS && size.height in 1..MAXIMUM_AXIS) {
        "Native Canvas image axes must be between 1 and $MAXIMUM_AXIS pixels; the device may impose a lower limit."
    }
}
