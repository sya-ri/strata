@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Validates native source or target axes in 1 through 32,768 for exact signed-integer sampling.
 * The device may impose a lower limit; CPU sources and common logical geometry use their own bounds.
 *
 * @throws IllegalArgumentException when either axis is outside that range.
 */
@JvmSynthetic
internal fun requireFabricMinecraftCanvasExtent(size: IntSize) {
    val maximumAxis = 32_768
    require(size.width in 1..maximumAxis && size.height in 1..maximumAxis) {
        "Native Canvas image axes must be between 1 and $maximumAxis pixels; the device may impose a lower limit."
    }
}
